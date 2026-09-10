package deal.semantic;

import deal.semantic.ir.ActualKind;
import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.AsyncLinkKind;
import deal.semantic.ir.AsyncStartSource;
import deal.semantic.ir.AsyncTokenId;
import deal.semantic.ir.AsyncTokenOwner;
import deal.semantic.ir.CallMode;
import deal.semantic.ir.CaptureMode;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExternalExecutionOwner;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.InternalResultType;
import deal.semantic.ir.ParameterBoundaryMode;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryContext;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryExecutor;
import deal.semantic.ir.BoundaryFailure;
import deal.semantic.ir.BoundaryOutcome;
import deal.semantic.ir.BoundaryValueView;
import deal.semantic.ir.ChainOperandCompletion;
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
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.NormalizedSlot;
import deal.semantic.ir.OpId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticArray;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticTable;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.StdlibFunctionId;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.UnicodeScalars;
import deal.semantic.ir.ValueId;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
 * expected/actual, active frames innermost-first, nested cause).
 * {@code STDLIB_CALL} executes through {@link SharedStdlibSemantics} —
 * the single stdlib algorithm executor — with the pinned precedence:
 * {@code STDLIB_PARAMETER} boundaries in one-based order, then the
 * algorithm (a failure resolves the primitive's registry-row projection
 * with the row's pinned origin and the active frames), then the single
 * {@code STDLIB_RETURN} boundary run by the call op. Console stdlib
 * calls record one ordered {@link SemanticRuntimeModel.EffectEvent}
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
        return execute(unit, table, null);
    }

    /**
     * Executes the entry module with the given deterministic host
     * responder (the E7 host seam): {@code CALL(HOST)}/host-source
     * adapter calls and async host operations resolve through the
     * responder — never through ambient host state. A host call without
     * a responder is a producer defect, never a silent projection.
     */
    public static SemanticRuntimeModel.ConsumerRun execute(LoweredModuleUnit unit,
                                                           StructuredBodyTable table,
                                                           HostResponder responder) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(table, "table must not be null");
        Execution state = new Execution(unit, table, responder);
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

    /**
     * Executes the entry module of a validated executable project with
     * the complete implementation closure: {@code CALL(EXTERNAL)}/
     * {@code ASYNC_START(EXTERNAL)} execute the callee unit's
     * {@code EXTERNAL_ENTRY} (sync: the callee {@code RETURN} runs the
     * single {@code EXTERNAL_RETURN} boundary and the value crosses the
     * ABI unchanged; async: the callee entry creates the canonical token
     * and the body task, the caller's alias token links through the
     * {@code ExternalAsyncLink}, and the caller's single
     * {@code ASYNC_COMPLETION} boundary validates the crossed value at
     * {@code AWAIT}). The entry module's run report is returned.
     */
    public static SemanticRuntimeModel.ConsumerRun execute(ExecutableLoweredProject project,
            Map<ModuleId, StructuredBodyTable> tables, HostResponder responder) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(tables, "tables must not be null");
        LoweredModuleUnit unit = project.modules().get(project.entryModule());
        if (unit == null) {
            throw new IllegalArgumentException("the entry module is not in the closure");
        }
        Execution state = new Execution(project, tables, responder);
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

    /**
     * Host-driven callback invocation (the E7 callback surface): the
     * unit's {@code CALLBACK_INVOKE} record for the bound function value
     * executes top-level — the {@code HOST_TO_DEAL} parameter boundaries
     * in one-based order, the resolved execution binding, and the single
     * {@code DEAL_TO_HOST} return boundary (by the executed body's
     * {@code RETURN} for bodies and DEAL-body-source adapters, by the
     * callback op for host/external functions and host/external-source
     * adapters). Sync functions only.
     */
    public static SemanticRuntimeModel.ConsumerRun invokeCallback(LoweredModuleUnit unit,
            StructuredBodyTable table, ValueId functionValue, List<Value> args) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(functionValue, "functionValue must not be null");
        Objects.requireNonNull(args, "args must not be null");
        Execution state = new Execution(unit, table, null);
        SemanticOp callback = null;
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.CALLBACK_INVOKE
                    && ((KindPayload.CallbackInvokePayload) op.payload()).function()
                        .equals(functionValue)) {
                callback = op;
                break;
            }
        }
        if (callback == null) {
            throw new IllegalArgumentException("the unit records no CALLBACK_INVOKE for "
                + functionValue + " (the callback surface is the lowerer's recorded "
                + "invocation, never an invented one)");
        }
        try {
            Value returned = state.executeCallback(callback, args);
            return state.report(new SemanticRuntimeModel.Terminal.Success(
                state.atomOf(returned)));
        } catch (DealFailure failure) {
            return state.report(new SemanticRuntimeModel.Terminal.DealFailure(
                state.snapshot(failure)));
        }
    }

    /**
     * Async-export invocation (the E7 cross-module async drive): the
     * callee module's async {@code EXTERNAL_ENTRY} creates its canonical
     * token and body task (the entry op id is the canonical token
     * identity), the deterministic FIFO drain runs the task exactly once
     * (the body's {@code RETURN} runs the single {@code FUNCTION_RETURN}
     * boundary), and the completed value/error is the run terminal —
     * caller-side {@code ASYNC_COMPLETION} boundaries run at the
     * caller's {@code AWAIT} sites inside the task itself.
     */
    public static SemanticRuntimeModel.ConsumerRun invokeAsyncEntry(
            ExecutableLoweredProject project, Map<ModuleId, StructuredBodyTable> tables,
            HostResponder responder, ModuleId module, String exportName, List<Value> args) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(module, "module must not be null");
        Objects.requireNonNull(exportName, "exportName must not be null");
        Objects.requireNonNull(args, "args must not be null");
        LoweredModuleUnit unit = project.modules().get(module);
        if (unit == null) {
            throw new IllegalArgumentException("module " + module + " is not in the closure");
        }
        SemanticOp entry = null;
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                    && ((KindPayload.ExternalEntryPayload) op.payload()).exportName()
                        .equals(exportName)) {
                entry = op;
                break;
            }
        }
        if (entry == null) {
            throw new IllegalArgumentException("module " + module
                + " records no EXTERNAL_ENTRY for export '" + exportName + "'");
        }
        Execution state = new Execution(project, tables, responder);
        try {
            // Dependency initialization first: the module-init block
            // (bindings, imports, the inert entry delegation) runs once
            // before the async entry task executes.
            try {
                state.runBlock(unit.moduleInit().initBlock());
            } catch (ReturnSignal | LoopSignal signal) {
                // The module-init block never transfers.
            }
            state.executeAsyncEntry(entry, null, List.copyOf(args), entry.opId().id());
            state.drainReadyTasks();
            Execution.Task task = state.tasks.get(entry.opId().id());
            if (task == null || !task.completed) {
                throw new IllegalStateException("the async entry task did not complete "
                    + "(producer defect)");
            }
            if (task.failure != null) {
                throw task.failure;
            }
            Value value = task.value == null ? Value.NullValue.INSTANCE : task.value;
            return state.report(new SemanticRuntimeModel.Terminal.Success(
                state.atomOf(value)));
        } catch (DealFailure failure) {
            return state.report(new SemanticRuntimeModel.Terminal.DealFailure(
                state.snapshot(failure)));
        }
    }

    /**
     * The deterministic host seam of the E7 call machine: a closed
     * responder supplies every host terminal — sync returns/throws,
     * async starts (an operation label, or a bad handle), and async
     * completions. The oracle records one ordered
     * {@code HOST_CALL}/{@code HOST_RETURN}/{@code HOST_THROW}/
     * {@code ASYNC_START_OP}/{@code ASYNC_COMPLETE_*} effect per
     * terminal, and a thrown host error crosses as a DEAL failure with
     * the host's code/message at the call origin.
     */
    public interface HostResponder {

        /** One sync host terminal: the returned value or the thrown host error. */
        sealed interface SyncOutcome permits SyncOutcome.Returned, SyncOutcome.Thrown {

            /** The host returned a value. */
            record Returned(Value value) implements SyncOutcome {
            }

            /** The host threw an error (code/message preserved). */
            record Thrown(String code, String message) implements SyncOutcome {
            }
        }

        /**
         * One sync host call terminal.
         *
         * @param module     the owning host module; non-null
         * @param export     the host export name; non-null
         * @param descriptor the declared function descriptor; non-null
         * @param args       the boundary-checked argument values; non-null
         * @return the terminal
         */
        default SyncOutcome call(ModuleId module, String export,
                                 RuntimeDescriptor.Func descriptor, List<Value> args) {
            throw new IllegalStateException("the responder scripts no sync host call for "
                + module + "." + export + " (the E7 host seam fails closed)");
        }

        /**
         * One async host start: returns the operation label the returned
         * handle binds to, or {@code null} for a bad handle (the
         * {@code ASYNC_OPERATION_HANDLE} terminal check fails).
         *
         * @param module         the owning host module; non-null
         * @param export         the host export name; non-null
         * @param descriptor     the declared async function descriptor; non-null
         * @param args           the boundary-checked argument values; non-null
         * @param operationLabel the deterministic operation label the
         *                       canonical token binds one-to-one to; non-null
         * @return the bound label, or {@code null} for a bad handle
         */
        default String startAsync(ModuleId module, String export,
                                  RuntimeDescriptor.Func descriptor, List<Value> args,
                                  String operationLabel) {
            return operationLabel;
        }

        /**
         * One async host completion: the completion value or the thrown
         * host error of the operation label.
         *
         * @param operationLabel the operation label; non-null
         * @return the completion terminal
         */
        default SyncOutcome completeAsync(String operationLabel) {
            return new SyncOutcome.Returned(Value.NullValue.INSTANCE);
        }
    }

    // =========================================================================
    // Runtime values
    // =========================================================================

    /** The oracle's closed runtime-value model. */
    public sealed interface Value
        permits Value.NullValue, Value.MissingValue, Value.BoolValue, Value.IntValue,
                Value.NumValue, Value.StrValue, Value.TableValue, Value.ArrayValue,
                Value.FuncValue, Value.AdapterValue, Value.IntrinsicValue, Value.ErrorValue,
                Value.SlotValue {

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

        /** A {@code FUNCTION_ADAPT} adapter value (target signature). */
        record AdapterValue(RuntimeDescriptor.Func signature) implements Value {
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
        /** The deterministic host responder (the E7 host seam); null outside host drives. */
        final HostResponder responder;
        /** The complete implementation closure (entry module included). */
        final Map<ModuleId, UnitState> units = new LinkedHashMap<>();
        /** The entry module's unit state. */
        final UnitState entry;
        /** The entry module's op index (single-unit aliases). */
        final Map<OpId, SemanticOp> opsById = new HashMap<>();
        final Map<OpId, List<SemanticOp>> childrenByParent = new HashMap<>();
        /** One async task/operation state per canonical token identity. */
        final Map<Long, Task> tasks = new LinkedHashMap<>();
        /** The deterministic FIFO queue of pending DEAL body tasks. */
        final ArrayDeque<Long> readyQueue = new ArrayDeque<>();
        /** The canonical-token → host operation-label bindings. */
        final Map<Long, String> hostOperations = new LinkedHashMap<>();
        /** The heap function value → resolved execution binding index. */
        final IdentityHashMap<Value, FunctionExecutionBinding> bindingsByValue =
            new IdentityHashMap<>();
        /**
         * The payload-owned children: ops referenced by an owner payload's
         * child/boundary id lists (chain children, boundary children of
         * ARRAY_NEW/CALL/STDLIB_CALL/MEMBER_READ/INDEX_READ/RETURN). The
         * block walk skips them — their owner arm executes each exactly
         * once in payload order; a double execution would emit duplicated
         * effects and fail the trace pairing.
         */
        final java.util.Set<OpId> ownedChildren = new java.util.HashSet<>();
        /** The payload-owned children only (closure computation excludes them). */
        final java.util.Set<OpId> structuralOwned;
        final Map<ValueId, Value> values = new HashMap<>();
        final Map<String, Cell> cells = new HashMap<>();
        /**
         * The per-invocation parameter-cell overlays, innermost first:
         * every body invocation pushes a fresh overlay holding its
         * parameter cells, so a recursive invocation re-binds its own
         * cells without corrupting the enclosing invocation's (the
         * closed cell model is keyed by binding identity — recursion
         * needs the invocation dimension).
         */
        final ArrayDeque<Map<String, Cell>> cellOverlays = new ArrayDeque<>();
        /**
         * The per-invocation value overlays, innermost first: a body
         * invocation pushes a fresh overlay so a re-executed op's result
         * slot (the same static {@code ValueId} across recursive
         * invocations) publishes per invocation — the enclosing
         * invocation's slot values stay untouched.
         */
        final ArrayDeque<Map<ValueId, Value>> valueOverlays = new ArrayDeque<>();
        final List<SemanticRuntimeModel.TraceEvent> trace = new ArrayList<>();
        final List<SemanticRuntimeModel.EffectEvent> effects = new ArrayList<>();
        final List<FunctionId> frames = new ArrayList<>();
        long sequence = 0;
        String entryResultAtom = null;

        Execution(LoweredModuleUnit unit, StructuredBodyTable table, HostResponder responder) {
            this.unit = unit;
            this.table = table;
            this.responder = responder;
            UnitState entryState = new UnitState(unit, table);
            units.put(unit.moduleId(), entryState);
            entry = entryState;
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
            // execution): the block walk skips these. The chain operand
            // closures (A-D2) are added on top: each ASSIGN/DELETE
            // child's operand-producing ops execute at the child's
            // position inside the chain, never at their flat block-list
            // position (the hoisted-operand interleaving the parity
            // fixtures pin).
            structuralOwned = ChainOperandCompletion.structuralOwners(unit);
            ownedChildren.addAll(structuralOwned);
            ChainOperandCompletion.registerChainOperandOwners(unit, structuralOwned,
                ownedChildren);
            ownedChildren.addAll(entryState.ownedChildren);
        }

        Execution(ExecutableLoweredProject project, Map<ModuleId, StructuredBodyTable> tables,
                  HostResponder responder) {
            LoweredModuleUnit entryUnit = project.modules().get(project.entryModule());
            if (entryUnit == null) {
                throw new IllegalArgumentException("the entry module is not in the closure");
            }
            this.unit = entryUnit;
            this.table = tables.get(project.entryModule());
            if (this.table == null) {
                throw new IllegalArgumentException("the entry module has no body table");
            }
            this.responder = responder;
            UnitState entryState = null;
            for (Map.Entry<ModuleId, LoweredModuleUnit> moduleEntry
                    : project.modules().entrySet()) {
                StructuredBodyTable moduleTable = tables.get(moduleEntry.getKey());
                if (moduleTable == null) {
                    throw new IllegalArgumentException("missing body table for module "
                        + moduleEntry.getKey());
                }
                UnitState state = new UnitState(moduleEntry.getValue(), moduleTable);
                units.put(moduleEntry.getKey(), state);
                if (moduleEntry.getKey().equals(project.entryModule())) {
                    entryState = state;
                }
            }
            entry = entryState;
            for (SemanticOp op : entryUnit.ops()) {
                opsById.put(op.opId(), op);
            }
            for (SemanticOp op : entryUnit.ops()) {
                OpId parent = op.origin().parentOpId();
                if (parent != null) {
                    childrenByParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(op);
                }
            }
            structuralOwned = ChainOperandCompletion.structuralOwners(entryUnit);
            ownedChildren.addAll(structuralOwned);
            ChainOperandCompletion.registerChainOperandOwners(entryUnit, structuralOwned,
                ownedChildren);
            ownedChildren.addAll(entryState.ownedChildren);
        }

        /** One module's validated execution state (ops, children, ownership). */
        private static final class UnitState {
            final LoweredModuleUnit unit;
            final StructuredBodyTable table;
            final Map<OpId, SemanticOp> opsById = new HashMap<>();
            final Map<OpId, List<SemanticOp>> childrenByParent = new HashMap<>();
            final Set<OpId> ownedChildren;

            UnitState(LoweredModuleUnit unit, StructuredBodyTable table) {
                this.unit = unit;
                this.table = table;
                for (SemanticOp op : unit.ops()) {
                    opsById.put(op.opId(), op);
                }
                for (SemanticOp op : unit.ops()) {
                    OpId parent = op.origin().parentOpId();
                    if (parent != null) {
                        childrenByParent.computeIfAbsent(parent,
                            k -> new ArrayList<>()).add(op);
                    }
                }
                Set<OpId> structural = ChainOperandCompletion.structuralOwners(unit);
                ownedChildren = new HashSet<>(structural);
                ChainOperandCompletion.registerChainOperandOwners(unit, structural,
                    ownedChildren);
                // The nested source ASYNC_START of an adapter-over-async
                // task executes under its outer op's arm, never at its
                // flat block-list position.
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
            }
        }

        /** The current value of one slot (overlays innermost-first). */
        Value valueOf(ValueId id) {
            for (Map<ValueId, Value> overlay : valueOverlays) {
                Value value = overlay.get(id);
                if (value != null) {
                    return value;
                }
            }
            return values.get(id);
        }

        /** Publishes one slot value (the innermost invocation overlay wins). */
        void putValue(ValueId id, Value value) {
            if (valueOverlays.isEmpty()) {
                values.put(id, value);
            } else {
                valueOverlays.peek().put(id, value);
            }
        }

        /** The unit state owning one op id. */
        UnitState stateOf(OpId opId) {
            UnitState state = units.get(opId.module());
            if (state == null) {
                throw new IllegalStateException("op " + opId + " names a module outside "
                    + "the executable closure (producer defect)");
            }
            return state;
        }

        /** Resolves one op id in its owning unit. */
        SemanticOp opOf(OpId opId) {
            SemanticOp op = stateOf(opId).opsById.get(opId);
            if (op == null) {
                throw new IllegalStateException("op " + opId + " is not a member of its "
                    + "validated unit (producer defect)");
            }
            return op;
        }

        /** One async task/operation state per canonical token identity. */
        static final class Task {
            final AsyncTokenId token;
            java.util.function.Supplier<Value> body;
            boolean ran;
            Value value;
            DealFailure failure;
            boolean completed;

            Task(AsyncTokenId token, java.util.function.Supplier<Value> body) {
                this.token = token;
                this.body = body;
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
                case Value.AdapterValue adapter -> allocate(adapter);
            };
        }

        /** The canonical token atom of an ASYNC_START/EXTERNAL_ENTRY SUCCESS. */
        String tokenAtom(AsyncTokenId token) {
            return switch (token) {
                case AsyncTokenId.Canonical canonical -> "tok:" + canonical.tokenId()
                    + ":" + canonical.owner().name();
                case AsyncTokenId.Alias alias -> "alias:" + alias.tokenId() + "->"
                    + canonicalReferent(alias.referent());
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
            return atomOf(valueOf(op.operands().get(index)));
        }

        String originText(SourceOrigin origin) {
            SourceSpan span = origin.span();
            return SemanticRuntimeModel.originAtom(origin.sourceId(),
                span == null ? null : span.startLine(),
                span == null ? null : span.startColumn());
        }

        // -- events -----------------------------------------------------------------

        void emitStart(SemanticOp op, List<String> inputs) {
            trace.add(new SemanticRuntimeModel.TraceEvent(sequence++,
                op.opId().module().path(), op.opId(), op.origin().parentOpId(), op.kind(),
                SemanticRuntimeModel.Phase.START, op.contract().canonicalDigest(),
                inputs, null, null));
        }

        void emitSuccess(SemanticOp op, String output) {
            trace.add(new SemanticRuntimeModel.TraceEvent(sequence++,
                op.opId().module().path(), op.opId(), op.origin().parentOpId(), op.kind(),
                SemanticRuntimeModel.Phase.SUCCESS, op.contract().canonicalDigest(),
                List.of(), output, null));
        }

        void emitFailure(SemanticOp op, DealFailure failure) {
            trace.add(new SemanticRuntimeModel.TraceEvent(sequence++,
                op.opId().module().path(), op.opId(), op.origin().parentOpId(), op.kind(),
                SemanticRuntimeModel.Phase.FAILURE, op.contract().canonicalDigest(),
                List.of(), null, snapshot(failure)));
        }

        /** START with a structural parent override (cross-unit ENTRY execution). */
        void emitStartParented(SemanticOp op, OpId parent, List<String> inputs) {
            trace.add(new SemanticRuntimeModel.TraceEvent(sequence++,
                op.opId().module().path(), op.opId(), parent, op.kind(),
                SemanticRuntimeModel.Phase.START, op.contract().canonicalDigest(),
                inputs, null, null));
        }

        /** SUCCESS with a structural parent override (cross-unit ENTRY execution). */
        void emitSuccessParented(SemanticOp op, OpId parent, String output) {
            trace.add(new SemanticRuntimeModel.TraceEvent(sequence++,
                op.opId().module().path(), op.opId(), parent, op.kind(),
                SemanticRuntimeModel.Phase.SUCCESS, op.contract().canonicalDigest(),
                List.of(), output, null));
        }

        /** FAILURE with a structural parent override (cross-unit ENTRY execution). */
        void emitFailureParented(SemanticOp op, OpId parent, DealFailure failure) {
            trace.add(new SemanticRuntimeModel.TraceEvent(sequence++,
                op.opId().module().path(), op.opId(), parent, op.kind(),
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
            if (!create) {
                for (Map<String, Cell> overlay : cellOverlays) {
                    Cell cell = overlay.get(key);
                    if (cell != null) {
                        return cell;
                    }
                }
                return cells.get(key);
            }
            if (cellOverlays.isEmpty()) {
                Cell cell = cells.get(key);
                if (cell == null) {
                    cell = new Cell(binding, generation);
                    cells.put(key, cell);
                }
                return cell;
            }
            // The innermost invocation overlay hosts fresh cells; a cell
            // already present there (an earlier re-entry of the same
            // binding identity inside one invocation) is reused.
            Map<String, Cell> top = cellOverlays.peek();
            Cell cell = top.get(key);
            if (cell == null) {
                cell = new Cell(binding, generation);
                top.put(key, cell);
            }
            return cell;
        }

        /** The binding's current (latest-generation) cell (overlays innermost-first). */
        Cell currentCellOf(BindingId binding) {
            for (Map<String, Cell> overlay : cellOverlays) {
                Cell latest = null;
                for (Cell cell : overlay.values()) {
                    if (cell.binding.equals(binding)
                            && (latest == null || cell.generation > latest.generation)) {
                        latest = cell;
                    }
                }
                if (latest != null) {
                    return latest;
                }
            }
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

        /**
         * Pushes one invocation's parameter-cell overlay: the body
         * block's leading parameter ALLOCs get fresh cells bound to the
         * argument values (recursion-safe — the enclosing invocation's
         * cells stay untouched).
         */
        void pushParamCells(UnitState state, BlockId bodyBlock, int paramCount,
                            List<Value> args) {
            List<OpId> bodyOps = state.table.blockOps().get(bodyBlock);
            if (bodyOps == null) {
                throw new IllegalStateException("the callee body block " + bodyBlock
                    + " has no membership row (producer defect)");
            }
            Map<String, Cell> overlay = new LinkedHashMap<>();
            for (int i = 0; i < paramCount && i < bodyOps.size(); i++) {
                SemanticOp alloc = state.opsById.get(bodyOps.get(i));
                if (alloc.kind() != SemanticOpKind.BINDING_ALLOC) {
                    throw new IllegalStateException("the callee body's leading op "
                        + alloc.opId() + " is not a BINDING_ALLOC (parameter cell)");
                }
                KindPayload.BindingAllocPayload allocPayload =
                    (KindPayload.BindingAllocPayload) alloc.payload();
                Cell cell = new Cell(allocPayload.binding(), allocPayload.generation());
                cell.value = args.get(i);
                cell.initialized = true;
                overlay.put(allocPayload.binding() + "#" + allocPayload.generation(), cell);
            }
            cellOverlays.push(overlay);
            valueOverlays.push(new LinkedHashMap<>());
        }

        /** Pops the innermost invocation's parameter-cell overlay. */
        void popParamCells() {
            cellOverlays.pop();
            valueOverlays.pop();
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
                inputs.add(atomOf(valueOf(operand)));
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
                case EXTERNAL_ENTRY -> throw new IllegalStateException(
                    "an EXTERNAL_ENTRY executes only under its triggering caller op "
                        + "(the block walk never runs the entry record)");
                case CALLBACK_INVOKE -> throw new IllegalStateException(
                    "a CALLBACK_INVOKE executes only under the host-driven "
                        + "invokeCallback surface (the block walk never runs it)");
                case ASYNC_START -> executeAsyncStart(op);
                case AWAIT -> executeAwait(op);
                case FUNCTION_ADAPT -> executeFunctionAdapt(op);
                case ENTRY_INVOKE -> executeEntryInvoke(op);
                case EXPORT_PUBLISH -> executeExportPublish(op);
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
                putValue(valueId, value);
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
            Value input = valueOf(op.operands().get(0));
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
            Value left = valueOf(op.operands().get(0));
            Value right = valueOf(op.operands().get(1));
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
                out.append(((Value.StrValue) valueOf(fragment)).value());
            }
            return publish(op, new Value.StrValue(out.toString()));
        }

        private String executeArrayNew(SemanticOp op) {
            KindPayload.ArrayNewPayload payload = (KindPayload.ArrayNewPayload) op.payload();
            List<Value> elements = new ArrayList<>();
            for (int i = 0; i < payload.values().size(); i++) {
                ValueId valueId = payload.values().get(i);
                OpId boundaryId = payload.elementBoundaryOpIds().get(i);
                SemanticOp boundary = opOf(boundaryId);
                Value checked = runBoundaryChild(boundary, valueOf(valueId),
                    BoundaryContext.element(i + 1));
                elements.add(checked);
            }
            return publish(op, new Value.ArrayValue(elements, payload.elementDescriptor()));
        }

        private String executeTableNew(SemanticOp op) {
            KindPayload.TableNewPayload payload = (KindPayload.TableNewPayload) op.payload();
            LinkedHashMap<String, Value> entries = new LinkedHashMap<>();
            for (KindPayload.TableEntry entry : payload.entries()) {
                entries.put(entry.key(), valueOf(entry.value()));
            }
            return publish(op, new Value.TableValue(entries));
        }

        private String executeArrayLength(SemanticOp op) {
            KindPayload.ArrayLengthPayload payload =
                (KindPayload.ArrayLengthPayload) op.payload();
            Value.ArrayValue array = (Value.ArrayValue) valueOf(payload.arrayValue());
            return publish(op, new Value.IntValue(array.elements().size()));
        }

        /**
         * MEMBER_READ: the missing-aware read, then its
         * CONTEXTUAL_TABLE_READ boundary child (missing→nullable-null /
         * E8001 via the contextual decision).
         */
        private String executeMemberRead(SemanticOp op) {
            KindPayload.MemberReadPayload payload = (KindPayload.MemberReadPayload) op.payload();
            Value.TableValue table = (Value.TableValue) valueOf(payload.table());
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
                    Value.TableValue table = (Value.TableValue) valueOf(payload.table());
                    table.entries().put(payload.key(), valueOf(payload.value()));
                }
                case KindPayload.MemberDeletePayload payload -> {
                    Value.TableValue table = (Value.TableValue) valueOf(payload.table());
                    table.entries().remove(payload.key());
                }
                case KindPayload.IndexWritePayload payload -> {
                    Value container = valueOf(payload.container());
                    NormalizedSlot slot = ((Value.SlotValue) valueOf(payload.slot())).slot();
                    commitIndexWrite(container, slot, valueOf(payload.value()));
                }
                case KindPayload.IndexDeletePayload payload -> {
                    Value container = valueOf(payload.container());
                    NormalizedSlot slot = ((Value.SlotValue) valueOf(payload.slot())).slot();
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
            Value rawKey = valueOf(payload.rawKey());
            Value currentLength = valueOf(payload.currentLength());
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
            Value container = valueOf(payload.container());
            NormalizedSlot slot = ((Value.SlotValue) valueOf(payload.slot())).slot();
            SemanticOp boundary = opOf(payload.elementBoundaryOpId());
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
                case Value.StrValue str -> {
                    UnicodeScalars.ScalarString scalar = UnicodeScalars.validate(str.value());
                    yield scalar instanceof UnicodeScalars.Valid
                        ? BoundaryValueView.of(ActualKind.STRING)
                        : BoundaryValueView.of(ActualKind.INVALID_UNICODE);
                }
                case Value.TableValue table -> BoundaryValueView.of(ActualKind.TABLE);
                case Value.ErrorValue error -> BoundaryValueView.ofClass("@builtin/Error");
                case Value.FuncValue func -> BoundaryValueView.ofFunction(func.signature());
                case Value.AdapterValue adapter ->
                    BoundaryValueView.ofFunction(adapter.signature());
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
            Value input = valueOf(payload.input());
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
            Value value = valueOf(payload.value());
            if (value == null) {
                // An intrinsic function identity (int()/number() as
                // first-class values — the producer-less identity slot).
                value = new Value.IntrinsicValue("intrinsic");
                putValue(payload.value(), value);
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
                    + payload.binding() + "#" + payload.generation() + " at op "
                    + op.opId() + " in " + op.origin().sourceId());
            }
            return publish(op, cell.value);
        }

        private String executeBindingStore(SemanticOp op) {
            KindPayload.BindingStorePayload payload =
                (KindPayload.BindingStorePayload) op.payload();
            Cell cell = cellOf(payload.binding(), payload.generation(), true);
            cell.value = valueOf(payload.value());
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
            Value.FuncValue closure = new Value.FuncValue(payload.function(),
                payload.signature(), captures);
            FunctionExecutionBinding binding = bindingOf((ValueId) op.result());
            if (binding != null) {
                bindingsByValue.put(closure, binding);
            }
            return publish(op, closure);
        }

        /** ASSIGN: children in payload order; the committed value result is pre-published. */
        private String executeAssign(SemanticOp op) {
            KindPayload.AssignPayload payload = (KindPayload.AssignPayload) op.payload();
            for (OpId childId : payload.childOps()) {
                SemanticOp child = opOf(childId);
                completeChainOperands(child);
                if (child.kind() == SemanticOpKind.BOUNDARY) {
                    KindPayload.BoundaryPayload boundaryPayload =
                        (KindPayload.BoundaryPayload) child.payload();
                    BoundaryContext context = chainBoundaryContext(op, boundaryPayload.kind());
                    Value input = valueOf(boundaryPayload.input());
                    runBoundaryChild(child, input, context);
                } else {
                    execute(child);
                }
            }
            Value committed = null;
            if (op.result() instanceof ValueId valueId) {
                committed = valueOf(valueId);
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
                        ((Value.SlotValue) valueOf(normalizeResult)).slot();
                } else if (child.kind() == SemanticOpKind.ARRAY_LENGTH
                        && child.result() instanceof ValueId lengthResult) {
                    length = (int) ((Value.IntValue) valueOf(lengthResult)).value();
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
                SemanticOp child = opOf(childId);
                completeChainOperands(child);
                if (child.kind() == SemanticOpKind.BOUNDARY) {
                    KindPayload.BoundaryPayload boundaryPayload =
                        (KindPayload.BoundaryPayload) child.payload();
                    runBoundaryChild(child, valueOf(boundaryPayload.input()),
                        chainBoundaryContext(op, boundaryPayload.kind()));
                } else {
                    execute(child);
                }
            }
            return null;
        }

        /**
         * A-D2 ("each child's operands complete before that child's
         * START"): the child's transitive operand-producing closure
         * executes at the child's position inside the chain — the
         * receiver/key/RHS operand effects interleave with the children
         * exactly as the hoisted-operand parity fixtures pin (an
         * operand's nested side-effecting argument completes before the
         * operand call's own effect, and the RHS operand effects run
         * only after the key child completed).
         */
        private void completeChainOperands(SemanticOp child) {
            for (SemanticOp producer : ChainOperandCompletion.operandProducersOf(
                    child, unit, structuralOwned)) {
                execute(producer);
            }
        }

        /**
         * CALL — the D13 machine: parameter boundaries in one-based order
         * with the closed table's cells, then the callee execution per
         * the resolved binding (DIRECT runs the recorded body block;
         * INDIRECT resolves the callee's binding and executes it; HOST
         * performs exactly one host request; EXTERNAL executes the
         * callee unit's EXTERNAL_ENTRY for SHARED_BODY or the target ABI
         * terminal for RETAINED_ABI), then the single return boundary of
         * the shape — by the callee's RETURN for bodies, by the call op
         * for host terminals and retained-ABI externals.
         */
        private String executeCall(SemanticOp op) {
            KindPayload.CallPayload payload = (KindPayload.CallPayload) op.payload();
            List<Value> checkedArgs = runParameterBoundaries(op,
                payload.parameterBoundaryOpIds());
            FunctionExecutionBinding binding = switch (payload.callee()) {
                case KindPayload.CallCallee.Static staticCallee -> staticCallee.binding();
                case KindPayload.CallCallee.Indirect indirect -> resolveBindingOf(indirect.callee());
            };
            Value returned = switch (binding) {
                case FunctionExecutionBinding.LoweredBody body ->
                    invokeLoweredBody(op, payload, body, checkedArgs);
                case FunctionExecutionBinding.AdapterBinding adapter ->
                    invokeAdapter(op, payload, adapter, checkedArgs);
                case FunctionExecutionBinding.HostFunction host ->
                    invokeHostCallWithBoundary(op, payload, host.hostModuleId(),
                        host.exportName(), host.descriptor(), checkedArgs);
                case FunctionExecutionBinding.HostFunctionValue hostValue ->
                    invokeHostCallWithBoundary(op, payload, hostValue.hostModuleId(),
                        "@value#" + hostValue.materializingBoundaryOpId().id(),
                        hostValue.descriptor(), checkedArgs);
                case FunctionExecutionBinding.ExternalFunction external ->
                    invokeExternalCall(op, payload, external, checkedArgs);
            };
            return publish(op, returned);
        }

        /** The parameter boundaries of one call/async/callback op in one-based order. */
        private List<Value> runParameterBoundaries(SemanticOp op, List<OpId> boundaryIds) {
            List<Value> checked = new ArrayList<>();
            for (int i = 0; i < boundaryIds.size(); i++) {
                SemanticOp boundary = opOf(boundaryIds.get(i));
                Value arg = valueOf(
                    ((KindPayload.BoundaryPayload) boundary.payload()).input());
                checked.add(runBoundaryChild(boundary, arg,
                    BoundaryContext.parameter(i + 1)));
            }
            return checked;
        }

        /** The body execution of one LoweredBody binding under the invoking op. */
        private Value invokeLoweredBody(SemanticOp op, KindPayload.CallPayload payload,
                                        FunctionExecutionBinding.LoweredBody body,
                                        List<Value> checkedArgs) {
            UnitState state = stateOf(op.opId());
            LoweredFunction function = state.unit.functions().get(body.functionId());
            if (function == null) {
                throw new IllegalStateException("CALL resolves a missing lowered function "
                    + body.functionId());
            }
            bindParamCells(state, body.blockId(), payload.signature().paramTypes().size(),
                checkedArgs);
            frames.add(0, body.functionId());
            Value returned;
            try {
                returned = runBodyBlock(body.blockId(),
                    payload.signature().paramTypes().size(), state);
            } finally {
                frames.remove(0);
                popParamCells();
            }
            return returned;
        }

        /** Binds one body block's leading parameter ALLOC cells (the invocation overlay). */
        private void bindParamCells(UnitState state, BlockId bodyBlock, int paramCount,
                                    List<Value> args) {
            pushParamCells(state, bodyBlock, paramCount, args);
        }

        /** The D15 adapter invocation protocol (source class statically fixed). */
        private Value invokeAdapter(SemanticOp op, KindPayload.CallPayload payload,
                                    FunctionExecutionBinding.AdapterBinding adapter,
                                    List<Value> checkedArgs) {
            int m = adapter.sourceSignature().paramTypes().size();
            Value sourceValue = loadAdapterSource(op, adapter);
            checkAdapterSourceSignature(op, adapter, sourceValue);
            FunctionExecutionBinding sourceBinding = resolveBindingOfValue(sourceValue);
            List<Value> leading = List.copyOf(checkedArgs.subList(0, m));
            return switch (sourceBinding) {
                case FunctionExecutionBinding.LoweredBody body -> {
                    UnitState state = stateOf(op.opId());
                    bindParamCells(state, body.blockId(), m, leading);
                    frames.add(0, body.functionId());
                    try {
                        yield runBodyBlock(body.blockId(), m, state);
                    } finally {
                        frames.remove(0);
                        popParamCells();
                    }
                }
                case FunctionExecutionBinding.HostFunction host -> {
                    Value value = invokeHostRequest(op, host.hostModuleId(),
                        host.exportName(), host.descriptor(), leading);
                    yield runBoundaryChild(opOf(payload.returnBoundaryOpId()), value,
                        BoundaryContext.none());
                }
                case FunctionExecutionBinding.HostFunctionValue hostValue -> {
                    Value value = invokeHostRequest(op, hostValue.hostModuleId(),
                        "@value#" + hostValue.materializingBoundaryOpId().id(),
                        hostValue.descriptor(), leading);
                    yield runBoundaryChild(opOf(payload.returnBoundaryOpId()), value,
                        BoundaryContext.none());
                }
                case FunctionExecutionBinding.ExternalFunction external -> {
                    if (external.executionOwner() == ExternalExecutionOwner.SHARED_BODY) {
                        yield executeExternalEntryFor(op, payload.externalEntryRef(),
                            external, leading);
                    }
                    Value value = invokeHostRequest(op, external.moduleId(),
                        external.exportName(), external.descriptor(), leading);
                    yield runBoundaryChild(opOf(payload.returnBoundaryOpId()), value,
                        BoundaryContext.none());
                }
                case FunctionExecutionBinding.AdapterBinding nested ->
                    throw new IllegalStateException("adapter-of-adapter invocation is "
                        + "outside the statically-resolved slice (ISSUE-0531)");
            };
        }

        /** The D15 source load: VALUE retains, SHARED_CELL re-reads, THUNK re-executes. */
        private Value loadAdapterSource(SemanticOp op,
                                        FunctionExecutionBinding.AdapterBinding adapter) {
            return switch (adapter.sourceRef()) {
                case AdaptSourceRef.Value value -> valueOf(value.value());
                case AdaptSourceRef.SharedCell cell -> {
                    Cell loaded = cellOf(cell.binding(), cell.generation(), false);
                    if (loaded == null || !loaded.initialized) {
                        throw new IllegalStateException("the adapter's SHARED_CELL load "
                            + "of an uninitialized cell " + cell.binding() + "#"
                            + cell.generation() + " (producer defect)");
                    }
                    yield loaded.value;
                }
                case AdaptSourceRef.Thunk thunk -> runThunkBlock(op, thunk);
            };
        }

        /** The adapter's source-signature check: E8010 FUNCTION_SIGNATURE at the invocation. */
        private void checkAdapterSourceSignature(SemanticOp op,
                FunctionExecutionBinding.AdapterBinding adapter, Value sourceValue) {
            BoundaryOutcome outcome = BoundaryExecutor.check(
                FailurePolicyId.FUNCTION_SIGNATURE, adapter.sourceSignature(),
                viewOfValue(sourceValue), BoundaryContext.none());
            switch (outcome) {
                case BoundaryOutcome.Pass ignored -> { }
                case BoundaryOutcome.Fail fail -> throw DealFailure.of(fail.failure(),
                    op.origin(), List.copyOf(frames));
            }
        }

        /** The REEVALUATE_THUNK source re-execution (side effects repeat per invoke). */
        private Value runThunkBlock(SemanticOp invocation, AdaptSourceRef.Thunk thunk) {
            UnitState state = stateOf(invocation.opId());
            List<OpId> ops = state.table.blockOps().get(thunk.blockId());
            if (ops == null) {
                throw new IllegalStateException("the adapter thunk block "
                    + thunk.blockId() + " has no membership row (producer defect)");
            }
            Value produced = null;
            for (OpId opId : ops) {
                if (state.ownedChildren.contains(opId)) {
                    continue;
                }
                SemanticOp thunkOp = state.opsById.get(opId);
                if (thunkOp == null) {
                    throw new IllegalStateException("op " + opId
                        + " is not a member of the validated unit");
                }
                execute(thunkOp);
                if (thunkOp.result() instanceof ValueId valueId) {
                    produced = valueOf(valueId);
                }
            }
            return produced;
        }

        /** One host request effect with its ordered terminal. */
        private Value invokeHostRequest(SemanticOp op, ModuleId module, String export,
                                        RuntimeDescriptor.Func descriptor, List<Value> args) {
            if (responder == null) {
                throw new IllegalStateException("a host call requires a deterministic "
                    + "host responder (the E7 host seam)");
            }
            effects.add(new SemanticRuntimeModel.EffectEvent(
                SemanticRuntimeModel.EffectEvent.Kind.HOST_CALL,
                module.path() + "." + export));
            HostResponder.SyncOutcome outcome = responder.call(module, export, descriptor,
                List.copyOf(args));
            return switch (outcome) {
                case HostResponder.SyncOutcome.Returned returned -> {
                    effects.add(new SemanticRuntimeModel.EffectEvent(
                        SemanticRuntimeModel.EffectEvent.Kind.HOST_RETURN,
                        module.path() + "." + export + "=" + atomOf(returned.value())));
                    yield returned.value();
                }
                case HostResponder.SyncOutcome.Thrown thrown -> {
                    effects.add(new SemanticRuntimeModel.EffectEvent(
                        SemanticRuntimeModel.EffectEvent.Kind.HOST_THROW,
                        module.path() + "." + export + "!" + thrown.code()));
                    throw new DealFailure(thrown.code(), thrown.message(), op.origin(),
                        null, null, null, List.copyOf(frames));
                }
            };
        }

        /** A host call plus the call-op-run HOST_TO_DEAL+HOST_SYNC_RETURN boundary. */
        private Value invokeHostCallWithBoundary(SemanticOp op, KindPayload.CallPayload payload,
                ModuleId module, String export, RuntimeDescriptor.Func descriptor,
                List<Value> args) {
            Value value = invokeHostRequest(op, module, export, descriptor, args);
            return runBoundaryChild(opOf(payload.returnBoundaryOpId()), value,
                BoundaryContext.none());
        }

        /** The external-call terminal per the execution owner. */
        private Value invokeExternalCall(SemanticOp op, KindPayload.CallPayload payload,
                FunctionExecutionBinding.ExternalFunction external, List<Value> args) {
            if (external.executionOwner() == ExternalExecutionOwner.SHARED_BODY) {
                return executeExternalEntryFor(op, payload.externalEntryRef(), external,
                    args);
            }
            Value value = invokeHostRequest(op, external.moduleId(), external.exportName(),
                external.descriptor(), args);
            return runBoundaryChild(opOf(payload.returnBoundaryOpId()), value,
                BoundaryContext.none());
        }

        /** The callee unit's sync EXTERNAL_ENTRY execution under the triggering caller op. */
        private Value executeExternalEntryFor(SemanticOp caller, OpId entryOpId,
                FunctionExecutionBinding.ExternalFunction external, List<Value> args) {
            if (entryOpId == null) {
                throw new IllegalStateException("the SHARED_BODY external call carries no "
                    + "externalEntryRef (producer defect)");
            }
            SemanticOp entry = opOf(entryOpId);
            UnitState state = stateOf(entryOpId);
            KindPayload.ExternalEntryPayload entryPayload =
                (KindPayload.ExternalEntryPayload) entry.payload();
            if (!entryPayload.exportName().equals(external.exportName())) {
                throw new IllegalStateException("the externalEntryRef names export '"
                    + entryPayload.exportName() + "' but the binding names '"
                    + external.exportName() + "' (producer defect)");
            }
            LoweredFunction function = state.unit.functions().get(entryPayload.function());
            if (function == null) {
                throw new IllegalStateException("EXTERNAL_ENTRY resolves a missing "
                    + "lowered function " + entryPayload.function());
            }
            emitStartParented(entry, caller.opId(), List.of());
            try {
                // The entry runs no parameter boundaries: the caller's
                // EXTERNAL_PARAMETER boundaries ran exactly once.
                bindParamCells(state, function.body(),
                    entryPayload.signature().paramTypes().size(), args);
                frames.add(0, entryPayload.function());
                Value returned;
                try {
                    returned = runBodyBlock(function.body(),
                        entryPayload.signature().paramTypes().size(), state);
                } finally {
                    frames.remove(0);
                    popParamCells();
                }
                emitSuccessParented(entry, caller.opId(), atomOf(returned));
                return returned;
            } catch (DealFailure failure) {
                emitFailureParented(entry, caller.opId(), failure);
                throw failure;
            }
        }

        /** The registered binding of one producing allocation identity, or null. */
        private FunctionExecutionBinding bindingOf(ValueId identity) {
            for (UnitState state : units.values()) {
                FunctionExecutionBinding found = state.unit.functionBindings().get(
                    new FunctionAllocationIdentity(identity.id()));
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        /** The binding of one callee/function ValueId (fail closed when absent). */
        private FunctionExecutionBinding resolveBindingOf(ValueId callee) {
            FunctionExecutionBinding binding = bindingOf(callee);
            if (binding == null) {
                throw new IllegalStateException("function value " + callee
                    + " has no registered FunctionExecutionBinding (producer defect)");
            }
            return binding;
        }

        /** The heap function value's resolved execution binding (fail closed). */
        private FunctionExecutionBinding resolveBindingOfValue(Value value) {
            FunctionExecutionBinding binding = bindingsByValue.get(value);
            if (binding == null) {
                throw new IllegalStateException("function value " + atomOf(value)
                    + " has no registered FunctionExecutionBinding (producer defect)");
            }
            return binding;
        }

        /**
         * Runs a callee body block, skipping the leading parameter ALLOCs
         * (already bound); a RETURN transfers the checked value out.
         */
        private Value runBodyBlock(BlockId block, int skipLeading) {
            return runBodyBlock(block, skipLeading, entry);
        }

        private Value runBodyBlock(BlockId block, int skipLeading, UnitState state) {
            List<OpId> ops = state.table.blockOps().get(block);
            if (ops == null) {
                throw new IllegalStateException("block " + block
                    + " has no membership row (a malformed table — the production "
                    + "validator rejects this)");
            }
            int i = 0;
            for (OpId opId : ops) {
                if (i++ < skipLeading) {
                    continue;
                }
                if (state.ownedChildren.contains(opId)) {
                    continue;
                }
                SemanticOp op = state.opsById.get(opId);
                if (op == null) {
                    throw new IllegalStateException("op " + opId
                        + " is not a member of the validated unit");
                }
                try {
                    execute(op);
                } catch (ReturnSignal signal) {
                    return signal.value;
                }
            }
            return Value.NullValue.INSTANCE;
        }

        /**
         * FUNCTION_ADAPT creation (D15): SUCCESS publishes a fresh
         * adapter identity of the target signature; creation evaluates no
         * thunk and reads no binding (VALUE's single operand already
         * completed).
         */
        private String executeFunctionAdapt(SemanticOp op) {
            KindPayload.FunctionAdaptPayload payload =
                (KindPayload.FunctionAdaptPayload) op.payload();
            Value.AdapterValue adapter = new Value.AdapterValue(payload.targetSignature());
            FunctionExecutionBinding binding = resolveBindingOf((ValueId) op.result());
            bindingsByValue.put(adapter, binding);
            return publish(op, adapter);
        }

        /**
         * ASYNC_START — the per-resolution terminal (D13 step 6): the
         * parameter boundaries in one-based order (zero under
         * {@code ELIDED_BY_ADAPTER}), then the source's own terminal —
         * a canonical token plus one body task for a DEAL body; the
         * adapter protocol plus the nested source {@code ASYNC_START}
         * for adapter-over-async; one host request with the
         * {@code AsyncStart} terminal, handle validation as the op's own
         * {@code ASYNC_OPERATION_HANDLE} terminal check, and a canonical
         * token bound to the operation label for an async host function;
         * the callee unit's async {@code EXTERNAL_ENTRY} for an async
         * external (the caller token aliases the callee canonical token
         * through {@code ExternalAsyncLink}).
         */
        private String executeAsyncStart(SemanticOp op) {
            KindPayload.AsyncStartPayload payload =
                (KindPayload.AsyncStartPayload) op.payload();
            // Under ELIDED_BY_ADAPTER the leading-M operand values are the
            // source arguments (zero parameter boundaries — the outer
            // adapter's xN checks are the complete set).
            List<Value> checkedArgs;
            if (payload.parameterBoundaryMode() == ParameterBoundaryMode.ELIDED_BY_ADAPTER) {
                checkedArgs = new ArrayList<>();
                for (ValueId operand : op.operands()) {
                    checkedArgs.add(valueOf(operand));
                }
            } else {
                checkedArgs = runParameterBoundaries(op, payload.parameterBoundaryOpIds());
            }
            FunctionExecutionBinding binding = switch (payload.callee()) {
                case KindPayload.CallCallee.Static staticCallee -> staticCallee.binding();
                case KindPayload.CallCallee.Indirect indirect -> resolveBindingOf(indirect.callee());
            };
            AsyncTokenId token = (AsyncTokenId) op.result();
            switch (binding) {
                case FunctionExecutionBinding.LoweredBody body -> {
                    tasks.put(token.tokenId(), new Task(token,
                        () -> runTaskBody(op, body, checkedArgs)));
                    readyQueue.add(token.tokenId());
                }
                case FunctionExecutionBinding.AdapterBinding adapter -> {
                    tasks.put(token.tokenId(), new Task(token,
                        () -> runAdapterOverAsyncTask(op, adapter, checkedArgs)));
                    readyQueue.add(token.tokenId());
                }
                case FunctionExecutionBinding.HostFunction host -> {
                    requireHostResponder();
                    String label = payload.hostOperationLabel();
                    effects.add(new SemanticRuntimeModel.EffectEvent(
                        SemanticRuntimeModel.EffectEvent.Kind.ASYNC_START_OP, label));
                    String bound = responder.startAsync(host.hostModuleId(),
                        host.exportName(), host.descriptor(), List.copyOf(checkedArgs),
                        label);
                    if (bound == null) {
                        throw registryFailure(op, FailurePolicyId.ASYNC_OPERATION_HANDLE,
                            "async-operation", "nothing");
                    }
                    hostOperations.put(token.tokenId(), bound);
                    tasks.put(token.tokenId(), new Task(token, null));
                }
                case FunctionExecutionBinding.HostFunctionValue hostValue -> {
                    requireHostResponder();
                    String label = payload.hostOperationLabel();
                    effects.add(new SemanticRuntimeModel.EffectEvent(
                        SemanticRuntimeModel.EffectEvent.Kind.ASYNC_START_OP, label));
                    String bound = responder.startAsync(hostValue.hostModuleId(),
                        "@value#" + hostValue.materializingBoundaryOpId().id(),
                        hostValue.descriptor(), List.copyOf(checkedArgs), label);
                    if (bound == null) {
                        throw registryFailure(op, FailurePolicyId.ASYNC_OPERATION_HANDLE,
                            "async-operation", "nothing");
                    }
                    hostOperations.put(token.tokenId(), bound);
                    tasks.put(token.tokenId(), new Task(token, null));
                }
                case FunctionExecutionBinding.ExternalFunction external -> {
                    if (payload.externalAsyncLink() == null) {
                        throw new IllegalStateException("ASYNC_START(EXTERNAL) without "
                            + "its ExternalAsyncLink (producer defect)");
                    }
                    long calleeTokenId =
                        payload.externalAsyncLink().calleeTokenId().tokenId();
                    SemanticOp entry = opOf(new OpId(external.moduleId(), calleeTokenId));
                    executeAsyncEntry(entry, op.opId(), checkedArgs, calleeTokenId);
                }
            }
            return tokenAtom(token);
        }

        /** The deterministic host responder guard (fail closed without one). */
        private void requireHostResponder() {
            if (responder == null) {
                throw new IllegalStateException("an async host call requires a "
                    + "deterministic host responder (the E7 host seam)");
            }
        }

        /** One DEAL body task: the body executes exactly once and completes the token. */
        private Value runTaskBody(SemanticOp op, FunctionExecutionBinding.LoweredBody body,
                                  List<Value> args) {
            UnitState state = stateOf(op.opId());
            bindParamCells(state, body.blockId(), args.size(), args);
            frames.add(0, body.functionId());
            try {
                return runBodyBlock(body.blockId(), args.size(), state);
            } finally {
                frames.remove(0);
                popParamCells();
            }
        }

        /**
         * One adapter-over-async task: the adapter protocol steps, then
         * the nested source {@code ASYNC_START} (ELIDED_BY_ADAPTER) with
         * its own per-resolution terminal; the outer alias token
         * completes by delegation through the source token (zero return
         * boundaries of its own).
         */
        private Value runAdapterOverAsyncTask(SemanticOp op,
                FunctionExecutionBinding.AdapterBinding adapter, List<Value> checkedArgs) {
            Value sourceValue = loadAdapterSource(op, adapter);
            checkAdapterSourceSignature(op, adapter, sourceValue);
            SemanticOp nested = nestedAsyncStartOf(op);
            execute(nested);
            return Value.NullValue.INSTANCE;
        }

        /** The nested source ASYNC_START parented to the outer adapter-over-async op. */
        private SemanticOp nestedAsyncStartOf(SemanticOp outer) {
            for (SemanticOp candidate : stateOf(outer.opId()).unit.ops()) {
                if (candidate.kind() == SemanticOpKind.ASYNC_START
                        && outer.opId().equals(candidate.origin().parentOpId())) {
                    return candidate;
                }
            }
            throw new IllegalStateException("the adapter-over-async task has no nested "
                + "source ASYNC_START (producer defect)");
        }

        /** The callee unit's async EXTERNAL_ENTRY under the triggering caller op. */
        private void executeAsyncEntry(SemanticOp entry, OpId callerOpId, List<Value> args,
                                       long canonicalTokenId) {
            KindPayload.ExternalEntryPayload entryPayload =
                (KindPayload.ExternalEntryPayload) entry.payload();
            if (!entryPayload.async()) {
                throw new IllegalStateException("the async external call's entry is not "
                    + "async (producer defect)");
            }
            AsyncTokenId token = new AsyncTokenId.Canonical(canonicalTokenId,
                AsyncTokenOwner.DEAL_BODY_TASK);
            emitStartParented(entry, callerOpId, List.of());
            tasks.put(canonicalTokenId, new Task(token, () -> runEntryTask(entry, args)));
            readyQueue.add(canonicalTokenId);
            emitSuccessParented(entry, callerOpId, tokenAtom(token));
        }

        /** One async entry body task (the callee unit's canonical token task). */
        private Value runEntryTask(SemanticOp entry, List<Value> args) {
            KindPayload.ExternalEntryPayload entryPayload =
                (KindPayload.ExternalEntryPayload) entry.payload();
            UnitState state = stateOf(entry.opId());
            LoweredFunction function = state.unit.functions().get(entryPayload.function());
            if (function == null) {
                throw new IllegalStateException("EXTERNAL_ENTRY resolves a missing "
                    + "lowered function " + entryPayload.function());
            }
            bindParamCells(state, function.body(),
                entryPayload.signature().paramTypes().size(), args);
            frames.add(0, entryPayload.function());
            try {
                return runBodyBlock(function.body(),
                    entryPayload.signature().paramTypes().size(), state);
            } finally {
                frames.remove(0);
                popParamCells();
            }
        }

        /** The deterministic FIFO task drain (no parallel source execution). */
        private void drainReadyTasks() {
            while (!readyQueue.isEmpty()) {
                long tokenId = readyQueue.poll();
                Task task = tasks.get(tokenId);
                if (task == null || task.ran || task.completed) {
                    continue;
                }
                task.ran = true;
                if (task.body == null) {
                    continue; // host operations complete through the responder.
                }
                try {
                    task.value = task.body.get();
                    task.completed = true;
                } catch (DealFailure failure) {
                    task.failure = failure;
                    task.completed = true;
                }
            }
        }

        /**
         * AWAIT — the completion position (D13 step 6): the deterministic
         * FIFO drain first, then the token's completion. Alias tokens
         * complete exactly when their canonical referent completes, with
         * the referent's completion value/error unchanged; a failed
         * operation publishes the identical error (never a re-check or a
         * synthesized copy) and a completed value crosses the single
         * {@code ASYNC_COMPLETION} boundary at the await site. Host
         * operation tokens complete from the host through the responder.
         */
        private String executeAwait(SemanticOp op) {
            KindPayload.AwaitPayload payload = (KindPayload.AwaitPayload) op.payload();
            drainReadyTasks();
            long canonicalId = canonicalReferent(payload.token());
            Task task = tasks.get(canonicalId);
            if (task == null) {
                throw new IllegalStateException("AWAIT consumes an unbound token "
                    + payload.token() + " (producer defect)");
            }
            if (!task.completed) {
                // A host operation: the completion arrives from the host.
                String label = hostOperations.get(canonicalId);
                if (label == null) {
                    throw new IllegalStateException("the AWAIT token " + payload.token()
                        + " has no task and no host operation label (producer defect)");
                }
                requireHostResponder();
                HostResponder.SyncOutcome outcome = responder.completeAsync(label);
                switch (outcome) {
                    case HostResponder.SyncOutcome.Returned returned -> {
                        effects.add(new SemanticRuntimeModel.EffectEvent(
                            SemanticRuntimeModel.EffectEvent.Kind.ASYNC_COMPLETE_RETURN,
                            label + "=" + atomOf(returned.value())));
                        task.value = returned.value();
                        task.completed = true;
                    }
                    case HostResponder.SyncOutcome.Thrown thrown -> {
                        effects.add(new SemanticRuntimeModel.EffectEvent(
                            SemanticRuntimeModel.EffectEvent.Kind.ASYNC_COMPLETE_THROW,
                            label + "!" + thrown.code()));
                        task.failure = new DealFailure(thrown.code(), thrown.message(),
                            op.origin(), null, null, null, List.copyOf(frames));
                        task.completed = true;
                    }
                }
            }
            if (task.failure != null) {
                throw task.failure; // the identical error — never re-checked or copied
            }
            Value completed = task.value == null ? Value.NullValue.INSTANCE : task.value;
            SemanticOp boundary = opOf(payload.completionBoundaryOpId());
            Value checked = runBoundaryChild(boundary, completed, BoundaryContext.none());
            return publish(op, checked);
        }

        /** The canonical referent token identity (alias chains resolve transitively). */
        long canonicalReferent(AsyncTokenId token) {
            AsyncTokenId current = token;
            Set<Long> seen = new HashSet<>();
            while (current instanceof AsyncTokenId.Alias alias) {
                if (!seen.add(current.tokenId())) {
                    throw new IllegalStateException("alias cycle over token " + current);
                }
                current = alias.referent();
            }
            return current.tokenId();
        }

        /**
         * EXPORT_PUBLISH — the atomic publication record: the
         * MODULE_EXPORT boundary (descriptor-kind) checks the published
         * value, then the publication is the unit-level export fact
         * (inert at execution — the exports are recorded statically).
         */
        private String executeExportPublish(SemanticOp op) {
            KindPayload.ExportPublishPayload payload =
                (KindPayload.ExportPublishPayload) op.payload();
            Value value = valueOf(payload.value());
            for (SemanticOp candidate : stateOf(op.opId()).unit.ops()) {
                if (candidate.kind() == SemanticOpKind.BOUNDARY
                        && op.opId().equals(candidate.origin().parentOpId())
                        && ((KindPayload.BoundaryPayload) candidate.payload()).kind()
                            == BoundaryKind.MODULE_EXPORT) {
                    runBoundaryChild(candidate, value, BoundaryContext.none());
                }
            }
            return null;
        }

        /**
         * ENTRY_INVOKE — delegates exactly one {@code CALL(DIRECT)} to
         * {@code main}: null and exits the program after the terminal.
         */
        private String executeEntryInvoke(SemanticOp op) {
            SemanticOp call = entryCallOf(op);
            execute(call);
            entryResultAtom = atomOf(valueOf((ValueId) call.result()));
            return null;
        }

        /** The delegated CALL child parented to the entry op. */
        private SemanticOp entryCallOf(SemanticOp entry) {
            for (SemanticOp candidate : stateOf(entry.opId()).unit.ops()) {
                if (candidate.kind() == SemanticOpKind.CALL
                        && entry.opId().equals(candidate.origin().parentOpId())) {
                    return candidate;
                }
            }
            throw new IllegalStateException("ENTRY_INVOKE without its delegated CALL "
                + "(producer defect)");
        }

        /**
         * The CALLBACK_INVOKE execution (host-driven, top-level): the
         * {@code HOST_TO_DEAL} parameter boundaries in one-based order,
         * the resolved execution binding, and the single
         * {@code DEAL_TO_HOST} return boundary — by the executed body's
         * {@code RETURN} for bodies and DEAL-body-source adapters, by the
         * callback op for host/external functions and host/external-source
         * adapters. Sync functions only.
         */
        private Value executeCallback(SemanticOp callback, List<Value> args) {
            KindPayload.CallbackInvokePayload payload =
                (KindPayload.CallbackInvokePayload) callback.payload();
            List<Value> checked = new ArrayList<>();
            for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                SemanticOp boundary = opOf(payload.parameterBoundaryOpIds().get(i));
                Value arg = i < args.size() ? args.get(i) : Value.MissingValue.INSTANCE;
                putValue(((KindPayload.BoundaryPayload) boundary.payload()).input(), arg);
                checked.add(runBoundaryChild(boundary, arg,
                    BoundaryContext.parameter(i + 1)));
            }
            FunctionExecutionBinding binding = resolveBindingOf(payload.function());
            switch (binding) {
                case FunctionExecutionBinding.LoweredBody body -> {
                    UnitState state = stateOf(callback.opId());
                    bindParamCells(state, body.blockId(),
                        payload.descriptor().paramTypes().size(), checked);
                    frames.add(0, body.functionId());
                    try {
                        return runBodyBlock(body.blockId(),
                            payload.descriptor().paramTypes().size(), state);
                    } finally {
                        frames.remove(0);
                        popParamCells();
                    }
                }
                case FunctionExecutionBinding.AdapterBinding adapter -> {
                    int m = adapter.sourceSignature().paramTypes().size();
                    Value sourceValue = loadAdapterSource(callback, adapter);
                    checkAdapterSourceSignature(callback, adapter, sourceValue);
                    FunctionExecutionBinding sourceBinding =
                        resolveBindingOfValue(sourceValue);
                    List<Value> leading = List.copyOf(checked.subList(0, m));
                    return switch (sourceBinding) {
                        case FunctionExecutionBinding.LoweredBody body -> {
                            UnitState state = stateOf(callback.opId());
                            bindParamCells(state, body.blockId(), m, leading);
                            frames.add(0, body.functionId());
                            try {
                                yield runBodyBlock(body.blockId(), m, state);
                            } finally {
                                frames.remove(0);
                                popParamCells();
                            }
                        }
                        case FunctionExecutionBinding.HostFunction host -> {
                            Value value = invokeHostRequest(callback, host.hostModuleId(),
                                host.exportName(), host.descriptor(), leading);
                            yield runBoundaryChild(opOf(payload.returnBoundaryOpId()),
                                value, BoundaryContext.none());
                        }
                        case FunctionExecutionBinding.HostFunctionValue hostValue -> {
                            Value value = invokeHostRequest(callback,
                                hostValue.hostModuleId(),
                                "@value#" + hostValue.materializingBoundaryOpId().id(),
                                hostValue.descriptor(), leading);
                            yield runBoundaryChild(opOf(payload.returnBoundaryOpId()),
                                value, BoundaryContext.none());
                        }
                        case FunctionExecutionBinding.ExternalFunction external -> {
                            Value value = invokeHostRequest(callback, external.moduleId(),
                                external.exportName(), external.descriptor(), leading);
                            yield runBoundaryChild(opOf(payload.returnBoundaryOpId()),
                                value, BoundaryContext.none());
                        }
                        case FunctionExecutionBinding.AdapterBinding nested ->
                            throw new IllegalStateException("adapter-of-adapter "
                                + "invocation is outside the statically-resolved slice "
                                + "(ISSUE-0531)");
                    };
                }
                case FunctionExecutionBinding.HostFunction host -> {
                    Value value = invokeHostRequest(callback, host.hostModuleId(),
                        host.exportName(), host.descriptor(), checked);
                    return runBoundaryChild(opOf(payload.returnBoundaryOpId()), value,
                        BoundaryContext.none());
                }
                case FunctionExecutionBinding.HostFunctionValue hostValue -> {
                    Value value = invokeHostRequest(callback, hostValue.hostModuleId(),
                        "@value#" + hostValue.materializingBoundaryOpId().id(),
                        hostValue.descriptor(), checked);
                    return runBoundaryChild(opOf(payload.returnBoundaryOpId()), value,
                        BoundaryContext.none());
                }
                case FunctionExecutionBinding.ExternalFunction external -> {
                    Value value = invokeHostRequest(callback, external.moduleId(),
                        external.exportName(), external.descriptor(), checked);
                    return runBoundaryChild(opOf(payload.returnBoundaryOpId()), value,
                        BoundaryContext.none());
                }
            }
        }

        private String executeIntrinsic(SemanticOp op) {
            KindPayload.IntrinsicCallPayload payload =
                (KindPayload.IntrinsicCallPayload) op.payload();
            Value input = valueOf(payload.input());
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
                case Value.AdapterValue ignored -> "function";
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
            // Step 1 — precedence (parent D13 step 3 / closed
            // STDLIB_CALL cell): every STDLIB_PARAMETER boundary runs in
            // one-based order before any algorithm executes; the first
            // failing parameter boundary wins (descriptor-kind rule,
            // including E8001 "expected string, got invalid Unicode
            // scalar encoding" at the boundary origin).
            List<Value> checked = new ArrayList<>();
            for (int i = 0; i < payload.args().size(); i++) {
                Value value = valueOf(payload.args().get(i));
                SemanticOp boundary = parameterBoundaryOf(op, i);
                if (boundary != null) {
                    value = runBoundaryChild(boundary, value,
                        BoundaryContext.parameter(i + 1));
                }
                checked.add(value);
            }
            // Step 2 — the shared algorithm: SharedStdlibSemantics is the
            // single executor of the 20 named operations
            // (stdlib-operations-and-time-lock D4) over the
            // boundary-admitted carriers; a failure is the primitive's
            // registry-row projection resolved with the row's pinned
            // origin (the STDLIB_CALL call origin) and the active DEAL
            // frames.
            List<SharedStdlibSemantics.Value> argv = new ArrayList<>();
            for (Value value : checked) {
                argv.add(stdlibCarrierOf(value));
            }
            SharedStdlibSemantics.ConsoleSink sink = consoleSinkFor(payload.function());
            SharedStdlibSemantics.Outcome<SharedStdlibSemantics.Value> outcome =
                SharedStdlibSemantics.execute(op, argv, sink);
            return switch (outcome) {
                case SharedStdlibSemantics.Outcome.Success<SharedStdlibSemantics.Value>
                        success -> {
                    Value result = stdlibResultOf(success.value());
                    // Step 3 — the single STDLIB_RETURN boundary run by
                    // the call op after the algorithm result
                    // (descriptor-kind rule); a successful parse whose
                    // top-level value is not a table fails here.
                    SemanticOp returnBoundary = returnBoundaryOf(op);
                    if (returnBoundary != null) {
                        result = runBoundaryChild(returnBoundary, result,
                            BoundaryContext.none());
                    }
                    yield publish(op, result);
                }
                case SharedStdlibSemantics.Outcome.Failure<SharedStdlibSemantics.Value>
                        failure -> {
                    SharedStdlibSemantics.StdlibFailure stdlibFailure = failure.failure();
                    throw DealFailure.of(stdlibFailure.failure(), stdlibFailure.origin(),
                        List.copyOf(frames));
                }
            };
        }

        /** The STDLIB_PARAMETER child for the i-th argument, or null. */
        private SemanticOp parameterBoundaryOf(SemanticOp op, int index) {
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

        // =========================================================================
        // Stdlib carrier conversion (oracle value model <-> SharedStdlibSemantics)
        // =========================================================================

        /**
         * The oracle runtime value → the closed stdlib carrier
         * ({@link SharedStdlibSemantics.Value}): the same closed value
         * view the oracle heap realizes, converted recursively for table
         * and array members (JSON data shapes). Values outside the
         * boundary-admitted set fail closed — the parameter boundaries
         * already passed, so such a carrier is a producer defect, never
         * a DEAL projection.
         */
        private SharedStdlibSemantics.Value stdlibCarrierOf(Value value) {
            return switch (value) {
                case Value.NullValue ignored ->
                    SharedStdlibSemantics.Value.Null.INSTANCE;
                case Value.BoolValue bool ->
                    new SharedStdlibSemantics.Value.Bool(bool.value());
                case Value.IntValue intValue -> {
                    long v = intValue.value();
                    if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                        throw new IllegalStateException(
                            "a stdlib int carrier must be signed32; got " + v
                                + " (the int parameter boundary's admission set)");
                    }
                    yield new SharedStdlibSemantics.Value.Int((int) v);
                }
                case Value.NumValue num ->
                    new SharedStdlibSemantics.Value.Number(num.value());
                case Value.StrValue str ->
                    SharedStdlibSemantics.Value.string(str.value());
                case Value.TableValue table -> new SharedStdlibSemantics.Value.Table(
                    stdlibTableCarrierOf(table));
                case Value.ArrayValue array -> stdlibArrayCarrierOf(array);
                case Value.FuncValue ignored ->
                    new SharedStdlibSemantics.Value.Other(ActualKind.FUNCTION, null);
                case Value.AdapterValue ignored ->
                    new SharedStdlibSemantics.Value.Other(ActualKind.FUNCTION, null);
                case Value.IntrinsicValue ignored ->
                    new SharedStdlibSemantics.Value.Other(ActualKind.FUNCTION, null);
                case Value.ErrorValue ignored -> new SharedStdlibSemantics.Value.Other(
                    ActualKind.CLASS, "@builtin/Error");
                case Value.MissingValue ignored ->
                    new SharedStdlibSemantics.Value.Other(ActualKind.MISSING, null);
                case Value.SlotValue ignored -> throw new IllegalStateException(
                    "a slot value is never a stdlib argument carrier");
            };
        }

        /** One oracle table → the closed stdlib table carrier (first-insertion order). */
        private SemanticTable<SharedStdlibSemantics.Value> stdlibTableCarrierOf(
                Value.TableValue table) {
            SemanticTable<SharedStdlibSemantics.Value> carrier = new SemanticTable<>();
            for (Map.Entry<String, Value> entry : table.entries().entrySet()) {
                carrier.put(entry.getKey(), stdlibCarrierOf(entry.getValue()));
            }
            return carrier;
        }

        /** One oracle array → the closed stdlib array carrier (index order). */
        private SharedStdlibSemantics.Value.Array stdlibArrayCarrierOf(
                Value.ArrayValue array) {
            List<SharedStdlibSemantics.Value> elements = new ArrayList<>();
            for (Value element : array.elements()) {
                elements.add(stdlibCarrierOf(element));
            }
            return (SharedStdlibSemantics.Value.Array)
                SharedStdlibSemantics.Value.array(elements);
        }

        /**
         * The shared algorithm result → the oracle runtime value
         * (recursive for the JSON data shapes the stringify/parse
         * algorithms publish: null/boolean/int/number/string leaves,
         * first-insertion-order tables, index-order arrays).
         */
        private Value stdlibResultOf(SharedStdlibSemantics.Value value) {
            return switch (value) {
                case SharedStdlibSemantics.Value.Null ignored ->
                    Value.NullValue.INSTANCE;
                case SharedStdlibSemantics.Value.Bool bool ->
                    new Value.BoolValue(bool.value());
                case SharedStdlibSemantics.Value.Int intValue ->
                    new Value.IntValue(intValue.value());
                case SharedStdlibSemantics.Value.Number number ->
                    new Value.NumValue(number.value());
                case SharedStdlibSemantics.Value.String string -> {
                    if (!(string.scalar() instanceof UnicodeScalars.Valid valid)) {
                        throw new IllegalStateException(
                            "a stdlib string result is always scalar-valid");
                    }
                    yield new Value.StrValue(valid.carrier());
                }
                case SharedStdlibSemantics.Value.Table table ->
                    stdlibResultTableOf(table.table());
                case SharedStdlibSemantics.Value.Array array ->
                    stdlibResultArrayOf(array.elements());
                case SharedStdlibSemantics.Value.Other other ->
                    throw new IllegalStateException(
                        "a stdlib algorithm result never carries an unsupported value: "
                            + other.kind());
            };
        }

        /** One closed stdlib table → the oracle table value (first-insertion order). */
        private Value.TableValue stdlibResultTableOf(
                SemanticTable<SharedStdlibSemantics.Value> table) {
            LinkedHashMap<String, Value> entries = new LinkedHashMap<>();
            for (String key : table.keys()) {
                SemanticTable.Lookup<SharedStdlibSemantics.Value> lookup = table.get(key);
                if (!(lookup
                        instanceof SemanticTable.Lookup.Present<SharedStdlibSemantics.Value>
                        present)) {
                    throw new IllegalStateException(
                        "a table key returned by keys() is always present");
                }
                entries.put(key, stdlibResultOf(present.value()));
            }
            return new Value.TableValue(entries);
        }

        /** One closed stdlib array → the oracle array value (index order). */
        private Value.ArrayValue stdlibResultArrayOf(
                SemanticArray<SharedStdlibSemantics.Value> elements) {
            List<Value> result = new ArrayList<>();
            for (int i = 0; i < elements.size(); i++) {
                result.add(stdlibResultOf(elements.elementAt(i)));
            }
            return new Value.ArrayValue(result, JSON_CARRIER_ELEMENT_DESCRIPTOR);
        }

        /**
         * The element descriptor of a JSON-parsed array carrier: untyped
         * JSON data has no declared element descriptor in the closed set,
         * and the field is never consulted on the stdlib result path (the
         * STDLIB_RETURN boundary checks only the declared top-level
         * descriptor). The value only completes the oracle array record.
         */
        private static final RuntimeDescriptor JSON_CARRIER_ELEMENT_DESCRIPTOR =
            new RuntimeDescriptor.Nullable(RuntimeDescriptor.Table.INSTANCE);

        /**
         * The injected console sink of a {@code CONSOLE_LOG}/
         * {@code CONSOLE_ERROR} call (D5), or null for every other id:
         * {@code write} receives the argument's exact scalar UTF-8 bytes
         * plus one ordered {@code \n}, and the oracle records one
         * ordered effect — the protocol effect text is the scalar text
         * (the shared emitters' {@code F|CONSOLE_WRITE} convention; the
         * ordered newline is the byte-level contract's, not part of the
         * recorded text). A sink failure is infrastructure
         * ({@code INFRASTRUCTURE_ONLY}): the primitive never converts it
         * to a DEAL failure and the exception propagates.
         */
        private SharedStdlibSemantics.ConsoleSink consoleSinkFor(
                StdlibFunctionId function) {
            if (function != StdlibFunctionId.CONSOLE_LOG
                    && function != StdlibFunctionId.CONSOLE_ERROR) {
                return null;
            }
            SharedStdlibSemantics.Channel channel =
                function == StdlibFunctionId.CONSOLE_LOG
                    ? SharedStdlibSemantics.Channel.STDOUT
                    : SharedStdlibSemantics.Channel.STDERR;
            return new SharedStdlibSemantics.ConsoleSink() {
                @Override
                public SharedStdlibSemantics.Channel channel() {
                    return channel;
                }

                @Override
                public void write(byte[] bytes) {
                    String text = new String(bytes, 0, bytes.length - 1,
                        StandardCharsets.UTF_8);
                    effects.add(new SemanticRuntimeModel.EffectEvent(
                        SemanticRuntimeModel.EffectEvent.Kind.CONSOLE_WRITE, text));
                }
            };
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
            Value condition = valueOf(payload.condition());
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
                    return publish(op, valueOf((ValueId) op.result()));
                }
                case LOGICAL_OR -> {
                    if (truthy) {
                        return publish(op, condition);
                    }
                    runBlock(payload.selectedBlock());
                    return publish(op, valueOf((ValueId) op.result()));
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
                        if (!((Value.BoolValue) valueOf(payload.condition())).value()) {
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
                        if (!((Value.BoolValue) valueOf(payload.condition())).value()) {
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
            Value iterable = valueOf(payload.iterable());
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
            Value.ErrorValue error = (Value.ErrorValue) valueOf(payload.errorValue());
            throw new DealFailure(error.code(), error.message(), op.origin(), null, null,
                null, List.copyOf(frames));
        }

        private String executeReturn(SemanticOp op) {
            KindPayload.ReturnPayload payload = (KindPayload.ReturnPayload) op.payload();
            Value value = payload.value() == null
                ? Value.NullValue.INSTANCE : valueOf(payload.value());
            SemanticOp boundary = opOf(payload.returnBoundaryOpId());
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
            Value value = new Value.IntrinsicValue("export:" + payload.module().path()
                + "." + payload.name());
            FunctionExecutionBinding binding = bindingOf((ValueId) op.result());
            if (binding != null) {
                bindingsByValue.put(value, binding);
            }
            return publish(op, value);
        }

        private String executeModuleImport(SemanticOp op) {
            KindPayload.ModuleImportPayload payload =
                (KindPayload.ModuleImportPayload) op.payload();
            return null;
        }

        private String executeDiscard(SemanticOp op) {
            KindPayload.DiscardPayload payload = (KindPayload.DiscardPayload) op.payload();
            Value discarded = valueOf(payload.value());
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
