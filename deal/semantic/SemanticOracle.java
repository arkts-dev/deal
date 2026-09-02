package deal.semantic;

import deal.semantic.ir.ActualKind;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryContext;
import deal.semantic.ir.BoundaryExecutor;
import deal.semantic.ir.BoundaryFailure;
import deal.semantic.ir.BoundaryOutcome;
import deal.semantic.ir.BoundaryValueView;
import deal.semantic.ir.ComparisonExecutor;
import deal.semantic.ir.ComparisonOperandView;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.IterationMode;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.NormalizedSlot;
import deal.semantic.ir.OpId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.StdlibFunctionId;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.UnicodeScalars;
import deal.semantic.ir.ValueId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The semantic oracle of {@code deal.semantic-ir/1} — an independent
 * interpreter over a validated {@link LoweredModuleUnit} plus its
 * {@link StructuredBodyTable} (semantic-lowering-differential-conformance
 * D5; the oracle carrier of the decomposition-tail integration
 * verification, ISSUE-0410). It invokes no target backend, no generated
 * artifact, no Java execution of emitted code, and no LuaJIT: execution is
 * realized directly from the validated IR.
 *
 * <p><b>Execution shape (the decomposition-tail carrier contract).</b>
 * The oracle executes the entry module's module-init block in list order
 * (the unit's {@code ModuleInitPlan} block, whose ops include the
 * produced entry delegation — the lowerer's module-init-level
 * {@code CALL(DIRECT main)} followed by its {@code DISCARD}); the run's
 * result atom is the value the trailing {@code DISCARD} discarded (the
 * entry call's committed value). Every executed op emits exactly one
 * START and one terminal (SUCCESS/FAILURE) {@link
 * SemanticRuntimeModel.TraceEvent} carrying the op's contract digest and
 * the structural {@code parentOpId} of its validated origin; children
 * (boundary ops of calls, chains, and reads) execute with their own
 * events. Completed operands' atoms are the START inputs; the produced
 * value atom is the SUCCESS output; a DEAL failure publishes the exact
 * error snapshot (code, canonical message, origin, attained
 * expected/actual, active frames innermost-first, nested cause). Console
 * stdlib calls record one ordered {@link SemanticRuntimeModel.EffectEvent}
 * per terminal. A DEAL failure escaping the module-init block is the
 * run's {@link SemanticRuntimeModel.Terminal.DealFailure}; otherwise the
 * run succeeds with the entry result atom.</p>
 *
 * <p><b>Runtime conventions pinned here (consumed identically by the
 * shared emitters).</b></p>
 * <ul>
 *   <li>Bindings: one runtime cell per {@code (BindingId, generation)}
 *       incarnation; {@code BINDING_ALLOC} creates the cell,
 *       {@code BINDING_INIT}/{@code BINDING_STORE} commit into it,
 *       {@code BINDING_LOAD} reads it (generation-checked). Closures
 *       capture the cells current at {@code CLOSURE_NEW} execution
 *       ({@code captures} are binding identities; loads inside the body
 *       carry their incarnation generations). A {@code FOR_EACH}
 *       iteration replaces the iteration binding's cell with a fresh one
 *       per iteration, so closures created in the body observe
 *       per-iteration values.</li>
 *   <li>Parameters: a callee body block's leading {@code BINDING_ALLOC}
 *       ops in list order are its parameter cells (the lowerer's
 *       body-entry parameter ALLOCs); a {@code CALL} binds the
 *       boundary-checked argument values to those cells before the
 *       remaining body ops execute.</li>
 *   <li>The single return boundary: {@code RETURN} executes the
 *       {@code FUNCTION_RETURN} boundary named by its payload (its
 *       origin's parent), publishes the checked value as the enclosing
 *       call's return value, and transfers. The CALL terminal records the
 *       value without re-checking.</li>
 *   <li>Transfers: {@code RETURN}/{@code BREAK}/{@code CONTINUE} succeed
 *       and then signal; the signal unwinds to the matching structure
 *       ({@code CALL} invocation, {@code LOOP}/{@code FOR_EACH} with the
 *       recorded target op) through enclosing {@code TRY_CATCH}es without
 *       being caught.</li>
 *   <li>Error values: {@code THROW} consumes an {@code Error} value
 *       ({@code code,message}); {@code TRY_CATCH} reifies a caught DEAL
 *       failure as an {@code Error} value bound to the catch binding; a
 *       catch failure carries its own origin with {@code cause} = the
 *       original snapshot. Only DEAL failures (E8) are catchable.</li>
 * </ul>
 *
 * <p><b>Purity of consumers.</b> The oracle, the shared LuaJIT emitter
 * artifact, and the shared JVM emitter artifact execute the identical
 * validated unit; the differential harness validates every event against
 * the exact IR op and compares the three reports event-for-event —
 * duplicated evaluations, wrong selectors, missing boundaries, and moved
 * children fail even when printed output coincides.</p>
 */
public final class SemanticOracle {

    private SemanticOracle() {
    }

    /** The module-level entry convention: {@code main} is the entry function. */
    public static final String ENTRY_FUNCTION = "main";

    /**
     * Executes the entry module of the validated project: the module-init
     * block in list order (which ends with the produced entry delegation
     * CALL + DISCARD), recording the exact semantic trace, the ordered
     * effects, and the terminal.
     *
     * @param unit  the validated lowered module unit; non-null
     * @param table the unit's produced block-membership table; non-null
     * @return the complete consumer run report
     */
    public static SemanticRuntimeModel.ConsumerRun execute(LoweredModuleUnit unit,
                                                           StructuredBodyTable table) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(table, "table must not be null");
        Execution state = new Execution(unit, table);
        try {
            state.runBlock(unit.moduleInit().initBlock());
        } catch (DealFailure failure) {
            return state.report(new SemanticRuntimeModel.Terminal.DealFailure(
                state.snapshot(failure)));
        }
        String resultAtom = state.entryResultAtom != null
            ? state.entryResultAtom : "null";
        return state.report(new SemanticRuntimeModel.Terminal.Success(resultAtom));
    }

    // =========================================================================
    // Runtime values
    // =========================================================================

    /** The oracle's closed runtime-value model. */
    public sealed interface Value
        permits Value.NullValue, Value.MissingValue, Value.BoolValue, Value.IntValue,
                Value.NumValue, Value.StrValue, Value.TableValue, Value.ArrayValue,
                Value.FuncValue, Value.IntrinsicValue, Value.ErrorValue, Value.SlotValue {

        enum NullValue implements Value { INSTANCE }

        enum MissingValue implements Value { INSTANCE }

        record BoolValue(boolean value) implements Value {
        }

        record IntValue(long value) implements Value {
        }

        record NumValue(double value) implements Value {
        }

        record StrValue(String value) implements Value {
        }

        /** An insertion-ordered table over string keys. */
        record TableValue(LinkedHashMap<String, Value> entries) implements Value {
        }

        /** A dense array; deleted slots hold {@link MissingValue}. */
        record ArrayValue(List<Value> elements, RuntimeDescriptor elementDescriptor)
            implements Value {
        }

        /** A closure allocation: the lowered body plus its captured cells. */
        record FuncValue(FunctionId function, RuntimeDescriptor.Func signature,
                         Map<BindingId, Cell> captures) implements Value {
        }

        /** An intrinsic/export function value (int()/number(), stdlib exports). */
        record IntrinsicValue(String name) implements Value {
        }

        /** The caught/reified DEAL {@code Error} value {@code {code, message}}. */
        record ErrorValue(String code, String message) implements Value {
        }

        /** The normalize-computed slot (internal; never a language value). */
        record SlotValue(NormalizedSlot slot) implements Value {
        }
    }

    /** One binding cell incarnation: binding, generation, and current value. */
    static final class Cell {
        final BindingId binding;
        final long generation;
        Value value;
        boolean initialized;

        Cell(BindingId binding, long generation) {
            this.binding = binding;
            this.generation = generation;
        }
    }

    /** A DEAL failure with its exact projection facts. */
    public static final class DealFailure extends RuntimeException {
        final String code;
        final String message;
        final SourceOrigin origin;
        final String expected;
        final String actual;
        final DealFailure cause;
        final List<FunctionId> frames;

        DealFailure(String code, String message, SourceOrigin origin,
                    String expected, String actual, DealFailure cause,
                    List<FunctionId> frames) {
            super(message, cause);
            this.code = code;
            this.message = message;
            this.origin = origin;
            this.expected = expected;
            this.actual = actual;
            this.cause = cause;
            this.frames = List.copyOf(frames);
        }

        static DealFailure of(BoundaryFailure failure, SourceOrigin origin,
                              List<FunctionId> frames) {
            return new DealFailure(failure.code().code(), failure.message(), origin,
                failure.expected(), failure.actual(), null, frames);
        }
    }

    /** A RETURN transfer: the checked value returns to the enclosing invocation. */
    static final class ReturnSignal extends RuntimeException {
        final Value value;

        ReturnSignal(Value value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    /** A BREAK/CONTINUE transfer naming the recorded loop target. */
    static final class LoopSignal extends RuntimeException {
        final OpId target;
        final boolean isContinue;

        LoopSignal(OpId target, boolean isContinue) {
            super(null, null, false, false);
            this.target = target;
            this.isContinue = isContinue;
        }
    }

    // =========================================================================
    // Execution state
    // =========================================================================

    private static final class Execution {
        final LoweredModuleUnit unit;
        final StructuredBodyTable table;
        final Map<OpId, SemanticOp> opsById = new HashMap<>();
        final Map<OpId, List<SemanticOp>> childrenByParent = new HashMap<>();
        /**
         * The payload-owned children: ops referenced by an owner payload's
         * child/boundary id lists (chain children, boundary children of
         * ARRAY_NEW/CALL/STDLIB_CALL/MEMBER_READ/INDEX_READ/RETURN). The
         * block walk skips them — their owner arm executes each exactly
         * once in payload order; a double execution would emit duplicated
         * effects and fail the trace pairing.
         */
        final java.util.Set<OpId> ownedChildren = new java.util.HashSet<>();
        final Map<ValueId, Value> values = new HashMap<>();
        final Map<String, Cell> cells = new HashMap<>();
        final List<SemanticRuntimeModel.TraceEvent> trace = new ArrayList<>();
        final List<SemanticRuntimeModel.EffectEvent> effects = new ArrayList<>();
        final List<FunctionId> frames = new ArrayList<>();
        long sequence = 0;
        String entryResultAtom = null;

        Execution(LoweredModuleUnit unit, StructuredBodyTable table) {
            this.unit = unit;
            this.table = table;
            for (SemanticOp op : unit.ops()) {
                opsById.put(op.opId(), op);
            }
            for (SemanticOp op : unit.ops()) {
                OpId parent = op.origin().parentOpId();
                if (parent != null) {
                    childrenByParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(op);
                }
            }
            // Payload-referenced children (executed exactly once by their
            // owner arm in payload order) plus boundary children of
            // MEMBER_READ/STDLIB_CALL (their owner arms' single-child
            // execution): the block walk skips these.
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
                        List<SemanticOp> children = childrenByParent.get(op.opId());
                        if (children != null) {
                            for (SemanticOp child : children) {
                                if (child.kind() == SemanticOpKind.BOUNDARY) {
                                    ownedChildren.add(child.opId());
                                }
                            }
                        }
                    }
                    case KindPayload.MemberReadPayload ignored -> {
                        List<SemanticOp> children = childrenByParent.get(op.opId());
                        if (children != null) {
                            for (SemanticOp child : children) {
                                if (child.kind() == SemanticOpKind.BOUNDARY) {
                                    ownedChildren.add(child.opId());
                                }
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        SemanticRuntimeModel.ConsumerRun report(SemanticRuntimeModel.Terminal terminal) {
            StringBuilder report = new StringBuilder();
            report.append("semantic-oracle: module=").append(unit.moduleId())
                .append(" events=").append(trace.size())
                .append(" effects=").append(effects.size())
                .append(" terminal=").append(terminal instanceof
                    SemanticRuntimeModel.Terminal.Success success
                        ? "success(" + success.resultAtom() + ")" : "deal-failure");
            return new SemanticRuntimeModel.ConsumerRun("semantic-oracle",
                unit.moduleId().path(), unit.interfaceHash(), unit.loweringContextHash(),
                trace, effects, terminal, report.toString());
        }

        // -- atoms -----------------------------------------------------------------

        String atomOf(Value value) {
            if (value == null) {
                return "missing";
            }
            return switch (value) {
                case Value.NullValue ignored -> "null";
                case Value.MissingValue ignored -> "missing";
                case Value.BoolValue bool -> "bool:" + bool.value();
                case Value.IntValue intValue -> SemanticRuntimeModel.intAtom(intValue.value());
                case Value.NumValue num -> SemanticRuntimeModel.numberAtom(num.value());
                case Value.StrValue str -> "str:" + SemanticRuntimeModel.escapeString(str.value());
                case Value.ErrorValue error -> "err:" + error.code() + ":"
                    + SemanticRuntimeModel.escapeString(error.message());
                case Value.SlotValue slot -> slot.slot() instanceof NormalizedSlot.ArraySlot array
                    ? "slot:" + array.index() + "/" + array.present() + "/" + array.append()
                    : "keyslot:" + SemanticRuntimeModel.escapeString(
                        ((NormalizedSlot.TableSlot) slot.slot()).key());
                case Value.TableValue table -> allocate(table);
                case Value.ArrayValue array -> allocate(array);
                case Value.FuncValue func -> allocate(func);
                case Value.IntrinsicValue intrinsic -> allocate(intrinsic);
            };
        }

        /**
         * Allocation ids are assigned on first observation, not at
         * allocation time (the trace/effect/result-order normalization
         * rule): heap values reach the trace through START inputs/SUCCESS
         * outputs/effects, and the id is bound the first time the value is
         * atomized.
         */
        private final Map<Value, String> allocationIds = new java.util.IdentityHashMap<>();
        private long nextAllocationOrdinal = 1;

        private String allocate(Value heap) {
            return "ref:" + allocationIds.computeIfAbsent(heap,
                k -> String.valueOf(nextAllocationOrdinal++));
        }


        /** The atom of an op's operand value. */
        String operandAtom(SemanticOp op, int index) {
            return atomOf(values.get(op.operands().get(index)));
        }

        String originText(SourceOrigin origin) {
            SourceSpan span = origin.span();
            return SemanticRuntimeModel.originAtom(origin.sourceId(),
                span == null ? null : span.startLine(),
                span == null ? null : span.startColumn());
        }

        // -- events -----------------------------------------------------------------

        void emitStart(SemanticOp op, List<String> inputs) {
            trace.add(new SemanticRuntimeModel.TraceEvent(sequence++, unit.moduleId().path(),
                op.opId(), op.origin().parentOpId(), op.kind(),
                SemanticRuntimeModel.Phase.START, op.contract().canonicalDigest(),
                inputs, null, null));
        }

        void emitSuccess(SemanticOp op, String output) {
            trace.add(new SemanticRuntimeModel.TraceEvent(sequence++, unit.moduleId().path(),
                op.opId(), op.origin().parentOpId(), op.kind(),
                SemanticRuntimeModel.Phase.SUCCESS, op.contract().canonicalDigest(),
                List.of(), output, null));
        }

        void emitFailure(SemanticOp op, DealFailure failure) {
            trace.add(new SemanticRuntimeModel.TraceEvent(sequence++, unit.moduleId().path(),
                op.opId(), op.origin().parentOpId(), op.kind(),
                SemanticRuntimeModel.Phase.FAILURE, op.contract().canonicalDigest(),
                List.of(), null, snapshot(failure)));
        }

        SemanticRuntimeModel.ErrorSnapshot snapshot(DealFailure failure) {
            List<String> frameTexts = new ArrayList<>();
            for (FunctionId frame : failure.frames) {
                frameTexts.add(String.valueOf(frame.id()));
            }
            return new SemanticRuntimeModel.ErrorSnapshot(failure.code, failure.message,
                originText(failure.origin), failure.expected, failure.actual, frameTexts,
                failure.cause == null ? null : snapshot(failure.cause));
        }

        // -- cells -------------------------------------------------------------------

        Cell cellOf(BindingId binding, long generation, boolean create) {
            String key = binding + "#" + generation;
            Cell cell = cells.get(key);
            if (cell == null && create) {
                cell = new Cell(binding, generation);
                cells.put(key, cell);
            }
            return cell;
        }

        /** The binding's current (latest-generation) cell. */
        Cell currentCellOf(BindingId binding) {
            Cell latest = null;
            for (Cell cell : cells.values()) {
                if (cell.binding.equals(binding)) {
                    if (latest == null || cell.generation > latest.generation) {
                        latest = cell;
                    }
                }
            }
            return latest;
        }

        // -- control flow ------------------------------------------------------------

        /** Runs a block's ops in list order; transfers and failures propagate. */
        void runBlock(BlockId block) {
            List<OpId> ops = table.blockOps().get(block);
            if (ops == null) {
                throw new IllegalStateException("block " + block
                    + " has no membership row (a malformed table — the production "
                    + "validator rejects this)");
            }
            for (OpId opId : ops) {
                if (ownedChildren.contains(opId)) {
                    continue; // payload-owned: the owner arm executes it once
                }
                SemanticOp op = opsById.get(opId);
                if (op == null) {
                    throw new IllegalStateException("op " + opId
                        + " is not a member of the validated unit");
                }
                execute(op);
            }
        }

        // =========================================================================
        // Operation execution
        // =========================================================================

        void execute(SemanticOp op) {
            List<String> inputs = new ArrayList<>();
            for (ValueId operand : op.operands()) {
                inputs.add(atomOf(values.get(operand)));
            }
            emitStart(op, inputs);
            try {
                String output = executeKind(op);
                emitSuccess(op, output);
            } catch (ReturnSignal | LoopSignal signal) {
                emitSuccess(op, null);
                throw signal;
            } catch (DealFailure failure) {
                emitFailure(op, failure);
                throw failure;
            }
        }

        private String executeKind(SemanticOp op) {
            return switch (op.kind()) {
                case CONST -> executeConst(op);
                case UNARY -> executeUnary(op);
                case BINARY -> executeBinary(op);
                case STRING_CONCAT -> executeConcat(op);
                case ARRAY_NEW -> executeArrayNew(op);
                case TABLE_NEW -> executeTableNew(op);
                case ARRAY_LENGTH -> executeArrayLength(op);
                case MEMBER_READ -> executeMemberRead(op);
                case MEMBER_WRITE, MEMBER_DELETE, INDEX_WRITE, INDEX_DELETE,
                     FIELD_WRITE, FIELD_DELETE -> executeCommit(op);
                case INDEX_NORMALIZE -> executeNormalize(op);
                case INDEX_READ -> executeIndexRead(op);
                case BOUNDARY -> executeBoundary(op);
                case BINDING_ALLOC -> executeBindingAlloc(op);
                case BINDING_INIT -> executeBindingInit(op);
                case BINDING_LOAD -> executeBindingLoad(op);
                case BINDING_STORE -> executeBindingStore(op);
                case CLOSURE_NEW -> executeClosureNew(op);
                case ASSIGN -> executeAssign(op);
                case DELETE -> executeDelete(op);
                case CALL -> executeCall(op);
                case INTRINSIC_CALL -> executeIntrinsic(op);
                case STDLIB_CALL -> executeStdlib(op);
                case BRANCH -> executeBranch(op);
                case LOOP -> executeLoop(op);
                case FOR_EACH -> executeForEach(op);
                case TRY_CATCH -> executeTryCatch(op);
                case THROW -> executeThrow(op);
                case RETURN -> executeReturn(op);
                case BREAK -> executeBreak(op);
                case CONTINUE -> executeContinue(op);
                case DISCARD -> executeDiscard(op);
                case MODULE_IMPORT -> executeModuleImport(op);
                case EXPORT_READ -> executeExportRead(op);
                default -> throw new IllegalStateException(
                    "op kind " + op.kind() + " has no oracle execution in this "
                        + "decomposition-tail domain (the carrier executes the "
                        + "EVALUATION_ORDER op set; " + op.kind()
                        + " is another epic's production)");
            };
        }

        /** Publishes an op's result value and returns its atom. */
        private String publish(SemanticOp op, Value value) {
            if (op.result() instanceof ValueId valueId) {
                values.put(valueId, value);
            }
            return atomOf(value);
        }

        private String executeConst(SemanticOp op) {
            KindPayload.ConstPayload payload = (KindPayload.ConstPayload) op.payload();
            ScalarValue scalar = payload.value();
            Value value = switch (scalar) {
                case ScalarValue.Null ignored -> Value.NullValue.INSTANCE;
                case ScalarValue.Boolean bool -> new Value.BoolValue(bool.value());
                case ScalarValue.Int intValue -> new Value.IntValue(intValue.value());
                case ScalarValue.Number number -> new Value.NumValue(number.value());
                case ScalarValue.String string -> new Value.StrValue(string.value());
            };
            return publish(op, value);
        }

        private String executeUnary(SemanticOp op) {
            KindPayload.UnaryPayload payload = (KindPayload.UnaryPayload) op.payload();
            Value input = values.get(op.operands().get(0));
            return switch (payload.selector()) {
                case BOOL_NOT -> publish(op,
                    new Value.BoolValue(!((Value.BoolValue) input).value()));
                case INT32_NEG -> {
                    long value = ((Value.IntValue) input).value();
                    if (value == Integer.MIN_VALUE) {
                        throw int32ResultFailure(op);
                    }
                    yield publish(op, new Value.IntValue(-value));
                }
                case NUMBER_NEG -> publish(op,
                    new Value.NumValue(-((Value.NumValue) input).value()));
            };
        }

        private String executeBinary(SemanticOp op) {
            KindPayload.BinaryPayload payload = (KindPayload.BinaryPayload) op.payload();
            Value left = values.get(op.operands().get(0));
            Value right = values.get(op.operands().get(1));
            if (payload.selector().name().startsWith("INT32_")
                    || payload.selector().name().startsWith("NUMBER_")) {
                boolean comparison = payload.selector().name().endsWith("_EQ")
                    || payload.selector().name().endsWith("_NE")
                    || payload.selector().name().endsWith("_LT")
                    || payload.selector().name().endsWith("_LE")
                    || payload.selector().name().endsWith("_GT")
                    || payload.selector().name().endsWith("_GE");
                if (!comparison) {
                    return publish(op, arithmetic(op, payload.selector(), left, right));
                }
            }
            boolean result = ComparisonExecutor.compare(payload.selector(),
                viewOf(left), viewOf(right), payload.innerDescriptor(), payload.side());
            return publish(op, new Value.BoolValue(result));
        }

        /** The closed int32/number arithmetic rows (E8004/E8005/E8006 projections). */
        private Value arithmetic(SemanticOp op, deal.semantic.ir.BinarySelector selector,
                                 Value left, Value right) {
            return switch (selector) {
                case INT32_ADD -> int32Result(op,
                    ((Value.IntValue) left).value() + ((Value.IntValue) right).value());
                case INT32_SUB -> int32Result(op,
                    ((Value.IntValue) left).value() - ((Value.IntValue) right).value());
                case INT32_MUL -> int32Result(op,
                    ((Value.IntValue) left).value() * ((Value.IntValue) right).value());
                case INT32_DIV_TRUNC -> {
                    long divisor = ((Value.IntValue) right).value();
                    if (divisor == 0) {
                        throw registryFailure(op, FailurePolicyId.INT32_DIVISOR_THEN_RESULT,
                            null, null);
                    }
                    long dividend = ((Value.IntValue) left).value();
                    if (dividend == Integer.MIN_VALUE && divisor == -1) {
                        throw registryFailure(op, FailurePolicyId.INT32_RESULT, null, null);
                    }
                    yield int32Result(op, dividend / divisor);
                }
                case INT32_MOD_TRUNC -> {
                    long divisor = ((Value.IntValue) right).value();
                    if (divisor == 0) {
                        throw registryFailure(op, FailurePolicyId.INT32_DIVISOR_THEN_RESULT,
                            null, null);
                    }
                    yield int32Result(op, ((Value.IntValue) left).value() % divisor);
                }
                case INT32_POW -> {
                    long exponent = ((Value.IntValue) right).value();
                    if (exponent < 0) {
                        throw registryFailure(op, FailurePolicyId.INT32_EXPONENT_THEN_RESULT,
                            null, null);
                    }
                    long base = ((Value.IntValue) left).value();
                    long result = 1;
                    try {
                        for (long i = 0; i < exponent; i++) {
                            result = Math.multiplyExact(result, base);
                        }
                    } catch (ArithmeticException overflow) {
                        throw registryFailure(op, FailurePolicyId.INT32_RESULT, null, null);
                    }
                    yield int32Result(op, result);
                }
                case NUMBER_ADD -> new Value.NumValue(
                    ((Value.NumValue) left).value() + ((Value.NumValue) right).value());
                case NUMBER_SUB -> new Value.NumValue(
                    ((Value.NumValue) left).value() - ((Value.NumValue) right).value());
                case NUMBER_MUL -> new Value.NumValue(
                    ((Value.NumValue) left).value() * ((Value.NumValue) right).value());
                case NUMBER_DIV_IEEE -> new Value.NumValue(
                    ((Value.NumValue) left).value() / ((Value.NumValue) right).value());
                case NUMBER_MOD_FLOOR -> {
                    double a = ((Value.NumValue) left).value();
                    double b = ((Value.NumValue) right).value();
                    yield new Value.NumValue(a - Math.floor(a / b) * b);
                }
                case NUMBER_POW_IEEE -> new Value.NumValue(
                    Math.pow(((Value.NumValue) left).value(),
                        ((Value.NumValue) right).value()));
                default -> throw new IllegalStateException(
                    "selector " + selector + " is not an arithmetic row");
            };
        }

        /** The signed32 range projection: E8004 at the operation origin. */
        private Value int32Result(SemanticOp op, long result) {
            if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
                throw registryFailure(op, FailurePolicyId.INT32_RESULT, null, null);
            }
            return new Value.IntValue(result);
        }

        /** A pinned registry-row failure at the operation origin. */
        private DealFailure registryFailure(SemanticOp op, FailurePolicyId policy,
                                            String expected, String actual) {
            BoundaryFailure failure = BoundaryFailure.fromRow(
                FailureContractRegistry.row(policy), 0, expected, actual,
                new LinkedHashMap<>(), null);
            return DealFailure.of(failure, op.origin(), List.copyOf(frames));
        }

        private DealFailure int32ResultFailure(SemanticOp op) {
            return registryFailure(op, FailurePolicyId.INT32_RESULT, null, null);
        }

        /** Identity wrappers for heap values (record equality never decides
         *  reference identity). */
        private final Map<Value, Object> refIdentities = new java.util.IdentityHashMap<>();

        private Object refIdentity(Value heap) {
            return refIdentities.computeIfAbsent(heap, k -> new Object());
        }

        /** The runtime value → closed comparison operand view (B-D1). */
        ComparisonOperandView viewOf(Value value) {
            return switch (value) {
                case Value.NullValue ignored -> ComparisonOperandView.Null.INSTANCE;
                case Value.MissingValue ignored -> ComparisonOperandView.Missing.INSTANCE;
                case Value.BoolValue bool -> new ComparisonOperandView.Boolean(bool.value());
                case Value.IntValue intValue ->
                    new ComparisonOperandView.Int((int) intValue.value());
                case Value.NumValue num -> new ComparisonOperandView.Number(num.value());
                case Value.StrValue str -> new ComparisonOperandView.String(
                    (UnicodeScalars.Valid) UnicodeScalars.validate(str.value()));
                case Value.TableValue table -> new ComparisonOperandView.Ref(refIdentity(table));
                case Value.ArrayValue array -> new ComparisonOperandView.Ref(refIdentity(array));
                case Value.FuncValue func -> new ComparisonOperandView.Ref(refIdentity(func));
                case Value.IntrinsicValue intrinsic ->
                    new ComparisonOperandView.Ref(refIdentity(intrinsic));
                default -> throw new IllegalStateException(
                    "value " + value.getClass().getSimpleName()
                        + " is not a comparison operand view");
            };
        }

        private String executeConcat(SemanticOp op) {
            KindPayload.StringConcatPayload payload =
                (KindPayload.StringConcatPayload) op.payload();
            StringBuilder out = new StringBuilder();
            for (ValueId fragment : payload.fragments()) {
                out.append(((Value.StrValue) values.get(fragment)).value());
            }
            return publish(op, new Value.StrValue(out.toString()));
        }

        private String executeArrayNew(SemanticOp op) {
            KindPayload.ArrayNewPayload payload = (KindPayload.ArrayNewPayload) op.payload();
            List<Value> elements = new ArrayList<>();
            for (int i = 0; i < payload.values().size(); i++) {
                ValueId valueId = payload.values().get(i);
                OpId boundaryId = payload.elementBoundaryOpIds().get(i);
                SemanticOp boundary = opsById.get(boundaryId);
                Value checked = runBoundaryChild(boundary, values.get(valueId),
                    BoundaryContext.element(i + 1));
                elements.add(checked);
            }
            return publish(op, new Value.ArrayValue(elements, payload.elementDescriptor()));
        }

        private String executeTableNew(SemanticOp op) {
            KindPayload.TableNewPayload payload = (KindPayload.TableNewPayload) op.payload();
            LinkedHashMap<String, Value> entries = new LinkedHashMap<>();
            for (KindPayload.TableEntry entry : payload.entries()) {
                entries.put(entry.key(), values.get(entry.value()));
            }
            return publish(op, new Value.TableValue(entries));
        }

        private String executeArrayLength(SemanticOp op) {
            KindPayload.ArrayLengthPayload payload =
                (KindPayload.ArrayLengthPayload) op.payload();
            Value.ArrayValue array = (Value.ArrayValue) values.get(payload.arrayValue());
            return publish(op, new Value.IntValue(array.elements().size()));
        }

        /**
         * MEMBER_READ: the missing-aware read, then its
         * CONTEXTUAL_TABLE_READ boundary child (missing→nullable-null /
         * E8001 via the contextual decision).
         */
        private String executeMemberRead(SemanticOp op) {
            KindPayload.MemberReadPayload payload = (KindPayload.MemberReadPayload) op.payload();
            Value.TableValue table = (Value.TableValue) values.get(payload.table());
            Value read = table.entries().get(payload.key());
            if (read == null) {
                read = Value.MissingValue.INSTANCE;
            }
            SemanticOp boundary = singleChild(op);
            if (boundary == null) {
                return publish(op, read);
            }
            RuntimeDescriptor descriptor =
                ((KindPayload.BoundaryPayload) boundary.payload()).descriptor();
            Value checked = runBoundaryChild(boundary, read, BoundaryContext.none());
            return contextualReadDecision(op, checked, descriptor);
        }

        /** The single child op parented to {@code op}, or null. */
        private SemanticOp singleChild(SemanticOp op) {
            List<SemanticOp> children = childrenByParent.get(op.opId());
            if (children == null || children.isEmpty()) {
                return null;
            }
            if (children.size() != 1) {
                throw new IllegalStateException("op " + op.opId() + " has "
                    + children.size() + " children; a single child was expected");
            }
            return children.get(0);
        }

        /**
         * A commit op (MEMBER_WRITE/DELETE, INDEX_WRITE/DELETE,
         * FIELD_WRITE/DELETE): exactly one storage mutation over resolved
         * references, never re-evaluating a source expression.
         */
        private String executeCommit(SemanticOp op) {
            switch (op.payload()) {
                case KindPayload.MemberWritePayload payload -> {
                    Value.TableValue table = (Value.TableValue) values.get(payload.table());
                    table.entries().put(payload.key(), values.get(payload.value()));
                }
                case KindPayload.MemberDeletePayload payload -> {
                    Value.TableValue table = (Value.TableValue) values.get(payload.table());
                    table.entries().remove(payload.key());
                }
                case KindPayload.IndexWritePayload payload -> {
                    Value container = values.get(payload.container());
                    NormalizedSlot slot = ((Value.SlotValue) values.get(payload.slot())).slot();
                    commitIndexWrite(container, slot, values.get(payload.value()));
                }
                case KindPayload.IndexDeletePayload payload -> {
                    Value container = values.get(payload.container());
                    NormalizedSlot slot = ((Value.SlotValue) values.get(payload.slot())).slot();
                    commitIndexDelete(container, slot);
                }
                default -> throw new IllegalStateException(
                    "commit op payload " + op.payload().getClass().getSimpleName());
            }
            return null;
        }

        /** The array/table commit mutation (slot mechanics of the carrier). */
        private void commitIndexWrite(Value container, NormalizedSlot slot, Value value) {
            switch (slot) {
                case NormalizedSlot.ArraySlot array -> {
                    Value.ArrayValue target = (Value.ArrayValue) container;
                    if (array.append()) {
                        target.elements().add(value);
                    } else {
                        target.elements().set(array.index(), value);
                    }
                }
                case NormalizedSlot.TableSlot table ->
                    ((Value.TableValue) container).entries().put(table.key(), value);
            }
        }

        private void commitIndexDelete(Value container, NormalizedSlot slot) {
            switch (slot) {
                case NormalizedSlot.ArraySlot array -> {
                    Value.ArrayValue target = (Value.ArrayValue) container;
                    if (array.index() < target.elements().size()) {
                        target.elements().set(array.index(), Value.MissingValue.INSTANCE);
                    }
                    // index == length: the nil write at the append slot is a
                    // no-op on a dense array (A-D4).
                }
                case NormalizedSlot.TableSlot table ->
                    ((Value.TableValue) container).entries().remove(table.key());
            }
        }

        private String executeNormalize(SemanticOp op) {
            KindPayload.IndexNormalizePayload payload =
                (KindPayload.IndexNormalizePayload) op.payload();
            Value rawKey = values.get(payload.rawKey());
            Value currentLength = values.get(payload.currentLength());
            NormalizedSlot slot = switch (payload.mode()) {
                case ARRAY_READ, ARRAY_WRITE -> NormalizedSlot.arraySlot(payload.mode(),
                    (int) ((Value.IntValue) rawKey).value(),
                    (int) ((Value.IntValue) currentLength).value());
                case TABLE_READ, TABLE_WRITE -> NormalizedSlot.tableSlot(payload.mode(),
                    ((Value.StrValue) rawKey).value());
            };
            return publish(op, new Value.SlotValue(slot));
        }

        private String executeIndexRead(SemanticOp op) {
            KindPayload.IndexReadPayload payload = (KindPayload.IndexReadPayload) op.payload();
            Value container = values.get(payload.container());
            NormalizedSlot slot = ((Value.SlotValue) values.get(payload.slot())).slot();
            SemanticOp boundary = opsById.get(payload.elementBoundaryOpId());
            RuntimeDescriptor descriptor = ((KindPayload.BoundaryPayload) boundary.payload())
                .descriptor();
            switch (slot) {
                case NormalizedSlot.ArraySlot array -> {
                    Value.ArrayValue target = (Value.ArrayValue) container;
                    int index = array.index();
                    Value read;
                    if (index < 0) {
                        read = Value.MissingValue.INSTANCE; // E8002 before any read
                    } else if (index < target.elements().size()) {
                        read = target.elements().get(index);
                    } else {
                        read = Value.MissingValue.INSTANCE;
                    }
                    Value checked = runBoundaryChild(boundary, read,
                        BoundaryContext.arrayIndex(index));
                    if (checked instanceof Value.MissingValue
                            && descriptor instanceof RuntimeDescriptor.Nullable) {
                        checked = Value.NullValue.INSTANCE;
                    }
                    // A missing read publishes missing: the consuming
                    // contextual boundary/operand decides E8001/null
                    // (the comparison operands' missing≡null rule, the
                    // declaration boundary's E8001 projection).
                    return publish(op, checked);
                }
                case NormalizedSlot.TableSlot table -> {
                    Value.TableValue target = (Value.TableValue) container;
                    Value read = target.entries().get(table.key());
                    if (read == null) {
                        read = Value.MissingValue.INSTANCE;
                    }
                    Value checked = runBoundaryChild(boundary, read, BoundaryContext.none());
                    return contextualReadDecision(op, checked, descriptor);
                }
            }
        }

        /**
         * The read's contextual decision (the ARRAY_ELEMENT_READ/
         * CONTEXTUAL_TABLE_READ cell passes the missing view through; the
         * contextual boundary decides E8001/null): a missing value against
         * a nullable descriptor maps to language null; against a
         * non-nullable descriptor it projects the pinned E8001
         * {@code expected {expected}, got missing} at the read origin.
         */
        private String contextualReadDecision(SemanticOp op, Value checked,
                                              RuntimeDescriptor descriptor) {
            if (checked instanceof Value.MissingValue
                    && !(descriptor instanceof RuntimeDescriptor.Nullable)) {
                BoundaryFailure failure = BoundaryFailure.fromRow(
                    FailureContractRegistry.row(FailurePolicyId.TYPE_DESCRIPTOR), 0,
                    descriptor.canonicalSpecText(),
                    ActualKind.canonicalToken(ActualKind.MISSING, null),
                    new LinkedHashMap<>(), null);
                throw DealFailure.of(failure, op.origin(), List.copyOf(frames));
            }
            if (checked instanceof Value.MissingValue) {
                checked = Value.NullValue.INSTANCE;
            }
            return publish(op, checked);
        }

        /**
         * Executes one BOUNDARY child with its own START/terminal events.
         */
        Value runBoundaryChild(SemanticOp boundary, Value input, BoundaryContext context) {
            List<String> inputs = List.of(atomOf(input));
            emitStart(boundary, inputs);
            KindPayload.BoundaryPayload payload =
                (KindPayload.BoundaryPayload) boundary.payload();
            BoundaryValueView view = viewOfValue(input);
            BoundaryOutcome outcome;
            try {
                outcome = BoundaryExecutor.execute(boundary.failurePolicy(),
                    payload.descriptor(), view, context, payload.realization());
            } catch (BoundaryExecutor.Defect defect) {
                throw new IllegalStateException("boundary " + boundary.opId()
                    + " has no executor projection: " + defect.getMessage());
            }
            return switch (outcome) {
                case BoundaryOutcome.Pass pass -> {
                    Value result = valueOfView(pass.value(), input);
                    emitSuccess(boundary, atomOf(result));
                    yield result;
                }
                case BoundaryOutcome.Fail fail -> {
                    DealFailure failure = DealFailure.of(fail.failure(), boundary.origin(),
                        List.copyOf(frames));
                    emitFailure(boundary, failure);
                    throw failure;
                }
            };
        }

        /** The runtime value → closed boundary view. */
        private BoundaryValueView viewOfValue(Value value) {
            return switch (value) {
                case Value.NullValue ignored -> BoundaryValueView.nullView();
                case Value.MissingValue ignored -> BoundaryValueView.of(ActualKind.MISSING);
                case Value.BoolValue bool -> BoundaryValueView.of(ActualKind.BOOLEAN);
                case Value.IntValue intValue -> BoundaryValueView.ofInt(intValue.value());
                case Value.NumValue num -> BoundaryValueView.ofNumber(num.value());
                case Value.StrValue str -> BoundaryValueView.of(ActualKind.STRING);
                case Value.TableValue table -> BoundaryValueView.of(ActualKind.TABLE);
                case Value.ErrorValue error -> BoundaryValueView.ofClass("@builtin/Error");
                case Value.FuncValue func -> BoundaryValueView.ofFunction(func.signature());
                case Value.IntrinsicValue intrinsic ->
                    BoundaryValueView.ofFunction(new RuntimeDescriptor.Func(List.of(),
                        RuntimeDescriptor.Number.INSTANCE, false));
                case Value.ArrayValue array -> {
                    List<BoundaryValueView> elements = new ArrayList<>();
                    for (Value element : array.elements()) {
                        elements.add(viewOfValue(element));
                    }
                    yield BoundaryValueView.ofArray(elements);
                }
                case Value.SlotValue slot -> BoundaryValueView.of(ActualKind.INT);
            };
        }

        /** The passed boundary view → runtime value (missing→null was pre-mapped). */
        private Value valueOfView(BoundaryValueView view, Value original) {
            if (view.kind() == ActualKind.MISSING) {
                return Value.MissingValue.INSTANCE;
            }
            if (view.kind() == ActualKind.NULL) {
                return Value.NullValue.INSTANCE;
            }
            return original;
        }

        /** A free BOUNDARY op: the check over its completed operand (no events here). */
        private String executeBoundary(SemanticOp op) {
            KindPayload.BoundaryPayload payload = (KindPayload.BoundaryPayload) op.payload();
            Value input = values.get(payload.input());
            BoundaryOutcome outcome;
            try {
                outcome = BoundaryExecutor.execute(op.failurePolicy(), payload.descriptor(),
                    viewOfValue(input), BoundaryContext.none(), payload.realization());
            } catch (BoundaryExecutor.Defect defect) {
                throw new IllegalStateException("boundary " + op.opId()
                    + " has no executor projection: " + defect.getMessage());
            }
            return switch (outcome) {
                case BoundaryOutcome.Pass pass -> publish(op, valueOfView(pass.value(), input));
                case BoundaryOutcome.Fail fail ->
                    throw DealFailure.of(fail.failure(), op.origin(), List.copyOf(frames));
            };
        }

        private String executeBindingAlloc(SemanticOp op) {
            KindPayload.BindingAllocPayload payload =
                (KindPayload.BindingAllocPayload) op.payload();
            cellOf(payload.binding(), payload.generation(), true);
            return null;
        }

        private String executeBindingInit(SemanticOp op) {
            KindPayload.BindingInitPayload payload =
                (KindPayload.BindingInitPayload) op.payload();
            Cell cell = cellOf(payload.binding(), payload.generation(), true);
            Value value = values.get(payload.value());
            if (value == null) {
                // An intrinsic function identity (int()/number() as
                // first-class values — the producer-less identity slot).
                value = new Value.IntrinsicValue("intrinsic");
                values.put(payload.value(), value);
            }
            cell.value = value;
            cell.initialized = true;
            return null;
        }

        private String executeBindingLoad(SemanticOp op) {
            KindPayload.BindingLoadPayload payload =
                (KindPayload.BindingLoadPayload) op.payload();
            Cell cell = cellOf(payload.binding(), payload.generation(), false);
            if (cell == null || !cell.initialized) {
                throw new IllegalStateException("binding load of uninitialized cell "
                    + payload.binding() + "#" + payload.generation());
            }
            return publish(op, cell.value);
        }

        private String executeBindingStore(SemanticOp op) {
            KindPayload.BindingStorePayload payload =
                (KindPayload.BindingStorePayload) op.payload();
            Cell cell = cellOf(payload.binding(), payload.generation(), true);
            cell.value = values.get(payload.value());
            cell.initialized = true;
            return null;
        }

        private String executeClosureNew(SemanticOp op) {
            KindPayload.ClosureNewPayload payload =
                (KindPayload.ClosureNewPayload) op.payload();
            Map<BindingId, Cell> captures = new HashMap<>();
            for (BindingId binding : payload.captures()) {
                Cell current = currentCellOf(binding);
                if (current == null) {
                    throw new IllegalStateException("closure capture of unknown binding "
                        + binding);
                }
                captures.put(binding, current);
            }
            return publish(op, new Value.FuncValue(payload.function(), payload.signature(),
                captures));
        }

        /** ASSIGN: children in payload order; the committed value result is pre-published. */
        private String executeAssign(SemanticOp op) {
            KindPayload.AssignPayload payload = (KindPayload.AssignPayload) op.payload();
            for (OpId childId : payload.childOps()) {
                SemanticOp child = opsById.get(childId);
                if (child.kind() == SemanticOpKind.BOUNDARY) {
                    KindPayload.BoundaryPayload boundaryPayload =
                        (KindPayload.BoundaryPayload) child.payload();
                    BoundaryContext context = chainBoundaryContext(op, boundaryPayload.kind());
                    Value input = values.get(boundaryPayload.input());
                    runBoundaryChild(child, input, context);
                } else {
                    execute(child);
                }
            }
            Value committed = null;
            if (op.result() instanceof ValueId valueId) {
                committed = values.get(valueId);
            }
            return committed == null ? null : atomOf(committed);
        }

        /**
         * The chain executor's boundary-context supply (A-D4): the array
         * assignment/delete boundaries receive {@code {index, length}}
         * from the chain's already-completed normalize slot and length
         * children (both precede the boundary in payload order); every
         * other chain boundary runs with no context.
         */
        private BoundaryContext chainBoundaryContext(SemanticOp chain,
                                                     deal.semantic.ir.BoundaryKind kind) {
            if (kind != deal.semantic.ir.BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT
                    && kind != deal.semantic.ir.BoundaryKind.ARRAY_ELEMENT_DELETE) {
                return BoundaryContext.none();
            }
            NormalizedSlot.ArraySlot array = null;
            int length = -1;
            List<OpId> children = switch (chain.payload()) {
                case KindPayload.AssignPayload assign -> assign.childOps();
                case KindPayload.DeletePayload delete -> delete.childOps();
                default -> List.of();
            };
            for (OpId childId : children) {
                SemanticOp child = opsById.get(childId);
                if (child.kind() == SemanticOpKind.INDEX_NORMALIZE
                        && child.result() instanceof ValueId normalizeResult) {
                    array = (NormalizedSlot.ArraySlot)
                        ((Value.SlotValue) values.get(normalizeResult)).slot();
                } else if (child.kind() == SemanticOpKind.ARRAY_LENGTH
                        && child.result() instanceof ValueId lengthResult) {
                    length = (int) ((Value.IntValue) values.get(lengthResult)).value();
                }
            }
            if (array == null || length < 0) {
                throw new IllegalStateException("an array chain boundary lacks its "
                    + "normalize slot/length facts (a malformed chain — the production "
                    + "protocol rejects this)");
            }
            return BoundaryContext.writeBounds(array.index(), length);
        }

        /**
         * The chain executor's boundary-context supply (A-D4): the array
         * assignment/delete boundaries receive {@code {index, length}}
         * from the chain's normalize slot and length values; every other
         * chain boundary runs with no context.
         */
        private BoundaryContext chainBoundaryContext(deal.semantic.ir.BoundaryKind kind,
                                                     Value.SlotValue slot, Value length) {
            if (kind == deal.semantic.ir.BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT
                    || kind == deal.semantic.ir.BoundaryKind.ARRAY_ELEMENT_DELETE) {
                if (slot == null || !(slot.slot() instanceof NormalizedSlot.ArraySlot array)
                        || length == null) {
                    throw new IllegalStateException("an array chain boundary lacks its "
                        + "normalize slot/length facts (a malformed chain — the "
                        + "production protocol rejects this)");
                }
                return BoundaryContext.writeBounds(array.index(),
                    (int) ((Value.IntValue) length).value());
            }
            return BoundaryContext.none();
        }

        /** DELETE: children in payload order; publishes nothing. */
        private String executeDelete(SemanticOp op) {
            KindPayload.DeletePayload payload = (KindPayload.DeletePayload) op.payload();
            for (OpId childId : payload.childOps()) {
                SemanticOp child = opsById.get(childId);
                if (child.kind() == SemanticOpKind.BOUNDARY) {
                    KindPayload.BoundaryPayload boundaryPayload =
                        (KindPayload.BoundaryPayload) child.payload();
                    runBoundaryChild(child, values.get(boundaryPayload.input()),
                        chainBoundaryContext(op, boundaryPayload.kind()));
                } else {
                    execute(child);
                }
            }
            return null;
        }

        /**
         * CALL(DIRECT): parameter boundaries in one-based order, then the
         * recorded body block with the leading parameter ALLOCs bound.
         */
        private String executeCall(SemanticOp op) {
            KindPayload.CallPayload payload = (KindPayload.CallPayload) op.payload();
            FunctionExecutionBinding.LoweredBody body =
                (FunctionExecutionBinding.LoweredBody)
                    ((KindPayload.CallCallee.Static) payload.callee()).binding();
            // Parameter boundaries (descriptor-kind rule cells).
            List<Value> checkedArgs = new ArrayList<>();
            for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                OpId boundaryId = payload.parameterBoundaryOpIds().get(i);
                SemanticOp boundary = opsById.get(boundaryId);
                Value arg = values.get(
                    ((KindPayload.BoundaryPayload) boundary.payload()).input());
                checkedArgs.add(runBoundaryChild(boundary, arg,
                    BoundaryContext.parameter(i + 1)));
            }
            LoweredFunction function = unit.functions().get(body.functionId());
            if (function == null) {
                throw new IllegalStateException("CALL resolves a missing lowered function "
                    + body.functionId());
            }
            // Bind the body block's leading parameter ALLOC cells.
            List<OpId> bodyOps = table.blockOps().get(body.blockId());
            int paramCount = payload.signature().paramTypes().size();
            for (int i = 0; i < paramCount && i < bodyOps.size(); i++) {
                SemanticOp alloc = opsById.get(bodyOps.get(i));
                if (alloc.kind() != SemanticOpKind.BINDING_ALLOC) {
                    throw new IllegalStateException("the callee body's leading op "
                        + alloc.opId() + " is not a BINDING_ALLOC (parameter cell)");
                }
                KindPayload.BindingAllocPayload allocPayload =
                    (KindPayload.BindingAllocPayload) alloc.payload();
                Cell cell = cellOf(allocPayload.binding(), allocPayload.generation(), true);
                cell.value = checkedArgs.get(i);
                cell.initialized = true;
            }
            frames.add(0, body.functionId());
            Value returned;
            try {
                returned = runBodyBlock(body.blockId(), paramCount);
            } catch (DealFailure failure) {
                throw failure; // frames already captured innermost-first at the raise
            } finally {
                frames.remove(0);
            }
            return publish(op, returned);
        }

        /**
         * Runs a callee body block, skipping the leading parameter ALLOCs
         * (already bound); a RETURN transfers the checked value out.
         */
        private Value runBodyBlock(BlockId block, int skipLeading) {
            List<OpId> ops = table.blockOps().get(block);
            int i = 0;
            for (OpId opId : ops) {
                if (i++ < skipLeading) {
                    continue;
                }
                if (ownedChildren.contains(opId)) {
                    continue;
                }
                SemanticOp op = opsById.get(opId);
                try {
                    execute(op);
                } catch (ReturnSignal signal) {
                    return signal.value;
                }
            }
            return Value.NullValue.INSTANCE;
        }

        private String executeIntrinsic(SemanticOp op) {
            KindPayload.IntrinsicCallPayload payload =
                (KindPayload.IntrinsicCallPayload) op.payload();
            Value input = values.get(payload.input());
            Value result = switch (payload.kind()) {
                case INT_CONVERT -> convertInt(op, input);
                case NUMBER_CONVERT -> convertNumber(op, input);
            };
            return publish(op, result);
        }

        /** INT_CONVERSION order: null → NaN → infinity → fractional → E8004. */
        private Value convertInt(SemanticOp op, Value input) {
            if (input instanceof Value.NullValue) {
                throw conversionFailure(op, FailurePolicyId.INT_CONVERSION, "int",
                    "null", null);
            }
            if (input instanceof Value.NumValue num) {
                double value = num.value();
                if (Double.isNaN(value)) {
                    throw conversionFailure(op, FailurePolicyId.INT_CONVERSION, "int",
                        "NaN", null);
                }
                if (Double.isInfinite(value)) {
                    throw conversionFailure(op, FailurePolicyId.INT_CONVERSION, "int",
                        "infinity", null);
                }
                if (value != Math.rint(value)) {
                    throw conversionFailure(op, FailurePolicyId.INT_CONVERSION, "int",
                        "non-integer number", null);
                }
                if (value < -2147483648d || value > 2147483647d) {
                    throw conversionFailure(op, FailurePolicyId.INT32_RESULT, "int",
                        null, null);
                }
                return new Value.IntValue((long) value);
            }
            if (input instanceof Value.IntValue intValue) {
                return intValue;
            }
            throw conversionFailure(op, FailurePolicyId.INT_CONVERSION, "int",
                canonicalKind(input), null);
        }

        private Value convertNumber(SemanticOp op, Value input) {
            if (input instanceof Value.NullValue) {
                throw conversionFailure(op, FailurePolicyId.NUMBER_CONVERSION, "number",
                    "null", null);
            }
            if (input instanceof Value.IntValue intValue) {
                return new Value.NumValue((double) intValue.value());
            }
            if (input instanceof Value.NumValue num) {
                return num;
            }
            throw conversionFailure(op, FailurePolicyId.NUMBER_CONVERSION, "number",
                canonicalKind(input), null);
        }

        /** The canonical actual-kind token of a runtime value. */
        private String canonicalKind(Value input) {
            return switch (input) {
                case Value.NullValue ignored -> "null";
                case Value.BoolValue ignored -> "boolean";
                case Value.IntValue ignored -> "int";
                case Value.NumValue ignored -> "number";
                case Value.StrValue ignored -> "string";
                case Value.TableValue ignored -> "table";
                case Value.ArrayValue ignored -> "array";
                case Value.FuncValue ignored -> "function";
                case Value.IntrinsicValue ignored -> "function";
                case Value.ErrorValue ignored -> "class:@builtin/Error";
                case Value.MissingValue ignored -> "missing";
                case Value.SlotValue ignored -> throw new IllegalStateException(
                    "a slot value is never a conversion input");
            };
        }

        private DealFailure conversionFailure(SemanticOp op, FailurePolicyId policy,
                                              String expected, String actual,
                                              String messageOverride) {
            if (messageOverride == null) {
                BoundaryFailure failure = BoundaryFailure.fromRow(
                    FailureContractRegistry.row(policy), 0, expected, actual,
                    new LinkedHashMap<>(), null);
                return DealFailure.of(failure, op.origin(), List.copyOf(frames));
            }
            return new DealFailure("E8001", messageOverride, op.origin(), expected, actual,
                null, List.copyOf(frames));
        }

        private String executeStdlib(SemanticOp op) {
            KindPayload.StdlibCallPayload payload =
                (KindPayload.StdlibCallPayload) op.payload();
            if (payload.function() == StdlibFunctionId.CONSOLE_LOG
                    || payload.function() == StdlibFunctionId.CONSOLE_ERROR) {
                // STDLIB_PARAMETER boundaries in order (descriptor-kind rule).
                List<Value> checked = new ArrayList<>();
                for (ValueId arg : payload.args()) {
                    Value value = values.get(arg);
                    SemanticOp boundary = parameterBoundaryOf(op, value, checked.size());
                    if (boundary != null) {
                        value = runBoundaryChild(boundary, value,
                            BoundaryContext.parameter(checked.size() + 1));
                    }
                    checked.add(value);
                }
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < checked.size(); i++) {
                    if (checked.get(i) instanceof Value.StrValue str) {
                        if (i > 0) {
                            text.append(' ');
                        }
                        text.append(str.value());
                    }
                }
                effects.add(new SemanticRuntimeModel.EffectEvent(
                    SemanticRuntimeModel.EffectEvent.Kind.CONSOLE_WRITE, text.toString()));
                Value result = Value.NullValue.INSTANCE;
                // STDLIB_RETURN boundary (declared return descriptor).
                SemanticOp returnBoundary = returnBoundaryOf(op);
                if (returnBoundary != null) {
                    result = runBoundaryChild(returnBoundary, result, BoundaryContext.none());
                }
                return publish(op, result);
            }
            throw new IllegalStateException("stdlib " + payload.function()
                + " has no decomposition-tail oracle execution (the tail's stdlib slice "
                + "executes CONSOLE_LOG/CONSOLE_ERROR; the full table is the stdlib "
                + "epic's)");
        }

        /** The STDLIB_PARAMETER child for the i-th argument, or null. */
        private SemanticOp parameterBoundaryOf(SemanticOp op, Value arg, int index) {
            List<SemanticOp> children = childrenByParent.get(op.opId());
            if (children == null) {
                return null;
            }
            int seen = 0;
            for (SemanticOp child : children) {
                if (child.kind() == SemanticOpKind.BOUNDARY) {
                    KindPayload.BoundaryPayload payload =
                        (KindPayload.BoundaryPayload) child.payload();
                    if (payload.kind() == deal.semantic.ir.BoundaryKind.STDLIB_PARAMETER) {
                        if (seen == index) {
                            return child;
                        }
                        seen++;
                    }
                }
            }
            return null;
        }

        /** The STDLIB_RETURN child, or null. */
        private SemanticOp returnBoundaryOf(SemanticOp op) {
            List<SemanticOp> children = childrenByParent.get(op.opId());
            if (children == null) {
                return null;
            }
            for (SemanticOp child : children) {
                if (child.kind() == SemanticOpKind.BOUNDARY
                        && ((KindPayload.BoundaryPayload) child.payload()).kind()
                            == deal.semantic.ir.BoundaryKind.STDLIB_RETURN) {
                    return child;
                }
            }
            return null;
        }

        private String executeBranch(SemanticOp op) {
            KindPayload.BranchPayload payload = (KindPayload.BranchPayload) op.payload();
            Value condition = values.get(payload.condition());
            boolean truthy = ((Value.BoolValue) condition).value();
            switch (payload.selector()) {
                case IF -> {
                    if (truthy) {
                        runBlock(payload.selectedBlock());
                    } else if (payload.alternateBlock() != null) {
                        runBlock(payload.alternateBlock());
                    }
                    return null;
                }
                case LOGICAL_AND -> {
                    // The right operand's producing ops live in
                    // selectedBlock and execute only when the left value
                    // does not decide the result; the producer wires the
                    // op's result slot to the right operand's value
                    // identity, so the block run publishes it, and a
                    // skipped block publishes the left value.
                    if (!truthy) {
                        return publish(op, condition);
                    }
                    runBlock(payload.selectedBlock());
                    return publish(op, values.get((ValueId) op.result()));
                }
                case LOGICAL_OR -> {
                    if (truthy) {
                        return publish(op, condition);
                    }
                    runBlock(payload.selectedBlock());
                    return publish(op, values.get((ValueId) op.result()));
                }
                default -> throw new IllegalStateException("BRANCH selector "
                    + payload.selector());
            }
        }

        private String executeLoop(SemanticOp op) {
            KindPayload.LoopPayload payload = (KindPayload.LoopPayload) op.payload();
            switch (payload.selector()) {
                case WHILE -> {
                    while (true) {
                        runBlock(payload.initBlock());
                        if (!((Value.BoolValue) values.get(payload.condition())).value()) {
                            break;
                        }
                        try {
                            runBlock(payload.bodyBlock());
                        } catch (LoopSignal signal) {
                            if (!signal.target.equals(op.opId())) {
                                throw signal;
                            }
                            if (!signal.isContinue) {
                                break; // BREAK targeting this loop exits it
                            }
                            // WHILE continue → condition block re-run (top).
                        }
                    }
                    return null;
                }
                case FOR -> {
                    runBlock(payload.initBlock());
                    while (true) {
                        if (!((Value.BoolValue) values.get(payload.condition())).value()) {
                            break;
                        }
                        boolean broken = false;
                        try {
                            runBlock(payload.bodyBlock());
                        } catch (LoopSignal signal) {
                            if (!signal.target.equals(op.opId())) {
                                throw signal;
                            }
                            if (!signal.isContinue) {
                                break;
                            }
                            // FOR continue → update, then re-test.
                        }
                        if (broken) {
                            break;
                        }
                        if (payload.updateBlock() != null) {
                            try {
                                runBlock(payload.updateBlock());
                            } catch (LoopSignal signal) {
                                if (!signal.target.equals(op.opId())) {
                                    throw signal;
                                }
                                if (!signal.isContinue) {
                                    break;
                                }
                            }
                        }
                    }
                    return null;
                }
                default -> throw new IllegalStateException("LOOP selector " + payload.selector());
            }
        }

        private String executeForEach(SemanticOp op) {
            KindPayload.ForEachPayload payload = (KindPayload.ForEachPayload) op.payload();
            Value iterable = values.get(payload.iterable());
            switch (payload.mode()) {
                case ARRAY_VALUES -> {
                    Value.ArrayValue array = (Value.ArrayValue) iterable;
                    int initialLength = array.elements().size();
                    RuntimeDescriptor elementDescriptor = elementDescriptorOf(op);
                    for (int i = 0; i < initialLength; i++) {
                        Value element = i < array.elements().size()
                            ? array.elements().get(i) : Value.MissingValue.INSTANCE;
                        // The op's own terminal check: TYPE_DESCRIPTOR over the
                        // element descriptor (a missing element fails E8001
                        // at the FOR_EACH origin before the body runs).
                        Value checked = checkForEachElement(op, element, elementDescriptor);
                        // Fresh binding per iteration.
                        Cell cell = cellOf(payload.binding(), payload.generation(), true);
                        cell.value = checked;
                        cell.initialized = true;
                        try {
                            runBlock(payload.body());
                        } catch (LoopSignal signal) {
                            if (signal.target.equals(op.opId())) {
                                if (!signal.isContinue) {
                                    break;
                                }
                                // continue → next slot index.
                            } else {
                                throw signal;
                            }
                        }
                    }
                    return null;
                }
                case STRING_SCALARS -> {
                    Value.StrValue string = (Value.StrValue) iterable;
                    int[] scalars = string.value().codePoints().toArray();
                    for (int scalar : scalars) {
                        String single = new String(Character.toChars(scalar));
                        Cell cell = cellOf(payload.binding(), payload.generation(), true);
                        cell.value = new Value.StrValue(single);
                        cell.initialized = true;
                        try {
                            runBlock(payload.body());
                        } catch (LoopSignal signal) {
                            if (signal.target.equals(op.opId())) {
                                if (!signal.isContinue) {
                                    break;
                                }
                            } else {
                                throw signal;
                            }
                        }
                    }
                    return null;
                }
            }
            return null;
        }

        /** The FOR_EACH element descriptor: the iterable producer's array element. */
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
            throw new IllegalStateException("FOR_EACH(ARRAY_VALUES) iterable producer is "
                + "absent from the validated unit");
        }

        /** The FOR_EACH op's own TYPE_DESCRIPTOR terminal check (C-D5). */
        private Value checkForEachElement(SemanticOp op, Value element,
                                          RuntimeDescriptor descriptor) {
            BoundaryValueView view = viewOfValue(element);
            BoundaryOutcome outcome = BoundaryExecutor.check(FailurePolicyId.TYPE_DESCRIPTOR,
                descriptor, view, BoundaryContext.none());
            return switch (outcome) {
                case BoundaryOutcome.Pass pass -> valueOfView(pass.value(), element);
                case BoundaryOutcome.Fail fail ->
                    throw DealFailure.of(fail.failure(), op.origin(), List.copyOf(frames));
            };
        }

        private String executeTryCatch(SemanticOp op) {
            KindPayload.TryCatchPayload payload = (KindPayload.TryCatchPayload) op.payload();
            DealFailure caught;
            try {
                runBlock(payload.tryBlock());
                return null; // success: catch skipped
            } catch (DealFailure failure) {
                caught = failure;
            }
            // Reify the DEAL failure as an Error value bound to the catch binding.
            Value.ErrorValue error = new Value.ErrorValue(caught.code, caught.message);
            Cell cell = cellOf(payload.catchBinding(), 0, true);
            cell.value = error;
            cell.initialized = true;
            try {
                runBlock(payload.catchBlock());
            } catch (DealFailure replacement) {
                throw new DealFailure(replacement.code, replacement.message,
                    replacement.origin, replacement.expected, replacement.actual,
                    caught, replacement.frames);
            }
            return null;
        }

        private String executeThrow(SemanticOp op) {
            KindPayload.ThrowPayload payload = (KindPayload.ThrowPayload) op.payload();
            Value.ErrorValue error = (Value.ErrorValue) values.get(payload.errorValue());
            throw new DealFailure(error.code(), error.message(), op.origin(), null, null,
                null, List.copyOf(frames));
        }

        private String executeReturn(SemanticOp op) {
            KindPayload.ReturnPayload payload = (KindPayload.ReturnPayload) op.payload();
            Value value = payload.value() == null
                ? Value.NullValue.INSTANCE : values.get(payload.value());
            SemanticOp boundary = opsById.get(payload.returnBoundaryOpId());
            Value checked = runBoundaryChild(boundary, value, BoundaryContext.none());
            throw new ReturnSignal(checked);
        }

        private String executeBreak(SemanticOp op) {
            KindPayload.BreakPayload payload = (KindPayload.BreakPayload) op.payload();
            throw new LoopSignal(payload.loopId(), false);
        }

        private String executeContinue(SemanticOp op) {
            KindPayload.ContinuePayload payload = (KindPayload.ContinuePayload) op.payload();
            throw new LoopSignal(payload.loopId(), true);
        }

        /**
         * MODULE_IMPORT — the load-once initialization record. The tail's
         * stdlib slice imports {@code std/console} only: the stdlib
         * console algorithm executes inside {@code STDLIB_CALL}
         * ({@code CONSOLE_LOG}/{@code CONSOLE_ERROR}), so the import
         * record initializes no run state beyond the op terminal.
         */
        /**
         * EXPORT_READ — the checked export read of the std/console
         * module's log/error function values: the published export value
         * is an allocated function identity (the static
         * {@code STDLIB_CALL} consumes the closed function id).
         */
        private String executeExportRead(SemanticOp op) {
            KindPayload.ExportReadPayload payload =
                (KindPayload.ExportReadPayload) op.payload();
            Value value = new Value.IntrinsicValue("export:" + payload.name());
            return publish(op, value);
        }

        private String executeModuleImport(SemanticOp op) {
            KindPayload.ModuleImportPayload payload =
                (KindPayload.ModuleImportPayload) op.payload();
            return null;
        }

        private String executeDiscard(SemanticOp op) {
            KindPayload.DiscardPayload payload = (KindPayload.DiscardPayload) op.payload();
            Value discarded = values.get(payload.value());
            // The entry delegation discards the main-call result: record it
            // as the run result atom.
            if (isEntryDelegationDiscard(op)) {
                entryResultAtom = atomOf(discarded);
            }
            return null;
        }

        /** True iff the DISCARD is the module-init block's trailing entry delegation. */
        private boolean isEntryDelegationDiscard(SemanticOp op) {
            List<OpId> initOps = table.blockOps().get(unit.moduleInit().initBlock());
            if (initOps == null || initOps.isEmpty()
                    || !initOps.get(initOps.size() - 1).equals(op.opId())) {
                return false;
            }
            return true;
        }
    }
}
