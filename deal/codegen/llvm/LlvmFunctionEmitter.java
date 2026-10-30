package deal.codegen.llvm;

import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.AsyncTokenId;
import deal.semantic.ir.BindingGeneration;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.NullableSide;
import deal.semantic.ir.OpId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.ValueId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The basic-block, SSA-value, root-slot and failure-route emitter of the native
 * backend ({@code llvm-native-codegen-cfg-ssa} Architecture,
 * {@code LlvmFunctionEmitter}; C2, C4, C6, C8, {@code OpRendering},
 * {@code DiagnosticOriginProjection}, {@code CallFrameContract}).
 *
 * <p>Structured IR maps directly to a CFG: a {@code BRANCH} op emits its
 * condition and its two child blocks around a join, a {@code LOOP} emits its
 * per-iteration init block, its condition check, its body, its recorded update
 * block and its back edge, {@code BREAK}/{@code CONTINUE} transfer to the
 * recorded loop's own labels, and {@code TRY_CATCH} pushes its handler on the
 * emitter's failure-route stack so every recorded check or transfer failure in
 * the protected block reaches that handler instead of leaving the activation.
 * The handler binds the identical carried {@code deal_error*} as the boxed
 * {@code unknown} datum of the catch binding, then the catch block runs and
 * control continues after the statement.</p>
 *
 * <p>Results are SSA values held in per-value slots. Every managed-typed slot
 * that can be live across an allocating call owns a root-frame slot written at
 * definition (M4): a managed pointer directly, a persistent {@code deal_data}
 * through the tag-aware entry, and a reference-nullish operand only when it is
 * not one of the two nullish singletons. Every generated activation emits the
 * prologue and epilogue of {@code CallFrameContract} — the call-frame push with
 * its frame label, the root-frame enter with the static slot count, and the
 * root-frame leave plus call-frame pop on every exit path. A synthesized
 * dispatch shim contributes no activation frame of its own: the boxed apply
 * thunk and the adapter thunk pop their own label before they dispatch the
 * body they call, so an Error reports exactly the DEAL
 * activations that raised and dispatched it. A failure routed to
 * a handler keeps the activation's frame (the materialized Error already
 * snapshotted it); a failure that leaves the activation pops it first.</p>
 *
 * <p>Arithmetic, comparison and unary rendering reads exactly one arm table
 * ({@link LlvmSelectorArms}): the binary64 forms of C6, the one
 * {@code deal_text_compare} string authority, the closed {@code unknown}
 * relation of {@code deal_data_same}, the distinct nullish singleton/tag tests
 * and the nominal tag test — with no fast-math flag on any instruction.</p>
 */
final class LlvmFunctionEmitter {

    private final LlvmSession session;
    private final LoweredModuleUnit unit;
    private final StringBuilder text = new StringBuilder();
    private final List<String> allocas = new ArrayList<>();
    private final List<String> entryInit = new ArrayList<>();
    private final Map<Long, String> valueSlots = new LinkedHashMap<>();
    private final Map<Long, String> valueSlotTypes = new LinkedHashMap<>();
    private final Map<String, String> bindingSlots = new LinkedHashMap<>();
    private final Map<String, String> bindingSlotTypes = new LinkedHashMap<>();
    private final Map<Long, String> blockLabels = new LinkedHashMap<>();
    private final Map<Long, String> tokenSlots = new LinkedHashMap<>();
    private final Map<String, Integer> rootSlots = new LinkedHashMap<>();
    private final Deque<LoopTargets> loops = new ArrayDeque<>();
    private final Deque<FailureHandler> handlers = new ArrayDeque<>();
    private int loadCounter;
    private int labelCounter;
    private int rootSlotCount;
    private int rootEnterIndex = -1;
    private boolean terminated;
    private boolean fallsThrough;
    private String entryLabel;
    private String segmentLabel;
    private boolean entryBranched;
    private int currentDebugScope;
    private FunctionId functionId;
    /** One open loop's transfer labels. */
    private record LoopTargets(long loopId, String header, String next,
                               String exit) {
    }

    /** One open failure route: its handler label and pending-error slot. */
    private record FailureHandler(String label, String pendingSlot) {
    }

    LlvmFunctionEmitter(LlvmSession session, LoweredModuleUnit unit) {
        this.session = session;
        this.unit = unit;
    }

    // =========================================================================
    // One generated function
    // =========================================================================

    /**
     * Emits one lowered function definition with its prologue, its body and
     * its epilogue.
     */
    String emitFunction(FunctionId id) {
        this.functionId = id;
        LoweredFunction function = session.function(id);
        String linkageName = "F" + id.id();
        String display = session.displayName(session.ownerOf(id));
        currentDebugScope = session.debug.subprogram(display,
            function.sourceName(), linkageName,
            declarationLine(function));
        session.referenceRuntimeEntry("deal_call_frame_push");
        entryInit.add(LlvmIrPrinter.indent(1) + "call void @deal_call_frame_push("
            + frameLabelOperand(id) + ")");
        enterRootFrame();
        emitParameters(id, function);
        boolean setup = !function.captures().isEmpty()
            || hasCellParameter(id, function);
        if (setup) {
            // The capture prologue and every parameter cell allocation run in
            // one setup segment after the entry prologue, so an allocation the
            // body reads already happened while the entry still branches to
            // the first recorded block.
            String label = "setup" + id.id();
            entryInit.add(LlvmIrPrinter.indent(1) + "br label %" + label);
            text.append(label).append(":\n");
            entryBranched = true;
            terminated = false;
            emitCaptureSetup(id, function);
        } else {
            // Lentry already terminates in the branch assemble() writes.
            entryBranched = false;
            terminated = true;
        }
        emitBlock(function.body(), null);
        finishFunction(function);
        return assemble(id, function);
    }

    private String frameLabelOperand(FunctionId id) {
        LlvmData.StringConstant constant =
            session.data.frameLabel(session.labelOf(id));
        return session.data.operand(constant);
    }

    /**
     * The source line a function's {@code DISubprogram} declares: the start
     * line of its first source-attributed op, or line 1 for a function
     * without one.
     */
    private int declarationLine(LoweredFunction function) {
        // The function's declaration site is the origin of the closure that
        // materializes it; a function without one falls back to its first
        // source-attributed op, and the anchorless function to line 1.
        for (SemanticOp op : unit.ops()) {
            if (op.payload() instanceof KindPayload.ClosureNewPayload closure
                    && closure.function().equals(function.functionId())) {
                return Math.max(1, op.origin().span().startLine());
            }
        }
        for (SemanticOp op : session.memberOps(function.body())) {
            SourceOrigin origin = op.origin();
            if (origin.kind() == deal.semantic.ir.SourceOriginKind.USER) {
                return Math.max(1, origin.span().startLine());
            }
        }
        return 1;
    }

    /**
     * Declares and initializes the function's parameter bindings from the
     * ABI parameters (C3: {@code boolean} crosses as the 0/1 byte, and the
     * body holds the SSA {@code i1}).
     */
    private void emitParameters(FunctionId id, LoweredFunction function) {
        List<BindingId> parameters = session.parametersOf(id);
        List<RuntimeDescriptor> types = function.descriptor().paramTypes();
        if (parameters.size() != types.size()) {
            throw new IllegalStateException("the function " + id + " records "
                + types.size() + " parameter types for " + parameters.size()
                + " parameter bindings");
        }
        for (int index = 0; index < parameters.size(); index++) {
            RuntimeDescriptor descriptor = types.get(index);
            String representation = LlvmTypeMapper.representationOf(descriptor)
                .orElseThrow(() -> new IllegalStateException(
                    "a parameter of " + id + " carries no staged"
                        + " representation: " + descriptor.canonicalSpecText()));
            String abi = LlvmTypeMapper.abiParameterType(descriptor);
            String value = "%p" + index;
            if (descriptor instanceof RuntimeDescriptor.Boolean) {
                String narrowed = "%pn" + index;
                entryInit.add(LlvmIrPrinter.indent(1) + narrowed
                    + " = icmp ne " + abi + " " + value + ", 0");
                value = narrowed;
            }
            if (session.cellBinding(unit, parameters.get(index), 0)) {
                // A captured or reassigned parameter is one heap cell whose
                // datum the closure reads (C5): the native value is stashed
                // here and the setup segment publishes the cell below.
                String stash = "%parameter" + index + "value";
                allocas.add(stash + " = alloca " + representation);
                entryInit.add(LlvmIrPrinter.indent(1) + "store " + representation
                    + " " + value + ", " + representation + "* " + stash);
                continue;
            }
            String slot = bindingSlot(parameters.get(index), 0, representation);
            entryInit.add(LlvmIrPrinter.indent(1) + "store " + representation
                + " " + value + ", " + representation + "* " + slot);
            rootEntrySlot(descriptor, slot, value);
        }
    }

    /** The complete {@code define} block of one function. */
    private String assemble(FunctionId id, LoweredFunction function) {
        StringBuilder out = new StringBuilder();
        String name = "F" + id.id();
        String outcome = LlvmTypeMapper.outcomeType(
            function.descriptor().returnType());
        out.append("define internal ").append(outcome).append(" @")
            .append(name).append("(%struct.deal_fn* %env");
        for (int index = 0; index < function.descriptor().paramTypes().size();
                index++) {
            out.append(", ");
            out.append(LlvmTypeMapper.abiParameterType(
                function.descriptor().paramTypes().get(index)))
                .append(" %p").append(index);
        }
        out.append(") !dbg !").append(currentDebugScope).append(" {\n");
        out.append("Lentry:\n");
        for (String alloca : allocas) {
            out.append(LlvmIrPrinter.indent(1)).append(alloca).append('\n');
        }
        patchRootEnter();
        for (String line : entryInit) {
            out.append(line).append('\n');
        }
        if (!entryBranched) {
            out.append(LlvmIrPrinter.indent(1)).append("br label %")
                .append(blockLabel(function.body())).append('\n');
        }
        out.append(text);
        out.append("}\n");
        return out.toString();
    }

    private String functionEndLabel() {
        return "f" + functionId.id() + ".end";
    }

    /** Emits the fall-through epilogue of a void/{@code null}-result function. */
    private void finishFunction(LoweredFunction function) {
        if (!fallsThrough) {
            return;
        }
        RuntimeDescriptor result = function.descriptor().returnType();
        boolean carriesValue = !(LlvmTypeMapper.isStaticSingleton(result)
            || result instanceof RuntimeDescriptor.Void);
        startSegment(functionEndLabel());
        if (carriesValue) {
            throw new IllegalStateException("the lowered function "
                + function.functionId() + " falls through its body while its"
                + " declared result '" + result.canonicalSpecText()
                + "' carries a value");
        }
        emitEpilogue();
        terminate("ret " + LlvmTypeMapper.outcomeType(result)
            + " { i8* null }");
    }

    /**
     * The error-only return of a failure path: the C4 outcome built from the
     * carried {@code deal_error*} with no value slot read (C4). The aggregate
     * is the enclosing activation's own outcome shape — a function whose
     * declared result carries a value returns its typed outcome with the value
     * slot untouched — so the failure path of a {@code number},
     * {@code boolean}, nullish or {@code unknown} result is never spelled with
     * the error-only shape. The shape is derived from the activation the path
     * belongs to, never from emission order.
     */
    private void terminateFailure(String error) {
        String outcome = enclosingOutcomeType();
        String aggregate = "%failure" + (labelCounter++) + "v";
        emit(aggregate + " = insertvalue " + outcome
            + " zeroinitializer, i8* " + error + ", 0");
        terminate("ret " + outcome + " " + aggregate);
    }

    /**
     * The C4 outcome type of the activation being emitted: the lowered
     * function's declared result for a generated function, and the error-only
     * outcome of the module-initialization and entry-surface functions, which
     * carry no value slot at all (C2).
     */
    private String enclosingOutcomeType() {
        FunctionId active = functionId;
        if (active == null) {
            return "%struct.deal_outcome";
        }
        return LlvmTypeMapper.outcomeType(
            session.function(active).descriptor().returnType());
    }

    /** Leaves the body's root frame and pops its activation label. */
    private void emitEpilogue() {
        session.referenceRuntimeEntry("deal_root_frame_leave");
        emit("call void @deal_root_frame_leave()");
        session.referenceRuntimeEntry("deal_call_frame_pop");
        emit("call void @deal_call_frame_pop()");
    }

    // =========================================================================
    // One unit initialization function
    // =========================================================================

    /**
     * Emits one unit's module-initialization function: the recorded
     * {@code MODULE_INIT} state machine (uninitialized → initializing →
     * initialized), its init block, and the successful publication of the
     * module record. A second call observes the initialized state and
     * returns success without re-running user code.
     */
    String emitUnitInit(ModuleId module) {
        this.functionId = null;
        LoweredModuleUnit initUnit = session.unitOf(module);
        long index = session.moduleIndex(module);
        String record = "@" + LlvmData.moduleRecord(index);
        currentDebugScope = -1;
        String statePointer = "%state" + index;
        String start = "Linit" + index;
        String finish = "Ldone" + index;
        LlvmData.StringConstant label = session.data.frameLabel(
            module.path() + "#init@deal_module_init_" + index);
        session.referenceRuntimeEntry("deal_call_frame_push");
        entryInit.add(LlvmIrPrinter.indent(1)
            + "call void @deal_call_frame_push("
            + session.data.operand(label) + ")");
        enterRootFrame();
        entryInit.add(LlvmIrPrinter.indent(1) + statePointer
            + " = getelementptr %struct.deal_module, %struct.deal_module* "
            + record + ", i32 0, i32 1");
        String failurePointer = "%failure" + index;
        entryInit.add(LlvmIrPrinter.indent(1) + failurePointer
            + " = getelementptr %struct.deal_module, %struct.deal_module* "
            + record + ", i32 0, i32 4");
        String sticky = "Lsticky" + index;
        entryInit.add(LlvmIrPrinter.indent(1) + "%st" + index
            + " = load i32, i32* " + statePointer);
        entryInit.add(LlvmIrPrinter.indent(1) + "%ready" + index
            + " = icmp eq i32 %st" + index + ", 2");
        entryInit.add(LlvmIrPrinter.indent(1) + "br i1 %ready" + index
            + ", label %" + finish + ", label %maybe" + index);
        entryInit.add("maybe" + index + ":");
        entryInit.add(LlvmIrPrinter.indent(1) + "%failed" + index
            + " = icmp eq i32 %st" + index + ", 3");
        entryInit.add(LlvmIrPrinter.indent(1) + "br i1 %failed" + index
            + ", label %" + sticky + ", label %" + start);
        entryInit.add(sticky + ":");
        entryInit.add(LlvmIrPrinter.indent(1) + "%sticky" + index
            + " = load i8*, i8** " + failurePointer);
        entryInit.add(LlvmIrPrinter.indent(1) + "%stickyresult" + index
            + " = insertvalue %struct.deal_outcome zeroinitializer, i8*"
            + " %sticky" + index + ", 0");
        entryInit.add(LlvmIrPrinter.indent(1)
            + "call void @deal_root_frame_leave()");
        entryInit.add(LlvmIrPrinter.indent(1)
            + "call void @deal_call_frame_pop()");
        entryInit.add(LlvmIrPrinter.indent(1)
            + "ret %struct.deal_outcome %stickyresult" + index);
        text.append(start).append(":\n");
        terminated = false;
        emit("store i32 1, i32* " + statePointer);
        emitStdlibCarriers(initUnit);
        String failureHandler = "Linitfail" + index;
        String pending = newAlloca("i8*", "initfailure");
        handlers.push(new FailureHandler(failureHandler, pending));
        emit("br label %" + blockLabel(initUnit.moduleInit().initBlock()));
        terminated = true;
        emitBlock(initUnit.moduleInit().initBlock(), finish);
        handlers.pop();
        startSegment(failureHandler);
        String carried = load(null, "i8*", pending);
        emit("store i8* " + carried + ", i8** " + failurePointer);
        emit("store i32 3, i32* " + statePointer);
        emitEpilogue();
        String failureAggregate = "%initerrorresult" + index;
        emit(failureAggregate + " = insertvalue %struct.deal_outcome"
            + " zeroinitializer, i8* " + carried + ", 0");
        terminate("ret %struct.deal_outcome " + failureAggregate);
        startSegment(finish);
        emit("store i32 2, i32* " + statePointer);
        emitEpilogue();
        terminate("ret %struct.deal_outcome { i8* null }");
        StringBuilder out = new StringBuilder();
        out.append("define internal %struct.deal_outcome @deal_module_init_")
            .append(index).append("() {\nLentry:\n");
        for (String alloca : allocas) {
            out.append(LlvmIrPrinter.indent(1)).append(alloca).append('\n');
        }
        patchRootEnter();
        for (String line : entryInit) {
            out.append(line).append('\n');
        }
        out.append(text);
        out.append("}\n");
        return out.toString();
    }

    // =========================================================================
    // Block and segment plumbing
    // =========================================================================

    private String blockLabel(BlockId block) {
        return blockLabels.computeIfAbsent(block.id(),
            id -> "Lb" + id);
    }

    private String newLabel(String prefix) {
        labelCounter++;
        return prefix + labelCounter;
    }

    /** Opens one label as the current block, branching to it when needed. */
    private void startSegment(String label) {
        if (!terminated) {
            emit("br label %" + label);
        }
        terminated = false;
        if (text.isEmpty()) {
            // The assembler branches from {@code Lentry} into the first
            // emitted block, so that block always has a predecessor.
            entryLabel = label;
        }
        segmentLabel = label;
        text.append(label).append(":\n");
    }

    /**
     * True when some already-emitted terminator names {@code label}: the
     * emitted CFG, not a private copy of the producer's block-exit summary,
     * decides whether a join has a predecessor. A {@code BRANCH}/{@code
     * TRY_CATCH} whose every recorded child path returns or throws leaves its
     * join without a predecessor, so the tail after it is unreachable and the
     * activation has no fall-through exit to render (C4, {@code OpRendering}).
     */
    private boolean hasPredecessor(String label) {
        String needle = "label %" + label;
        int index = text.indexOf(needle);
        while (index >= 0) {
            int after = index + needle.length();
            if (after >= text.length() || !isLabelCharacter(text.charAt(after))) {
                return true;
            }
            index = text.indexOf(needle, index + 1);
        }
        return false;
    }

    /**
     * The literal boolean of one condition value: its recorded producer is a
     * boolean constant. An empty result means the value is not statically
     * decidable here, so the branch is emitted with its condition.
     */
    private Optional<Boolean> constantBoolean(ValueId value) {
        SemanticOp producer = session.producerOf(value);
        if (producer != null
                && producer.payload() instanceof KindPayload.ConstPayload constant
                && constant.value() instanceof ScalarValue.Boolean bool) {
            return Optional.of(bool.value());
        }
        return Optional.empty();
    }

    private static boolean isLabelCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_'
            || character == '.';
    }

    private void terminate(String terminator) {
        emit(terminator);
        terminated = true;
    }

    private void emit(String instruction) {
        text.append(LlvmIrPrinter.indent(1)).append(instruction).append('\n');
    }

    /**
     * Emits one block: its ops in membership order, then a branch to
     * {@code next} (or the function epilogue when {@code next} is
     * {@code null}).
     */
    private void emitBlock(BlockId block, String next) {
        String label = blockLabel(block);
        startSegment(label);
        for (SemanticOp op : session.memberOps(block)) {
            if (session.isSkipped(op.opId())) {
                continue;
            }
            if (terminated) {
                startSegment(newLabel("d"));
            }
            emitOp(op);
        }
        if (!terminated) {
            if (next == null && !segmentLabel.equals(entryLabel)
                    && !hasPredecessor(segmentLabel)) {
                // Every recorded path of the tail returned, threw or
                // transferred, so this block has no predecessor: the
                // activation has no fall-through exit and the declared
                // result needs no epilogue return (the C4 explicit
                // propagation has already been rendered on each path).
                terminate("unreachable");
                return;
            }
            if (next == null) {
                fallsThrough = true;
            }
            terminate("br label %" + (next == null
                ? functionEndLabel() : next));
        }
    }

    // =========================================================================
    // Op rendering
    // =========================================================================

    private void emitOp(SemanticOp op) {
        LlvmStageClassifier.rejectReason(unit, op).ifPresent(reason -> {
            throw new deal.codegen.SharedEmissionFailure(
                "LlvmSemanticEmitter rejects the op " + op.kind() + " of "
                    + op.opId() + ": " + reason, op.origin().span().range());
        });
        recordOrigin(op);
        switch (op.kind()) {
            case CONST -> emitConst(op);
            case UNARY -> emitUnary(op);
            case BINARY -> emitBinary(op);
            case STRING_CONCAT -> emitStringConcat(op);
            case ARRAY_NEW -> emitArrayNew(op);
            case TABLE_NEW -> emitTableNew(op);
            case ARRAY_LENGTH -> emitArrayLength(op);
            case MEMBER_READ -> emitMemberRead(op);
            case MEMBER_WRITE -> emitMemberWrite(op);
            case MEMBER_DELETE -> emitMemberDelete(op);
            case INDEX_NORMALIZE -> emitIndexNormalize(op);
            case INDEX_READ -> emitIndexRead(op);
            case INDEX_WRITE -> emitIndexWrite(op);
            case INDEX_DELETE -> emitIndexDelete(op);
            case HAS_FIELD -> emitHasField(op);
            case FOR_EACH -> emitForEach(op);
            case CLASS_NEW -> emitClassNew(op);
            case FIELD_READ -> emitFieldRead(op);
            case FIELD_WRITE -> emitFieldWrite(op);
            case FIELD_DELETE -> emitFieldDelete(op);
            case JSON_FROM_CLASS -> emitJsonFromClass(op);
            case JSON_TO_CLASS -> emitJsonToClass(op);
            case BINDING_ALLOC -> emitBindingAlloc(op);
            case ENTRY_INVOKE -> {
                // Structural: the entry delegation is
                // DEAL_PROJECT_RUN_MAIN's own call.
            }
            case ASSIGN, DELETE -> {
                // The committed value child already published the result
                // with its own (original) static type and value; a failed
                // store transferred before the commit, leaving the target
                // unwritten and the expression without a result (C5).
            }
            case DISCARD -> {
                // The discarded value's effects already completed; the
                // intentional discard leaves no pending error state (C4).
            }
            case BOUNDARY -> emitBoundary(op);
            case MODULE_INIT -> {
                // The module-init state machine wraps the init block (C2).
            }
            case EXPORT_PUBLISH -> emitExportPublish(op);
            case RECURSIVE_GROUP_INIT -> emitRecursiveGroupInit(op);
            case FUNCTION_ADAPT -> emitFunctionAdapt(op);
            case STDLIB_CALL -> emitStdlibCall(op);
            case ASYNC_START -> emitAsyncStart(op);
            case AWAIT -> emitAwait(op);
            case BINDING_INIT, BINDING_STORE -> emitBindingWrite(op);
            case BINDING_LOAD -> emitBindingLoad(op);
            case MODULE_IMPORT -> emitModuleImport(op);
            case CLOSURE_NEW -> emitClosureNew(op);
            case EXPORT_READ -> emitExportRead(op);
            case CALL -> emitCall(op);
            case BRANCH -> emitBranch(op);
            case LOOP -> emitLoop(op);
            case TRY_CATCH -> emitTryCatch(op);
            case THROW -> emitThrow(op);
            case RETURN -> emitReturn(op);
            case BREAK, CONTINUE -> emitBreakContinue(op,
                op.kind() == SemanticOpKind.BREAK);
            default -> throw new deal.codegen.SharedEmissionFailure(
                "LlvmSemanticEmitter rejects the op kind " + op.kind(),
                op.origin().span().range());
        }
    }

    /** Records the op's own origin as one emitted {@code deal_origin} value. */
    private void recordOrigin(SemanticOp op) {
        SourceOrigin origin = op.origin();
        session.data.origin(session.displayNameOfSource(origin.sourceId()),
            Math.max(1, origin.span().startLine()),
            Math.max(1, origin.span().startColumn()));
    }

    /** The pointer value of one op's own {@code deal_origin} record. */
    private String originValue(SemanticOp op) {
        SourceOrigin origin = op.origin();
        return "@" + session.data.origin(
            session.displayNameOfSource(origin.sourceId()),
            Math.max(1, origin.span().startLine()),
            Math.max(1, origin.span().startColumn()));
    }

    private String debugAttachment(SemanticOp op) {
        if (currentDebugScope < 0) {
            return "";
        }
        SourceOrigin origin = op.origin();
        int location = session.debug.location(
            Math.max(1, origin.span().startLine()),
            Math.max(1, origin.span().startColumn()), currentDebugScope);
        return ", " + LlvmDebugInfo.attach(location);
    }

    // =========================================================================
    // Constants
    // =========================================================================

    private void emitConst(SemanticOp op) {
        KindPayload.ConstPayload payload = (KindPayload.ConstPayload) op.payload();
        Optional<String> representation = resultRepresentation(op);
        if (representation.isEmpty()) {
            // The null/undefined singletons carry no data slot; a consumer
            // materializes the distinct singleton it declares (C4).
            return;
        }
        String type = representation.get();
        if (payload.value() instanceof ScalarValue.String text) {
            // A string literal is immutable UTF-8 static data copied into one
            // runtime string (C3/R2, `llvm-native-codegen-cfg-ssa` C9): the
            // emitted constant carries the exact bytes and the entry copies
            // them, so no emitted value aliases read-only module data.
            session.referenceRuntimeEntry("deal_text_new");
            LlvmData.StringConstant constant = session.data.intern(text.value());
            String built = emitTemporary(op, "call " + LlvmTypeMapper.STRING
                + " @deal_text_new(" + session.data.operand(constant)
                + ", i64 " + session.data.byteCount(constant) + ")");
            storeResult(op, type, built);
            return;
        }
        String literal = switch (payload.value()) {
            case ScalarValue.Number number ->
                LlvmIrPrinter.doubleLiteral(number.value());
            case ScalarValue.Boolean value -> value.value() ? "true" : "false";
            default -> throw new IllegalStateException(
                "the constant " + payload.value() + " carries no representation");
        };
        storeResult(op, type, literal);
    }

    // =========================================================================
    // Unary selectors (the closed C6 table)
    // =========================================================================

    private void emitUnary(SemanticOp op) {
        KindPayload.UnaryPayload payload = (KindPayload.UnaryPayload) op.payload();
        LlvmSelectorArms.UnaryArm arm =
            LlvmSelectorArms.unary(payload.selector());
        RuntimeDescriptor operandDescriptor = op.operandTypes().get(0);
        ValueId input = op.operands().get(0);
        String result = switch (arm.form()) {
            case XOR_TRUE -> emitTemporary(op, "xor i1 "
                + loadRepresentation(input, operandDescriptor, op) + ", true");
            case FNEG -> emitTemporary(op, "fneg double "
                + loadRepresentation(input, operandDescriptor, op));
            case TYPE_NAME -> emitTypeName(op, input, operandDescriptor);
            case CLASS_TAG -> emitInstanceOf(op, payload, input,
                operandDescriptor);
        };
        storeResult(op, resultRepresentation(op).orElseThrow(), result);
    }

    /**
     * {@code typeof}: the six-name projection, one immutable string per name
     * (C6). The operand's own representation is boxed into the persistent
     * tagged datum the runtime classifies.
     */
    private String emitTypeName(SemanticOp op, ValueId input,
                                RuntimeDescriptor operandDescriptor) {
        session.referenceRuntimeEntry("deal_descriptor_type_name");
        String datum = LlvmTypeMapper.representationOf(operandDescriptor).isPresent()
            ? emitDatum(operandDescriptor,
                loadRepresentation(input, operandDescriptor, op), op)
            : emitDatum(operandDescriptor, "", op);
        String slot = newAlloca("%struct.deal_data", "datum");
        emit("store %struct.deal_data " + datum + ", %struct.deal_data* " + slot);
        return emitTemporary(op, "call %struct.deal_string*"
            + " @deal_descriptor_type_name(%struct.deal_data* " + slot + ")");
    }

    /**
     * {@code instanceof C}: the nominal tag test (C6). The builtin
     * {@code Error} class is the one class identity whose tag the persistent
     * datum carries without an emitted class layout; every other class target
     * belongs to the data/class stage and is rejected by the classifier. A
     * data value of any other static type is never an instance, and a record
     * is never retagged.
     */
    private String emitInstanceOf(SemanticOp op,
                                  KindPayload.UnaryPayload payload,
                                  ValueId input,
                                  RuntimeDescriptor operandDescriptor) {
        RuntimeDescriptor target = payload.target();
        if (!(target instanceof RuntimeDescriptor.Class targetClass)) {
            throw new IllegalStateException("INSTANCEOF without a class target");
        }
        if (operandDescriptor instanceof RuntimeDescriptor.Class classType) {
            return classType.classId().equals(targetClass.classId())
                ? "true" : "false";
        }
        if (!(operandDescriptor instanceof RuntimeDescriptor.Unknown)) {
            // A number, boolean, string, nullish, container or function value
            // is never a nominal class instance (R2).
            return "false";
        }
        String datum = loadRepresentation(input, operandDescriptor, op);
        String tag = emitTemporary(op, "extractvalue %struct.deal_data "
            + datum + ", 0");
        if (targetClass.classId().equals(deal.semantic.ir.ClassId.ERROR)) {
            return emitTemporary(op, "icmp eq i32 " + tag + ", "
                + LlvmTypeMapper.DATA_ERROR);
        }
        // A class datum carries no second classification: the instance's own
        // class atom (emitted per layout) decides the nominal test, and a datum
        // of another tag is never an instance (C6: no retagging).
        String slot = newAlloca("i1", "instanceof");
        emit("store i1 false, i1* " + slot);
        String isClass = emitTemporary(op, "icmp eq i32 " + tag + ", "
            + LlvmTypeMapper.DATA_CLASS);
        String check = newLabel("instanceof.check");
        String join = newLabel("instanceof.join");
        emit("br i1 " + isClass + ", label %" + check + ", label %" + join);
        terminated = true;
        startSegment(check);
        String reference = emitTemporary(op, "extractvalue "
            + LlvmTypeMapper.DATA + " " + datum + ", 3");
        String atom = emitTemporary(op, "load i8*, i8** "
            + emitTemporary(op, "bitcast i8* " + reference + " to i8**"));
        String same = emitTemporary(op, "call i1 @deal_text_equals(i8* " + atom
            + ", " + session.data.operand(session.data.intern(
                targetClass.classId().text())) + ")");
        emit("store i1 " + same + ", i1* " + slot);
        terminate("br label %" + join);
        startSegment(join);
        return load(op, "i1", slot);
    }

    // =========================================================================
    // Binary selectors (the closed C6 table)
    // =========================================================================

    private void emitBinary(SemanticOp op) {
        KindPayload.BinaryPayload payload = (KindPayload.BinaryPayload) op.payload();
        LlvmSelectorArms.BinaryArm arm =
            LlvmSelectorArms.binary(payload.selector());
        RuntimeDescriptor leftDescriptor = op.operandTypes().get(0);
        RuntimeDescriptor rightDescriptor = op.operandTypes().get(1);
        ValueId left = op.operands().get(0);
        ValueId right = op.operands().get(1);
        String result = switch (arm.form()) {
            case ARITHMETIC -> {
                String leftOperand = loadRepresentation(left, leftDescriptor, op);
                String rightOperand = loadRepresentation(right, rightDescriptor, op);
                yield emitTemporary(op, arm.mnemonic() + " double "
                    + leftOperand + ", " + rightOperand);
            }
            case IEEE_PREDICATE -> {
                String leftOperand = loadRepresentation(left, leftDescriptor, op);
                String rightOperand = loadRepresentation(right, rightDescriptor, op);
                yield emitTemporary(op, "fcmp " + arm.predicate() + " double "
                    + leftOperand + ", " + rightOperand);
            }
            case BOOLEAN_BYTES -> {
                String leftOperand = loadRepresentation(left, leftDescriptor, op);
                String rightOperand = loadRepresentation(right, rightDescriptor, op);
                yield emitTemporary(op, "icmp " + arm.predicate() + " i1 "
                    + leftOperand + ", " + rightOperand);
            }
            case TEXT_COMPARE -> emitTextCompare(op, arm, left, right);
            case SINGLETON_TAGS -> emitSingletonTags(op, arm, leftDescriptor,
                rightDescriptor);
            case DATA_SAME -> emitDataSame(op, arm, left, right);
            case TAG_TEST -> emitTagTest(op, arm, payload, leftDescriptor,
                rightDescriptor, left, right);
            case IDENTITY -> {
                // Managed identity is compared on the byte pointer every C3
                // reference representation reduces to (C6: no structural
                // equality and no wrapper-slot identity).
                String leftOperand = identityPointer(
                    loadRepresentation(left, leftDescriptor, op),
                    leftDescriptor, op);
                String rightOperand = identityPointer(
                    loadRepresentation(right, rightDescriptor, op),
                    rightDescriptor, op);
                yield emitTemporary(op, "icmp " + arm.predicate() + " i8* "
                    + leftOperand + ", " + rightOperand);
            }
        };
        storeResult(op, resultRepresentation(op).orElseThrow(), result);
    }

    /** {@code STRING_*}: the one {@code deal_text_compare} authority (R8). */
    /** One operand as the byte pointer the managed identity compares (C6). */
    private String identityPointer(String operand, RuntimeDescriptor descriptor,
                                   SemanticOp op) {
        if (descriptor instanceof RuntimeDescriptor.Func) {
            return emitTemporary(op, "bitcast " + LlvmTypeMapper.FN_POINTER + " "
                + operand + " to i8*");
        }
        if (LlvmTypeMapper.isManagedPointer(descriptor)) {
            // Every managed representation reduces to the one byte pointer
            // managed identity compares: a string, an array, a record or a
            // class instance (C6: no structural equality and no wrapper-slot
            // identity).
            return emitTemporary(op, "bitcast "
                + stagedRepresentation(descriptor) + " " + operand + " to i8*");
        }
        return operand;
    }

    private String emitTextCompare(SemanticOp op, LlvmSelectorArms.BinaryArm arm,
                                   ValueId left, ValueId right) {
        session.referenceRuntimeEntry("deal_text_compare");
        String leftOperand = loadRepresentation(left,
            op.operandTypes().get(0), op);
        String rightOperand = loadRepresentation(right,
            op.operandTypes().get(1), op);
        String comparison = emitTemporary(op, "call i32 @deal_text_compare("
            + LlvmTypeMapper.STRING + " " + leftOperand + ", "
            + LlvmTypeMapper.STRING + " " + rightOperand + ")");
        return emitTemporary(op, "icmp " + LlvmSelectorArms.resultPredicate(arm)
            + " i32 " + comparison + ", "
            + LlvmSelectorArms.resultOperand(arm));
    }

    /**
     * {@code NULL_EQ}/{@code NULL_NE} and {@code UNDEFINED_EQ}/{@code
     * UNDEFINED_NE}: the exact singleton tags. Null equals only null and
     * undefined only undefined, so the two singletons are compared by identity
     * and never collapsed (C6).
     */
    private String emitSingletonTags(SemanticOp op,
                                     LlvmSelectorArms.BinaryArm arm,
                                     RuntimeDescriptor leftDescriptor,
                                     RuntimeDescriptor rightDescriptor) {
        boolean nullSingleton = leftDescriptor instanceof RuntimeDescriptor.Null
            || rightDescriptor instanceof RuntimeDescriptor.Null;
        String leftOperand = loadSingleton(nullSingleton, op);
        String rightOperand = loadSingleton(nullSingleton, op);
        return emitTemporary(op, "icmp " + arm.predicate() + " i8* "
            + leftOperand + ", " + rightOperand);
    }

    /**
     * {@code UNKNOWN_EQ}/{@code UNKNOWN_NE}: the closed data {@code ===}
     * through {@code deal_data_same}, whose result is {@code 1} exactly when
     * the relation holds. The arm's truth encoding therefore inverts the
     * comparison predicate: {@code UNKNOWN_EQ} reads {@code icmp ne i32 %r, 0}
     * and {@code UNKNOWN_NE} reads {@code icmp eq i32 %r, 0}, so null equals
     * only null and undefined only undefined (C6).
     */
    private String emitDataSame(SemanticOp op, LlvmSelectorArms.BinaryArm arm,
                               ValueId left, ValueId right) {
        session.referenceRuntimeEntry("deal_data_same");
        String leftSlot = valueSlot(left, LlvmTypeMapper.DATA);
        String rightSlot = valueSlot(right, LlvmTypeMapper.DATA);
        String comparison = emitTemporary(op, "call i32 @deal_data_same("
            + LlvmTypeMapper.DATA + "* " + leftSlot + ", "
            + LlvmTypeMapper.DATA + "* " + rightSlot + ")");
        return emitTemporary(op, "icmp " + LlvmSelectorArms.resultPredicate(arm)
            + " i32 " + comparison + ", "
            + LlvmSelectorArms.resultOperand(arm));
    }

    /**
     * The nullish tag tests of C6: {@code NULLABLE_NULL_*} (the named side is
     * language null), {@code NULLABLE_UNDEFINED_*} (the named side is
     * undefined) and {@code NULLABLE_EQ}/{@code NULLABLE_NE} (equal nullable
     * types, whose nullish tags are compared first and whose non-nullish base
     * values use their descriptor's own equality).
     */
    private String emitTagTest(SemanticOp op, LlvmSelectorArms.BinaryArm arm,
                               KindPayload.BinaryPayload payload,
                               RuntimeDescriptor leftDescriptor,
                               RuntimeDescriptor rightDescriptor,
                               ValueId left, ValueId right) {
        NullableSide side = payload.side();
        boolean nullArm = payload.selector()
            == deal.semantic.ir.BinarySelector.NULLABLE_NULL_EQ
            || payload.selector() == deal.semantic.ir.BinarySelector.NULLABLE_NULL_NE;
        boolean undefinedArm = payload.selector()
            == deal.semantic.ir.BinarySelector.NULLABLE_UNDEFINED_EQ
            || payload.selector()
                == deal.semantic.ir.BinarySelector.NULLABLE_UNDEFINED_NE;
        if (nullArm || undefinedArm) {
            boolean namedLeft = side != NullableSide.RIGHT;
            RuntimeDescriptor named = namedLeft ? leftDescriptor : rightDescriptor;
            ValueId value = namedLeft ? left : right;
            String operand = loadNamedNullish(value, named, op);
            if (named instanceof RuntimeDescriptor.Unknown) {
                String tag = emitTemporary(op, "extractvalue "
                    + LlvmTypeMapper.DATA + " " + operand + ", 0");
                return emitTemporary(op, "icmp " + arm.predicate() + " i32 "
                    + tag + ", " + (nullArm ? LlvmTypeMapper.DATA_NULL
                        : LlvmTypeMapper.DATA_UNDEFINED));
            }
            if (named instanceof RuntimeDescriptor.Nullable nullable
                    && LlvmTypeMapper.representationOf(nullable).orElseThrow()
                        .equals(LlvmTypeMapper.REFERENCE_NULLISH)) {
                String expected = loadSingleton(nullArm, op);
                return emitTemporary(op, "icmp " + arm.predicate() + " i8* "
                    + operand + ", " + expected);
            }
            RuntimeDescriptor.Nullable nullable =
                (RuntimeDescriptor.Nullable) named;
            String tag = emitTemporary(op, "extractvalue "
                + LlvmTypeMapper.representationOf(nullable).orElseThrow() + " " + operand
                + ", 0");
            return emitTemporary(op, "icmp " + arm.predicate() + " i32 "
                + tag + ", " + (nullArm ? LlvmTypeMapper.NULLISH_NULL
                    : LlvmTypeMapper.NULLISH_UNDEFINED));
        }
        return emitNullableEquality(op, arm, payload, leftDescriptor,
            rightDescriptor, left, right);
    }

    /**
     * {@code NULLABLE_EQ}/{@code NULLABLE_NE}: the nullish tags are compared
     * first — null and undefined never satisfy the relation of each other —
     * and two base values use their descriptor's equality.
     */
    private String emitNullableEquality(SemanticOp op,
                                        LlvmSelectorArms.BinaryArm arm,
                                        KindPayload.BinaryPayload payload,
                                        RuntimeDescriptor leftDescriptor,
                                        RuntimeDescriptor rightDescriptor,
                                        ValueId left, ValueId right) {
        RuntimeDescriptor inner = payload.innerDescriptor();
        String leftRepresentation = stagedRepresentation(leftDescriptor);
        String rightRepresentation = stagedRepresentation(rightDescriptor);
        String leftOperand = loadRepresentation(left, leftDescriptor, op);
        String rightOperand = loadRepresentation(right, rightDescriptor, op);
        String slot = newAlloca("i1", "nullisheq");
        if (leftRepresentation.equals(LlvmTypeMapper.REFERENCE_NULLISH)
                && rightRepresentation.equals(LlvmTypeMapper.REFERENCE_NULLISH)) {
            return emitReferenceNullableEquality(op, arm, inner, leftOperand,
                rightOperand, slot);
        }
        String leftTag = emitTemporary(op,
            "extractvalue " + leftRepresentation + " " + leftOperand + ", 0");
        String rightTag = emitTemporary(op,
            "extractvalue " + rightRepresentation + " " + rightOperand + ", 0");
        String leftNullish = emitTemporary(op, "icmp ne i32 " + leftTag + ", "
            + LlvmTypeMapper.NULLISH_BASE);
        String rightNullish = emitTemporary(op, "icmp ne i32 " + rightTag + ", "
            + LlvmTypeMapper.NULLISH_BASE);
        String anyNullish = emitTemporary(op, "or i1 " + leftNullish + ", "
            + rightNullish);
        String nullishLabel = newLabel("nullishtag");
        String baseLabel = newLabel("nullishbase");
        String joinLabel = newLabel("nullishjoin");
        emit("br i1 " + anyNullish + ", label %" + nullishLabel
            + ", label %" + baseLabel);
        terminated = true;
        startSegment(nullishLabel);
        String tagsEqual = emitTemporary(op, "icmp " + arm.predicate()
            + " i32 " + leftTag + ", " + rightTag);
        emit("store i1 " + tagsEqual + ", i1* " + slot);
        terminate("br label %" + joinLabel);
        startSegment(baseLabel);
        String leftBase = emitTemporary(op, "extractvalue " + leftRepresentation
            + " " + leftOperand + ", 1");
        String rightBase = emitTemporary(op, "extractvalue " + rightRepresentation
            + " " + rightOperand + ", 1");
        String baseEqual = emitBaseEquality(op, arm, inner, leftBase, rightBase);
        emit("store i1 " + baseEqual + ", i1* " + slot);
        terminate("br label %" + joinLabel);
        startSegment(joinLabel);
        return emitTemporary(op, "load i1, i1* " + slot);
    }

    /** One base-value equality of a scalar nullish inner descriptor. */
    private String emitBaseEquality(SemanticOp op,
                                    LlvmSelectorArms.BinaryArm arm,
                                    RuntimeDescriptor inner,
                                    String left, String right) {
        return switch (inner) {
            case RuntimeDescriptor.Number ignored -> emitTemporary(op,
                "fcmp " + ("eq".equals(arm.predicate()) ? "oeq" : "une")
                    + " double " + left + ", " + right);
            case RuntimeDescriptor.Boolean ignored -> emitTemporary(op,
                "icmp " + arm.predicate() + " i1 " + left + ", " + right);
            case RuntimeDescriptor.String ignored -> {
                session.referenceRuntimeEntry("deal_text_compare");
                String comparison = emitTemporary(op,
                    "call i32 @deal_text_compare(" + LlvmTypeMapper.STRING
                        + " " + left + ", " + LlvmTypeMapper.STRING + " "
                        + right + ")");
                yield emitTemporary(op, "icmp " + arm.predicate() + " i32 "
                    + comparison + ", 0");
            }
            case RuntimeDescriptor.Func ignored -> emitTemporary(op,
                "icmp " + arm.predicate() + " i8* "
                    + identityPointer(left, inner, op) + ", "
                    + identityPointer(right, inner, op));
            case RuntimeDescriptor.Array _,
                 RuntimeDescriptor.Table _,
                 RuntimeDescriptor.Class _ ->
                // A reference base's nullish equality is managed identity: the
                // base pointers are compared, never their contents (C6).
                emitTemporary(op, "icmp " + arm.predicate() + " i8* "
                    + referenceToBytes(op, inner, left) + ", "
                    + referenceToBytes(op, inner, right));
            default -> throw new IllegalStateException(
                "the nullish base '" + inner.canonicalSpecText()
                    + "' has no staged equality");
        };
    }

    /** One base reference operand as the byte pointer it compares as. */
    private String referenceToBytes(SemanticOp op, RuntimeDescriptor inner,
                                    String operand) {
        String representation = stagedRepresentation(inner);
        if (representation.equals("i8*")) {
            return operand;
        }
        return emitTemporary(op, "bitcast " + representation + " " + operand
            + " to i8*");
    }

    /**
     * {@code NULLABLE_EQ}/{@code NULLABLE_NE} over reference bases: the two
     * nullish singletons compare by identity and never with each other, and
     * two base values use their descriptor's own equality (C6).
     */
    private String emitReferenceNullableEquality(
            SemanticOp op, LlvmSelectorArms.BinaryArm arm,
            RuntimeDescriptor inner, String left, String right, String slot) {
        session.referenceRuntimeEntry("deal_value_is_null");
        session.referenceRuntimeEntry("deal_value_is_undefined");
        String nullLabel = newLabel("nullable.null");
        String baseLabel = newLabel("nullable.base");
        String joinLabel = newLabel("nullable.join");
        String leftNull = emitTemporary(op,
            "call i32 @deal_value_is_null(i8* " + left + ")");
        String leftUndefined = emitTemporary(op,
            "call i32 @deal_value_is_undefined(i8* " + left + ")");
        String rightNull = emitTemporary(op,
            "call i32 @deal_value_is_null(i8* " + right + ")");
        String rightUndefined = emitTemporary(op,
            "call i32 @deal_value_is_undefined(i8* " + right + ")");
        String leftNullish = emitTemporary(op,
            "or i32 " + leftNull + ", " + leftUndefined);
        String rightNullish = emitTemporary(op,
            "or i32 " + rightNull + ", " + rightUndefined);
        String anyNullish = emitTemporary(op, "or i32 " + leftNullish + ", "
            + rightNullish);
        String nullish = emitTemporary(op, "icmp ne i32 " + anyNullish + ", 0");
        emit("br i1 " + nullish + ", label %" + nullLabel + ", label %"
            + baseLabel);
        terminated = true;
        startSegment(nullLabel);
        String same = emitTemporary(op, "icmp " + arm.predicate() + " i8* "
            + left + ", " + right);
        emit("store i1 " + same + ", i1* " + slot);
        terminate("br label %" + joinLabel);
        startSegment(baseLabel);
        String baseEqual = emitBaseEquality(op, arm, inner,
            castBase(inner, left, op), castBase(inner, right, op));
        emit("store i1 " + baseEqual + ", i1* " + slot);
        terminate("br label %" + joinLabel);
        startSegment(joinLabel);
        return emitTemporary(op, "load i1, i1* " + slot);
    }

    /** The base-typed spelling of one reference-nullish payload. */
    private String castBase(RuntimeDescriptor inner, String pointer,
                            SemanticOp op) {
        if (LlvmTypeMapper.isManagedPointer(inner)) {
            return emitTemporary(op, "bitcast i8* " + pointer + " to "
                + LlvmTypeMapper.representationOf(inner).orElseThrow());
        }
        if (inner instanceof RuntimeDescriptor.Func) {
            return emitTemporary(op, "bitcast i8* " + pointer + " to "
                + LlvmTypeMapper.FN_POINTER);
        }
        throw new IllegalStateException("the nullish base '"
            + inner.canonicalSpecText() + "' has no staged representation");
    }

    /** Loads one operand as the representation of its recorded static type. */
    private String loadRepresentation(ValueId value, RuntimeDescriptor descriptor,
                                      SemanticOp op) {
        if (LlvmTypeMapper.isStaticSingleton(descriptor)) {
            throw new IllegalStateException("the value " + value
                + " carries no data representation: "
                + descriptor.canonicalSpecText());
        }
        String representation = stagedRepresentation(descriptor);
        return loadValue(value, representation, op);
    }

    /**
     * Loads one nullish operand: the singleton pointer for a static singleton,
     * the declared representation otherwise.
     */
    private String loadNamedNullish(ValueId value, RuntimeDescriptor descriptor,
                                    SemanticOp op) {
        if (descriptor instanceof RuntimeDescriptor.Null) {
            return loadSingleton(true, op);
        }
        if (descriptor instanceof RuntimeDescriptor.Undefined) {
            return loadSingleton(false, op);
        }
        return loadRepresentation(value, descriptor, op);
    }

    // =========================================================================
    // Assignment storage and conversions
    // =========================================================================

    private void emitBindingLoad(SemanticOp op) {
        KindPayload.BindingLoadPayload payload =
            (KindPayload.BindingLoadPayload) op.payload();
        Optional<String> representation = session.bindingRepresentation(unit,
            payload.binding(), payload.generation());
        if (representation.isEmpty()) {
            return;
        }
        if (session.cellBinding(unit, payload.binding(), payload.generation())) {
            RuntimeDescriptor declared = session.bindingType(unit,
                payload.binding(), payload.generation());
            if (declared == null) {
                throw new IllegalStateException("the captured binding "
                    + payload.binding() + " carries no declared type");
            }
            session.referenceRuntimeEntry("deal_cell_read_data");
            String cell = load(op, "i8*", bindingSlot(payload.binding(),
                payload.generation(), "i8*"));
            String datum = callRecord(op, LlvmTypeMapper.DATA,
                "deal_cell_read_data", "i8* " + cell);
            RuntimeDescriptor cellReadType = op.resultType()
                instanceof RuntimeDescriptor descriptor ? descriptor : declared;
            if (declared.equals(cellReadType)) {
                storeResult(op, representation.get(),
                    datumToRepresentation(declared, datum, op));
                return;
            }
            // A narrowed read of a cell sees the refinement the frontend
            // proved (R4): the cell already holds the persistent datum, so the
            // refinement validates it and projects the refined representation
            // out of it, exactly as the direct binding's read does.
            storeResult(op, stagedRepresentation(cellReadType),
                narrowedFromDatum(op, declared, cellReadType, datum));
            return;
        }
        String slot = bindingSlot(payload.binding(), payload.generation(),
            representation.get());
        String loaded = load(op, representation.get(), slot);
        RuntimeDescriptor declared = session.bindingType(unit,
            payload.binding(), payload.generation());
        RuntimeDescriptor loadedType = op.resultType()
            instanceof RuntimeDescriptor descriptor ? descriptor : declared;
        if (declared == null || loadedType == null
                || declared.equals(loadedType)) {
            storeResult(op, representation.get(), loaded);
            return;
        }
        // A narrowed read: the binding declares a wider type — a nullish form
        // or `unknown` — and this read observes the refinement the frontend
        // proved (R4). The read keeps the value the binding holds and projects
        // it onto the refined type's own representation; nothing is coerced,
        // retagged or replaced.
        storeResult(op, stagedRepresentation(loadedType),
            projectNarrowedRead(op, declared, loadedType, loaded));
    }

    /**
     * Projects one narrowed binding read onto the refined type's own
     * representation (C3). The binding's declared form is either the persistent
     * {@code unknown} datum or a nullish form; both carry the value in one
     * place — the datum itself, or the scalar tagged struct's {@code value}/
     * reference-nullish pointer — so the read goes through the one datum
     * authority: the held value is boxed where needed and the refined
     * representation is unboxed out of it. The refinement is optimistic across
     * calls and an indirect mutation can leave a declared member in the slot
     * (docs/spec-v1.5.md, narrowing invalidation), so the read first validates
     * the value it actually holds against the refined descriptor at its own
     * origin: a stale refinement fails with the declared E8001 arm there
     * instead of yielding a base value the binding never held. Every other
     * narrowed read is a producer fact this stage cannot render and fails
     * closed (C9).
     */
    private String projectNarrowedRead(SemanticOp op, RuntimeDescriptor declared,
                                       RuntimeDescriptor refined,
                                       String loaded) {
        boolean datum = declared instanceof RuntimeDescriptor.Unknown;
        boolean nullishBase = declared instanceof RuntimeDescriptor.Nullable nullable
            && LlvmTypeMapper.admits(nullable, refined);
        if (!datum && !nullishBase) {
            throw new deal.codegen.SharedEmissionFailure(
                "LlvmSemanticEmitter rejects the narrowed read " + op.opId()
                    + ": the declared type '" + declared.canonicalSpecText()
                    + "' does not refine to '" + refined.canonicalSpecText()
                    + "' (C9)",
                op.origin().span().range());
        }
        if (!LlvmTypeMapper.hasDatumForm(refined)) {
            throw new deal.codegen.SharedEmissionFailure(
                "LlvmSemanticEmitter rejects the narrowed read " + op.opId()
                    + ": the refined type '" + refined.canonicalSpecText()
                    + "' has no persistent data form (C9)",
                op.origin().span().range());
        }
        // The datum of the value the binding holds: an `unknown` binding stores
        // it directly, and a nullish binding's own form keeps a declared member
        // as its own singleton tag (R2).
        String held = datum ? loaded
            : LlvmDataValues.box(session, sink, declared, loaded);
        return narrowedFromDatum(op, declared, refined, held);
    }

    /**
     * Validates one held persistent datum against the refined descriptor at the
     * read's own origin and projects the refined representation out of it
     * (C3, R4). A stale refinement fails with the declared E8001 arm there
     * instead of yielding a base value the binding never held.
     */
    private String narrowedFromDatum(SemanticOp op, RuntimeDescriptor declared,
                                     RuntimeDescriptor refined, String held) {
        if (!LlvmTypeMapper.hasDatumForm(refined)) {
            throw new deal.codegen.SharedEmissionFailure(
                "LlvmSemanticEmitter rejects the narrowed read " + op.opId()
                    + ": the refined type '" + refined.canonicalSpecText()
                    + "' has no persistent data form (C9)",
                op.origin().span().range());
        }
        String slot = newAlloca("%struct.deal_check_input", "checked");
        emit("store %struct.deal_check_input "
            + checkInputRecordOf(op, declared, held)
            + ", %struct.deal_check_input* " + slot);
        emitCheckCall(op, refined, slot);
        return LlvmDataValues.unbox(session, sink, refined, held);
    }

    private void emitBindingWrite(SemanticOp op) {
        BindingId binding;
        long generation;
        ValueId value;
        if (op.payload() instanceof KindPayload.BindingInitPayload init) {
            binding = init.binding();
            generation = init.generation();
            value = init.value();
        } else {
            KindPayload.BindingStorePayload store =
                (KindPayload.BindingStorePayload) op.payload();
            binding = store.binding();
            generation = store.generation();
            value = store.value();
        }
        Optional<String> representation = session.bindingRepresentation(unit,
            binding, generation);
        if (representation.isEmpty()) {
            return;
        }
        RuntimeDescriptor declared = session.bindingType(unit, binding, generation);
        String stored = declared == null
            ? loadValue(value, representation.get(), op)
            : convert(value, descriptorOfValue(value), declared, op);
        if (session.cellBinding(unit, binding, generation)) {
            RuntimeDescriptor datumType = declared == null
                ? descriptorOfValue(value) : declared;
            String slot = bindingSlot(binding, generation, representation.get());
            String cell = load(op, "i8*", slot);
            String datum = emitDatum(datumType, stored, op);
            String datumSlot = newAlloca(LlvmTypeMapper.DATA, "cellstore");
            emit("store " + LlvmTypeMapper.DATA + " " + datum + ", "
                + LlvmTypeMapper.DATA + "* " + datumSlot);
            session.referenceRuntimeEntry("deal_cell_store_data");
            emit("call void @deal_cell_store_data(i8* " + cell + ", "
                + LlvmTypeMapper.DATA + "* " + datumSlot + ")"
                + debugAttachment(op));
            return;
        }
        String slot = bindingSlot(binding, generation, representation.get());
        emit("store " + representation.get() + " " + stored + ", "
            + representation.get() + "* " + slot);
        if (declared != null) {
            rootBindingSlot(binding, generation, declared, slot, op);
        }
    }

    /**
     * Converts one completed value from its recorded static descriptor to the
     * declared descriptor of the position it enters (C3): the singleton and
     * nullish forms are built from their own tags, a data value is boxed into
     * the persistent tagged datum, and a value already in the declared
     * representation is used unchanged. A conversion that would need a runtime
     * check is the assertion/container boundary's, never an implicit coercion.
     */
    private String convert(ValueId value, RuntimeDescriptor from,
                           RuntimeDescriptor to, SemanticOp op) {
        if (from.equals(to) && !LlvmTypeMapper.isStaticSingleton(from)) {
            return loadRepresentation(value, from, op);
        }
        String operand = LlvmTypeMapper.isStaticSingleton(from)
            ? null : loadRepresentation(value, from, op);
        return coerce(from, to, operand, op);
    }

    /**
     * Converts one already loaded operand from its recorded static descriptor
     * to the descriptor of the position it enters (C3): a data position boxes
     * its value into the persistent datum, a declared data value is projected
     * into its checked representation, and a nullish position builds its own
     * tagged or reference form. A conversion that would need a runtime check is
     * the assertion/container boundary's, never an implicit coercion.
     */
    private String coerce(RuntimeDescriptor from, RuntimeDescriptor to,
                          String operand, SemanticOp op) {
        if (from.equals(to) && !LlvmTypeMapper.isStaticSingleton(from)) {
            return operand;
        }
        // The declared position of a static singleton is that singleton: the
        // value carries no payload, so its storage is the distinct runtime
        // `null`/`undefined` pointer (C3).
        if (to instanceof RuntimeDescriptor.Null) {
            return loadSingleton(true, op);
        }
        if (to instanceof RuntimeDescriptor.Undefined) {
            return loadSingleton(false, op);
        }
        if (to instanceof RuntimeDescriptor.Unknown) {
            return emitDatum(from, operand, op);
        }
        if (from instanceof RuntimeDescriptor.Unknown) {
            return LlvmDataValues.unbox(session, sink, to, operand);
        }
        if (to instanceof RuntimeDescriptor.Nullable nullable) {
            return emitNullishFrom(from, nullable, operand, op);
        }
        // An unrenderable projection is one origin-bearing coverage failure
        // (C9): the production arm projects it as the single E6005 and
        // publishes nothing, never an origin-less internal error.
        throw new deal.codegen.SharedEmissionFailure(
            "LlvmSemanticEmitter rejects the coercion of " + op.opId()
                + ": the value of type '" + from.canonicalSpecText()
                + "' has no coercion into '" + to.canonicalSpecText() + "' (C9)",
            op.origin().span().range());
    }

    /**
     * Builds one nullish value of {@code target} from a static singleton, its
     * base type or the same nullish type (C3): a scalar base uses the tagged
     * struct, a reference base the pointer or the admitted singleton.
     */
    /**
     * Builds one nullish value of {@code target} from a static singleton, an
     * already loaded base operand or the same nullish type (C3): a scalar base
     * uses the tagged struct, a reference base the pointer or the admitted
     * singleton.
     */
    private String emitNullishFrom(RuntimeDescriptor from,
                                   RuntimeDescriptor.Nullable target,
                                   String operand, SemanticOp op) {
        String representation = LlvmTypeMapper.representationOf(target).orElseThrow(() ->
            new IllegalStateException("the nullish descriptor '"
                + target.canonicalSpecText() + "' has no staged"
                + " representation"));
        boolean reference = representation.equals(LlvmTypeMapper.REFERENCE_NULLISH);
        if (from instanceof RuntimeDescriptor.Null) {
            requireAdmitted(target, true, false, from);
            return reference ? loadSingleton(true, op)
                : taggedNullish(representation, LlvmTypeMapper.NULLISH_NULL,
                    null, op);
        }
        if (from instanceof RuntimeDescriptor.Undefined) {
            requireAdmitted(target, false, true, from);
            return reference ? loadSingleton(false, op)
                : taggedNullish(representation, LlvmTypeMapper.NULLISH_UNDEFINED,
                    null, op);
        }
        if (from instanceof RuntimeDescriptor.Nullable) {
            return operand;
        }
        if (!admittedBase(target, from)) {
            throw new IllegalStateException("the value's type '"
                + from.canonicalSpecText() + "' is not admitted by '"
                + target.canonicalSpecText() + "'");
        }
        if (reference) {
            String fromRepresentation = stagedRepresentation(from);
            return fromRepresentation.equals(LlvmTypeMapper.REFERENCE_NULLISH)
                ? operand
                : emitTemporary(op, "bitcast " + fromRepresentation + " "
                    + operand + " to i8*");
        }
        return taggedNullish(representation, LlvmTypeMapper.NULLISH_BASE,
            operand, op);
    }

    private static boolean admittedBase(RuntimeDescriptor.Nullable target,
                                        RuntimeDescriptor actual) {
        return LlvmTypeMapper.admits(target.inner(), actual);
    }

    private void requireAdmitted(RuntimeDescriptor.Nullable target, boolean nullish,
                                 boolean undefined, RuntimeDescriptor from) {
        boolean admitted = nullish ? LlvmTypeMapper.admitsNull(target) : undefined
            ? LlvmTypeMapper.admitsUndefined(target) : false;
        if (!admitted) {
            throw new IllegalStateException("the nullish descriptor '"
                + target.canonicalSpecText() + "' does not admit '"
                + from.canonicalSpecText() + "'");
        }
    }

    /** One {@code {tag, value}} scalar nullish value. */
    private String taggedNullish(String representation, int tag,
                                 String base, SemanticOp op) {
        String zero = representation.equals(LlvmTypeMapper.NULLISH_NUMBER)
            ? "double 0.0" : "i1 false";
        String literal = "{ i32 " + tag + ", " + zero + " }";
        if (base == null) {
            return literal;
        }
        return emitTemporary(op, "insertvalue " + representation + " "
            + literal + ", "
            + (representation.equals(LlvmTypeMapper.NULLISH_NUMBER)
                ? "double " : "i1 ") + base + ", 1");
    }

    /**
     * Boxes one completed value into the persistent tagged datum of an
     * {@code unknown} position (C3, R3). The one conversion authority is
     * {@link LlvmDataValues}: the emitted module constructs the structural
     * {@code deal_data} record directly — the header publishes its layout and
     * the emitted module owns these values — so no runtime constructor and no
     * allocation is involved, and the tag of a nullish operand is its own
     * stored value, so absence is never confused with a base value.
     */
    private String emitDatum(RuntimeDescriptor from, String operand,
                             SemanticOp op) {
        return LlvmDataValues.box(session, sink, from, operand);
    }

    /** The emitter's IR sink for the shared datum conversions. */
    private final LlvmDataValues.Sink sink = new LlvmDataValues.Sink() {
        @Override
        public void line(String text) {
            emit(text);
        }

        @Override
        public String temp(String instruction) {
            return emitTemporary(null, instruction);
        }
    };

    // =========================================================================
    // Assertions (the one rendered runtime check of this stage)
    // =========================================================================

    /**
     * The typed-boundary check ({@code llvm-native-runtime-value-model} R4/R5).
     * A boundary whose static operand type already proves the declared
     * descriptor renders nothing; the {@code as T} assertion renders the
     * descriptor-driven check and its declared E8001 arm, so a mismatch fails
     * at the assertion's own origin with the arm's own tuple and no coercion,
     * construction or identity change. The container/class/host boundaries of
     * the later stages extend the same input construction.
     */
    private void emitBoundary(SemanticOp op) {
        KindPayload.BoundaryPayload payload =
            (KindPayload.BoundaryPayload) op.payload();
        if (!(op.result() instanceof ValueId)) {
            // A boundary without a result is a checked position whose value
            // keeps its own static type: a statically proven one is elided
            // and every other one is rejected by the classifier.
            return;
        }
        ValueId result = (ValueId) op.result();
        RuntimeDescriptor declared = payload.descriptor();
        RuntimeDescriptor actual = descriptorOfValue(payload.input());
        String representation = stagedRepresentation(declared);
        if (LlvmTypeMapper.convertible(declared, actual)) {
            // The operand's own static type already proves the declared
            // descriptor, so the boundary is the identity of that value.
            storeResult(op, representation,
                convert(payload.input(), actual, declared, op));
            return;
        }
        String checked = checkValue(op, payload.input(), actual, declared);
        storeResult(op, representation, checked);
    }

    /**
     * The checked value of one assertion, through the one descriptor-driven
     * {@code deal_check_value} entry (R4). The published prototype passes its
     * forty-eight-byte {@code deal_check_input} by value, so the emitted call
     * names the record's C ABI spelling ({@code byval}); the eight-byte
     * {@code deal_outcome} it returns fits one eightbyte and crosses in
     * registers. On success the checked value is the input's own datum, so
     * identity is preserved and nothing is coerced, constructed or retagged.
     */
    private String checkValue(SemanticOp op, ValueId input,
                              RuntimeDescriptor actual,
                              RuntimeDescriptor declared) {
        String datum = checkInput(op, input, actual);
        emitCheckCall(op, declared, datum);
        return checkedValue(op, declared, datum);
    }

    /**
     * The one descriptor-driven check call of a compiled boundary (R4): the
     * declared descriptor is interned, the checked input is passed by value in
     * its record spelling, and the carried Error of a mismatch routes like every
     * other failure (C4) after the declared arm is rendered at the boundary's
     * own origin (R5). The caller owns the admitted-value projection.
     */
    private void emitCheckCall(SemanticOp op, RuntimeDescriptor declared,
                               String inputSlot) {
        session.referenceRuntimeEntry("deal_descriptor_intern");
        session.referenceRuntimeEntry("deal_check_value");
        String atom = emitTemporary(op, "call"
            + " %struct.deal_descriptor* @deal_descriptor_intern("
            + session.data.operand(session.data.intern(
                declared.canonicalSpecText())) + ")");
        String context = checkContext(op);
        String outcome = emitTemporary(op, "call %struct.deal_outcome"
            + " @deal_check_value(%struct.deal_descriptor* " + atom
            + ", %struct.deal_check_input* byval(%struct.deal_check_input) "
            + inputSlot + ", i32 " + checkBoundaryKind(declared)
            + ", %struct.deal_check_context* " + context + ")");
        String error = emitTemporary(op, "extractvalue %struct.deal_outcome "
            + outcome + ", 0");
        String failed = emitTemporary(op, "icmp ne i8* " + error + ", null");
        String failureLabel = newLabel("checkfail");
        String proceedLabel = newLabel("checkok");
        emit("br i1 " + failed + ", label %" + failureLabel + ", label %"
            + proceedLabel);
        terminated = true;
        startSegment(failureLabel);
        routeFailure(error);
        startSegment(proceedLabel);
    }

    /**
     * The closed boundary kind of one checked descriptor (R4): a function
     * descriptor belongs to the {@code FUNCTION_SIGNATURE} boundary and every
     * other descriptor to {@code TYPE_DESCRIPTOR}, because the runtime rejects
     * the other pairing. A nullish position is its own descriptor kind, so its
     * check reaches a function base through {@code TYPE_DESCRIPTOR}.
     */
    private static int checkBoundaryKind(RuntimeDescriptor declared) {
        return declared instanceof RuntimeDescriptor.Func
            ? LlvmRuntimeAbi.BOUNDARY_FUNCTION_SIGNATURE
            : LlvmRuntimeAbi.BOUNDARY_TYPE_DESCRIPTOR;
    }

    /**
     * The declared-typed value of one checked datum. The checked datum passes
     * through unchanged (R4: no coercion, construction or retagging), so the
     * projection is the declared representation's own unboxing of it.
     */
    private String checkedValue(SemanticOp op, RuntimeDescriptor declared,
                                String inputSlot) {
        String input = loadQuiet("%struct.deal_check_input", inputSlot);
        String datum = emitTemporary(op, "extractvalue "
            + "%struct.deal_check_input " + input + ", 2");
        return LlvmDataValues.unbox(session, sink, declared, datum);
    }

    /**
     * Stores the datum of one checked operand into the scratch slot the
     * {@code byval} check input copies from.
     */
    private String checkInput(SemanticOp op, ValueId input,
                              RuntimeDescriptor actual) {
        String slot = newAlloca("%struct.deal_check_input", "checked");
        emit("store %struct.deal_check_input "
            + checkInputRecord(op, input) + ", %struct.deal_check_input* "
            + slot);
        return slot;
    }

    /**
     * The carried canonical signature text of one checked datum, or
     * {@code null} when the presented position can carry no function-tagged
     * datum: a function-typed position carries the interned text of the
     * carrier the datum references, and an {@code unknown} or function-based
     * nullish position — whose datum could be any supported data — selects it
     * on the datum's own tag, so a {@code null}/{@code undefined} singleton or
     * any other datum is never dereferenced as a carrier. The runtime
     * classifies every function-tagged datum with this text
     * ({@code deal_check_classify} faults without it) and a function position
     * compares it against the declared signature.
     */
    private String carriedSignature(SemanticOp op, RuntimeDescriptor actual,
                                    String datum) {
        if (actual instanceof RuntimeDescriptor.Func) {
            return carrierSignature(op, datum);
        }
        if (!carriesFunctionDatum(actual)) {
            return null;
        }
        String slot = newAlloca("i8*", "signature");
        emit("store i8* null, i8** " + slot);
        String tag = emitTemporary(op, "extractvalue " + LlvmTypeMapper.DATA
            + " " + datum + ", 0");
        String isFunction = emitTemporary(op, "icmp eq i32 " + tag + ", "
            + LlvmTypeMapper.DATA_FUNCTION);
        String functionLabel = newLabel("signature.function");
        String joinLabel = newLabel("signature.join");
        emit("br i1 " + isFunction + ", label %" + functionLabel + ", label %"
            + joinLabel);
        terminated = true;
        startSegment(functionLabel);
        emit("store i8* " + carrierSignature(op, datum) + ", i8** " + slot);
        terminate("br label %" + joinLabel);
        startSegment(joinLabel);
        return loadQuiet("i8*", slot);
    }

    /**
     * Whether one presented descriptor's datum can carry the function tag: a
     * function value, an {@code unknown} datum of any supported kind, or a
     * nullish position whose base is one of those.
     */
    private static boolean carriesFunctionDatum(RuntimeDescriptor actual) {
        if (actual instanceof RuntimeDescriptor.Func
                || actual instanceof RuntimeDescriptor.Unknown) {
            return true;
        }
        return actual instanceof RuntimeDescriptor.Nullable nullable
            && carriesFunctionDatum(nullable.inner());
    }

    /** The interned signature text of the carrier one function datum references. */
    private String carrierSignature(SemanticOp op, String datum) {
        session.referenceRuntimeEntry("deal_fn_signature");
        String reference = emitTemporary(op, "extractvalue " + LlvmTypeMapper.DATA
            + " " + datum + ", 3");
        String carrier = emitTemporary(op, "bitcast i8* " + reference + " to "
            + LlvmTypeMapper.FN_POINTER);
        return emitTemporary(op, "call i8* @deal_fn_signature("
            + LlvmTypeMapper.FN_POINTER + " " + carrier + ")");
    }

    /** One unnamed load of the current op group. */
    private String loadQuiet(String type, String slot) {
        String name = "%load" + (loadCounter++);
        emit(name + " = load " + type + ", " + type + "* " + slot);
        return name;
    }

    /** The {@code deal_check_context} of one checked boundary. */
    private String checkContext(SemanticOp op) {
        String slot = newAlloca("%struct.deal_check_context", "checkcontext");
        emit("store %struct.deal_check_context { %struct.deal_origin* "
            + originValue(op) + ", i32 0 },"
            + " %struct.deal_check_context* " + slot);
        return slot;
    }

    // =========================================================================
    // Loads, stores, roots and singletons
    // =========================================================================

    private static String stagedRepresentation(RuntimeDescriptor descriptor) {
        // A static singleton's storage is the C3 singleton pointer: a declared
        // field, element or result slot of `null`/`undefined` always holds one
        // of the two distinct immutable singletons, never a value payload.
        if (LlvmTypeMapper.isStaticSingleton(descriptor)) {
            return LlvmTypeMapper.REFERENCE_NULLISH;
        }
        return LlvmTypeMapper.representationOf(descriptor).orElseThrow(() ->
            new IllegalStateException("the descriptor '"
                + descriptor.canonicalSpecText()
                + "' has no staged representation"));
    }

    private static String resultRepresentationOf(RuntimeDescriptor descriptor) {
        return stagedRepresentation(descriptor);
    }

    private Optional<String> resultRepresentation(SemanticOp op) {
        if (!(op.result() instanceof ValueId)) {
            return Optional.empty();
        }
        return session.valueRepresentation(unit, (ValueId) op.result());
    }

    /** The recorded static descriptor of one produced value. */
    private RuntimeDescriptor descriptorOfValue(ValueId value) {
        SemanticOp producer = session.producerOf(value);
        if (producer == null) {
            throw new IllegalStateException("the value " + value
                + " has no producing op in the lowered project");
        }
        if (!(producer.resultType() instanceof RuntimeDescriptor descriptor)) {
            throw new IllegalStateException("the value " + value
                + " carries no descriptor");
        }
        return descriptor;
    }

    private String valueSlot(ValueId value, String type) {
        String slot = valueSlots.computeIfAbsent(value.id(), id -> {
            String name = session.slot(value);
            allocas.add(name + " = alloca " + type);
            return name;
        });
        String bound = valueSlotTypes.putIfAbsent(value.id(), type);
        if (bound != null && !bound.equals(type)) {
            throw new IllegalStateException("the value " + value
                + " is used as both " + bound + " and " + type);
        }
        return slot;
    }

    private String bindingSlot(BindingId binding, long generation, String type) {
        // A captured or reassigned binding incaration is one heap cell (C5):
        // its slot holds the cell pointer, never the value itself.
        String slotType = session.cellBinding(unit, binding, generation)
            ? "i8*" : type;
        if (session.moduleScopedAcrossProject(binding, generation)) {
            return "@" + session.data.globalSlot(binding, generation, slotType);
        }
        String key = binding.id() + "_" + generation;
        String slot = bindingSlots.computeIfAbsent(key, ignored -> {
            String name = session.cell(binding, generation);
            allocas.add(name + " = alloca " + slotType);
            return name;
        });
        String bound = bindingSlotTypes.putIfAbsent(key, slotType);
        if (bound != null && !bound.equals(slotType)) {
            throw new IllegalStateException("the binding " + binding
                + " is used as both " + bound + " and " + slotType);
        }
        return slot;
    }

    private void storeResult(SemanticOp op, String type, String value) {
        if (!(op.result() instanceof ValueId result)) {
            return;
        }
        String slot = valueSlot(result, type);
        emit("store " + type + " " + value + ", " + type + "* " + slot);
        rootSlotWrite(descriptorOfValue(result), slot, value, op);
    }

    /** Loads one produced value from its SSA slot. */
    private String loadValue(ValueId value, String type, SemanticOp op) {
        return load(op, type, valueSlot(value, type));
    }

    private String load(SemanticOp op, String type, String slot) {
        String name = "%load" + (loadCounter++);
        emit(name + " = load " + type + ", " + type + "* " + slot
            + (op == null ? "" : debugAttachment(op)));
        return name;
    }

    /** One unnamed temporary instruction of the current op group. */
    private String emitTemporary(SemanticOp op, String instruction) {
        String name = "%tmp" + (loadCounter++);
        emit(name + " = " + instruction + (op == null ? "" : debugAttachment(op)));
        return name;
    }

    /** Allocates one entry-block scratch slot. */
    private String newAlloca(String type, String prefix) {
        String name = "%" + prefix + (loadCounter++);
        allocas.add(name + " = alloca " + type);
        return name;
    }

    /**
     * Calls one published entry whose record return crosses the C ABI through
     * the hidden {@code sret} pointer (a record larger than two eightbytes),
     * and answers the loaded record value.
     */
    private String callRecord(SemanticOp op, String recordType, String callee,
                              String arguments) {
        String slot = newAlloca(recordType, "callresult");
        emit("call void @" + callee + "(" + recordType + "* sret("
            + recordType + ") " + slot
            + (arguments.isEmpty() ? "" : ", " + arguments) + ")"
            + (op == null ? "" : debugAttachment(op)));
        return emitTemporary(op, "load " + recordType + ", " + recordType
            + "* " + slot);
    }

    // =========================================================================
    // Root frame discipline (M4)
    // =========================================================================

    /** Reserves the root-frame enter line so the slot count can be patched. */
    private void enterRootFrame() {
        session.referenceRuntimeEntry("deal_root_frame_enter");
        rootEnterIndex = entryInit.size();
        entryInit.add("");
    }

    /** Fills the reserved enter line with the activation's static slot count. */
    private void patchRootEnter() {
        if (rootEnterIndex >= 0) {
            entryInit.set(rootEnterIndex, LlvmIrPrinter.indent(1)
                + "call void @deal_root_frame_enter(i32 " + rootSlotCount + ")");
        }
    }

    private int rootSlot(String slot) {
        return rootSlots.computeIfAbsent(slot, ignored -> rootSlotCount++);
    }

    private void rootBindingSlot(BindingId binding, long generation,
                                 RuntimeDescriptor descriptor, String slot,
                                 SemanticOp op) {
        rootSlotWrite(descriptor, slot, null, op);
    }

    /** Roots one managed slot (M4), keyed on the slot's declared descriptor. */
    private void rootSlotWrite(RuntimeDescriptor descriptor, String slot,
                               String operand, SemanticOp op) {
        if (!LlvmTypeMapper.isManagedValue(descriptor)) {
            return;
        }
        int index = rootSlot(slot);
        session.referenceRuntimeEntry("deal_root_frame_write");
        if (descriptor instanceof RuntimeDescriptor.Unknown) {
            session.referenceRuntimeEntry("deal_root_frame_write_data");
            emit("call void @deal_root_frame_write_data(i32 " + index
                + ", " + LlvmTypeMapper.DATA + "* " + slot + ")"
                + (op == null ? "" : debugAttachment(op)));
            return;
        }
        String representation = stagedRepresentation(descriptor);
        String pointer = operand == null ? load(op, representation, slot)
            : operand;
        if (LlvmTypeMapper.isManagedPointer(descriptor)
                || descriptor instanceof RuntimeDescriptor.Func) {
            // A managed representation pointer is never a nullish singleton:
            // the nullish form of a reference position is its own `i8*`.
            pointer = emitTemporary(op, "bitcast " + representation + " "
                + pointer + " to i8*");
        } else {
            pointer = emitRootableReference(pointer, op);
        }
        emit("call void @deal_root_frame_write(i32 " + index + ", i8* "
            + pointer + ")" + (op == null ? "" : debugAttachment(op)));
    }

    /**
     * The rootable referent of one reference-nullish operand: the pointer
     * itself, or C NULL when it is the null or undefined singleton — a
     * branchless selection, so no nullish singleton is ever traced as a heap
     * address (M4/M5).
     */
    private String emitRootableReference(String pointer, SemanticOp op) {
        session.referenceRuntimeEntry("deal_value_is_null");
        session.referenceRuntimeEntry("deal_value_is_undefined");
        String isNull = emitTemporary(op,
            "call i32 @deal_value_is_null(i8* " + pointer + ")");
        String isUndefined = emitTemporary(op,
            "call i32 @deal_value_is_undefined(i8* " + pointer + ")");
        String nullish = emitTemporary(op, "or i32 " + isNull + ", "
            + isUndefined);
        String managed = emitTemporary(op, "icmp eq i32 " + nullish + ", 0");
        return emitTemporary(op, "select i1 " + managed + ", i8* " + pointer
            + ", i8* null");
    }

    /**
     * The same root write for one activation's entry block: a parameter's
     * managed slot is written before any user op runs (M4).
     */
    private void rootEntrySlot(RuntimeDescriptor descriptor, String slot,
                               String parameter) {
        if (!LlvmTypeMapper.isManagedValue(descriptor)) {
            return;
        }
        int index = rootSlot(slot);
        session.referenceRuntimeEntry("deal_root_frame_write");
        if (descriptor instanceof RuntimeDescriptor.Unknown) {
            session.referenceRuntimeEntry("deal_root_frame_write_data");
            entryInit.add(LlvmIrPrinter.indent(1)
                + "call void @deal_root_frame_write_data(i32 " + index + ", "
                + LlvmTypeMapper.DATA + "* " + slot + ")");
            return;
        }
        String pointer = parameter;
        if (LlvmTypeMapper.isManagedPointer(descriptor)
                || descriptor instanceof RuntimeDescriptor.Func) {
            pointer = "%rootcast" + (loadCounter++);
            entryInit.add(LlvmIrPrinter.indent(1) + pointer + " = bitcast "
                + stagedRepresentation(descriptor) + " " + parameter + " to i8*");
        } else {
            session.referenceRuntimeEntry("deal_value_is_null");
            session.referenceRuntimeEntry("deal_value_is_undefined");
            String isNull = "%rootnull" + (loadCounter++);
            String isUndefined = "%rootundefined" + (loadCounter++);
            String nullish = "%rootnullish" + (loadCounter++);
            String managed = "%rootmanaged" + (loadCounter++);
            String select = "%rootselect" + (loadCounter++);
            entryInit.add(LlvmIrPrinter.indent(1) + isNull
                + " = call i32 @deal_value_is_null(i8* " + parameter + ")");
            entryInit.add(LlvmIrPrinter.indent(1) + isUndefined
                + " = call i32 @deal_value_is_undefined(i8* " + parameter + ")");
            entryInit.add(LlvmIrPrinter.indent(1) + nullish + " = or i32 "
                + isNull + ", " + isUndefined);
            entryInit.add(LlvmIrPrinter.indent(1) + managed + " = icmp eq i32 "
                + nullish + ", 0");
            entryInit.add(LlvmIrPrinter.indent(1) + select + " = select i1 "
                + managed + ", i8* " + parameter + ", i8* null");
            pointer = select;
        }
        entryInit.add(LlvmIrPrinter.indent(1)
            + "call void @deal_root_frame_write(i32 " + index + ", i8* "
            + pointer + ")");
    }

    /** The distinct null singleton pointer ({@code null} is never C NULL). */
    private String loadSingleton(boolean nullish, SemanticOp op) {
        String global = nullish ? "deal_null_singleton"
            : "deal_undefined_singleton";
        session.referenceExternalGlobal(global);
        return emitTemporary(op, "load i8*, i8** @" + global);
    }

    // =========================================================================
    // Failure routing (C4)
    // =========================================================================

    /**
     * Transfers one carried Error to the nearest open failure route, or out of
     * the activation. A handler keeps the current frame (its materialized
     * Error already snapshotted it); leaving the activation restores the
     * caller's frame and root depth first.
     */
    private void routeFailure(String error) {
        FailureHandler handler = handlers.peek();
        if (handler == null) {
            emitEpilogue();
            terminateFailure(error);
            return;
        }
        emit("store i8* " + error + ", i8** " + handler.pendingSlot());
        terminate("br label %" + handler.label());
    }

    /**
     * Tests one rendered check's carried Error and routes the failure: the
     * C4 explicit test/branch, with {@code null} meaning success and the
     * value field never read on failure. The success path stays open as the
     * current segment.
     */
    private void branchOnFailure(SemanticOp op, String error, String prefix) {
        String failed = emitTemporary(op, "icmp ne i8* " + error + ", null");
        String failureLabel = newLabel(prefix + "fail");
        String proceedLabel = newLabel(prefix + "ok");
        emit("br i1 " + failed + ", label %" + failureLabel + ", label %"
            + proceedLabel);
        terminated = true;
        startSegment(failureLabel);
        routeFailure(error);
        startSegment(proceedLabel);
    }

    // =========================================================================
    // Structured control flow
    // =========================================================================

    private void emitModuleImport(SemanticOp op) {
        KindPayload.ModuleImportPayload payload =
            (KindPayload.ModuleImportPayload) op.payload();
        if (payload.kind() == deal.semantic.ir.ModuleImportKind.STDLIB) {
            // A stdlib declaration module is the compile-time type authority;
            // its operations reach the `std` group through STDLIB_CALL, so
            // there is no module record and no import cell to publish (R7).
            return;
        }
        ModuleId imported = payload.resolvedModule();
        if (!session.modules().contains(imported)) {
            throw new IllegalStateException("the import " + payload.rawSpecifier()
                + " resolves to " + imported + " outside the project closure");
        }
        String outcome = "%struct.deal_outcome";
        String result = "%import" + op.opId().id();
        emit(result + " = call " + outcome + " @deal_module_init_"
            + session.moduleIndex(imported) + "()" + debugAttachment(op));
        // A failed initialization propagates the carried Error unchanged.
        emit("%importerr" + op.opId().id() + " = extractvalue " + outcome + " "
            + result + ", 0");
        branchOnFailure(op, "%importerr" + op.opId().id(), "import");
        for (BindingId alias : payload.aliasCells()) {
            String representation = session.bindingRepresentation(unit, alias, 0)
                .orElseThrow(() -> new IllegalStateException(
                    "the import alias " + alias + " carries no representation"));
            emit("store " + representation + " @"
                + LlvmData.moduleRecord(session.moduleIndex(imported)) + ", "
                + representation + "* " + bindingSlot(alias, 0, representation));
        }
    }

    /**
     * {@code BINDING_ALLOC} of a captured or reassigned binding: one heap cell
     * per binding generation (C5). The slot holds the cell pointer and the cell
     * is rooted, so every alias observes one identity and one value.
     */
    private void emitBindingAlloc(SemanticOp op) {
        KindPayload.BindingAllocPayload payload =
            (KindPayload.BindingAllocPayload) op.payload();
        if (!session.cellBinding(unit, payload.binding(), payload.generation())) {
            // A direct binding's storage is its own native slot, declared on
            // first use.
            return;
        }
        if (setupPublishedCell(payload.binding(), payload.generation())) {
            // A parameter's generation 0 cell is published by the activation's
            // setup segment from the incoming argument (C5): its own
            // BINDING_ALLOC must not allocate a second cell holding
            // `undefined`, or the closure and every alias would observe it.
            return;
        }
        session.referenceRuntimeEntry("deal_cell_alloc_data");
        session.referenceRuntimeEntry("deal_data_of_undefined");
        String undefined = callRecord(op, LlvmTypeMapper.DATA,
            "deal_data_of_undefined", "");
        String datumSlot = newAlloca(LlvmTypeMapper.DATA, "celldatum");
        emit("store " + LlvmTypeMapper.DATA + " " + undefined + ", "
            + LlvmTypeMapper.DATA + "* " + datumSlot);
        String cell = emitTemporary(op, "call i8* @deal_cell_alloc_data("
            + LlvmTypeMapper.DATA + "* " + datumSlot + ", i32 "
            + payload.generation() + ")");
        String slot = bindingSlot(payload.binding(), payload.generation(), "i8*");
        emit("store i8* " + cell + ", i8** " + slot);
        rootCellAt(slot, op);
    }

    /**
     * Whether this activation's setup segment already published the cell of one
     * binding generation: every parameter is stashed by the prologue and its
     * generation 0 cell is allocated there before the body runs (C5), so the
     * body's own {@code BINDING_ALLOC} for that incarnation owns no allocation.
     */
    private boolean setupPublishedCell(BindingId binding, long generation) {
        return generation == 0 && functionId != null
            && session.parametersOf(functionId).contains(binding);
    }

    /** Writes one cell-pointer slot into its own root-frame slot (M4). */
    private void rootCellAt(String slot, SemanticOp op) {
        int index = rootSlot(slot);
        session.referenceRuntimeEntry("deal_root_frame_write");
        String cell = load(op, "i8*", slot);
        emit("call void @deal_root_frame_write(i32 " + index + ", i8* "
            + cell + ")" + (op == null ? "" : debugAttachment(op)));
    }

    /** Whether one function carries a parameter in a heap cell (C5). */
    private boolean hasCellParameter(FunctionId id, LoweredFunction function) {
        for (BindingId parameter : session.parametersOf(id)) {
            if (session.cellBinding(unit, parameter, 0)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The setup segment of one activation: the parameter cells and the cells
     * of the function's own generation-pinned capture list (C5). The captures
     * are read from the carrier exactly as the creating {@code CLOSURE_NEW}
     * recorded them, so a captured mutable binding and its closure share one
     * cell identity and a per-iteration generation gets a fresh cell.
     */
    private void emitCaptureSetup(FunctionId id, LoweredFunction function) {
        List<BindingId> parameters = session.parametersOf(id);
        List<RuntimeDescriptor> types = function.descriptor().paramTypes();
        for (int index = 0; index < parameters.size(); index++) {
            if (!session.cellBinding(unit, parameters.get(index), 0)) {
                continue;
            }
            RuntimeDescriptor descriptor = types.get(index);
            String representation = stagedRepresentation(descriptor);
            String stash = "%parameter" + index + "value";
            String value = load(null, representation, stash);
            rootSlotWrite(descriptor, stash, value, null);
            publishCell(parameters.get(index), 0, descriptor, value, null);
        }
        for (int index = 0; index < function.captures().size(); index++) {
            BindingGeneration capture = function.captures().get(index);
            session.referenceRuntimeEntry("deal_fn_capture");
            String cell = emitTemporary(null, "call i8* @deal_fn_capture("
                + LlvmTypeMapper.FN_POINTER + " %env, i32 " + index + ")");
            String slot = bindingSlot(capture.binding(), capture.generation(),
                "i8*");
            emit("store i8* " + cell + ", i8** " + slot);
            rootCellAt(slot, null);
        }
    }

    /** Allocates one datum cell for a binding generation and stores its pointer. */
    private void publishCell(BindingId binding, long generation,
                             RuntimeDescriptor descriptor, String value,
                             SemanticOp op) {
        session.referenceRuntimeEntry("deal_cell_alloc_data");
        String datum = emitDatum(descriptor, value, op);
        String datumSlot = newAlloca(LlvmTypeMapper.DATA, "celldatum");
        emit("store " + LlvmTypeMapper.DATA + " " + datum + ", "
            + LlvmTypeMapper.DATA + "* " + datumSlot);
        String cell = emitTemporary(op, "call i8* @deal_cell_alloc_data("
            + LlvmTypeMapper.DATA + "* " + datumSlot + ", i32 " + generation
            + ")");
        String slot = bindingSlot(binding, generation, "i8*");
        emit("store i8* " + cell + ", i8** " + slot);
        rootCellAt(slot, op);
    }

    /**
     * {@code CLOSURE_NEW}: one function value (C3/C5) carrying the exact
     * generation-pinned cells of the lowered capture list, the interned
     * canonical signature atom and the emitted boxed apply thunk.
     */
    private void emitClosureNew(SemanticOp op) {
        KindPayload.ClosureNewPayload payload =
            (KindPayload.ClosureNewPayload) op.payload();
        String carrier = emitClosure(payload.function(), payload.captures(),
            payload.signature(), op);
        storeResult(op, LlvmTypeMapper.FN_POINTER, carrier);
    }

    /** Builds one function value for a lowered function and capture list. */
    private String emitClosure(FunctionId target,
                               List<BindingGeneration> captures,
                               RuntimeDescriptor.Func signature,
                               SemanticOp op) {
        LoweredFunction function = session.function(target);
        if (function.captures().size() != captures.size()) {
            throw new IllegalStateException("the closure of " + target
                + " records " + captures.size() + " captures for the function's "
                + function.captures().size());
        }
        throwIfUnsupportedThunk(function, op, "the closure");
        session.referenceRuntimeEntry("deal_fn_create");
        String cells = "i8** null";
        if (!captures.isEmpty()) {
            String arrayType = "[" + captures.size() + " x i8*]";
            String array = newAlloca(arrayType, "cells");
            for (int index = 0; index < captures.size(); index++) {
                BindingGeneration capture = captures.get(index);
                String cell = load(op, "i8*", bindingSlot(capture.binding(),
                    capture.generation(), "i8*"));
                String element = emitTemporary(op, "getelementptr " + arrayType
                    + ", " + arrayType + "* " + array + ", i64 0, i64 "
                    + index);
                emit("store i8* " + cell + ", i8** " + element);
                if (index == 0) {
                    cells = "i8** " + element;
                }
            }
        }
        String thunk = "bitcast (" + thunkType() + " @" + thunkName(target)
            + " to i8*)";
        String signatureText = session.data.operand(
            session.data.intern(signature.canonicalSpecText()));
        return emitTemporary(op, "call " + LlvmTypeMapper.FN_POINTER
            + " @deal_fn_create(i8* " + thunk + ", " + cells + ", i32 "
            + captures.size() + ", " + signatureText + ", i32 "
            + (signature.isAsync() ? 1 : 0) + ")");
    }

    /** The IR type of one boxed apply thunk. */
    private static String thunkType() {
        return "%struct.deal_outcome_data (i8*, %struct.deal_check_input*,"
            + " i32, %struct.deal_origin*)*";
    }

    /** The emitted name of one function's boxed apply thunk. */
    static String thunkName(FunctionId target) {
        return "T" + target.id();
    }

    /**
     * Rejects a function signature whose boxing this stage does not construct:
     * the staged scalar, {@code unknown}, {@code string}, function and builtin
     * {@code Error} positions, the two nullish singletons and result-only
     * {@code void}. A nullish scalar or reference position, an array, a record
     * or a non-builtin class belongs to the data/class stage.
     */
    private void throwIfUnsupportedThunk(LoweredFunction function, SemanticOp op,
                                         String what) {
        List<RuntimeDescriptor> positions = new ArrayList<>(
            function.descriptor().paramTypes());
        positions.add(function.descriptor().returnType());
        for (RuntimeDescriptor descriptor : positions) {
            if (thunkBoxable(descriptor)) {
                continue;
            }
            throw new deal.codegen.SharedEmissionFailure(
                "LlvmSemanticEmitter rejects " + what + " of the function "
                    + function.functionId() + ": the position '"
                    + descriptor.canonicalSpecText()
                    + "' has no staged boxed apply form",
                op == null ? null : op.origin().span().range());
        }
    }

    /** Whether one descriptor has the staged boxed apply form. */
    private static boolean thunkBoxable(RuntimeDescriptor descriptor) {
        return descriptor instanceof RuntimeDescriptor.Void
            || descriptor instanceof RuntimeDescriptor.Null
            || LlvmTypeMapper.representationOf(descriptor).isPresent()
            || (descriptor instanceof RuntimeDescriptor.Nullable nullable
                && nullableBoxable(nullable));
    }

    /** Whether one nullish position has the staged boxed apply form. */
    private static boolean nullableBoxable(RuntimeDescriptor.Nullable nullable) {
        return LlvmTypeMapper.representationOf(nullable).isPresent();
    }

    private void emitExportRead(SemanticOp op) {
        KindPayload.ExportReadPayload payload =
            (KindPayload.ExportReadPayload) op.payload();
        ModuleId module = payload.module();
        Optional<deal.semantic.StdlibFunctionCatalog.Entry> stdlib =
            deal.semantic.StdlibFunctionCatalog.lookup(module.path(),
                payload.name());
        if (stdlib.isPresent()) {
            String slot = "@" + session.data.stdlibSlot(
                session.moduleIndex(unit.moduleId()), stdlib.get().function());
            String value = emitTemporary(op, "load " + LlvmTypeMapper.FN_POINTER
                + ", " + LlvmTypeMapper.FN_POINTER + "* " + slot);
            storeResult(op, LlvmTypeMapper.FN_POINTER, value);
            return;
        }
        String path = session.data.operand(
            session.data.intern(module.path()));
        String name = session.data.operand(session.data.intern(payload.name()));
        String record = emitTemporary(op, "call %struct.deal_module*"
            + " @deal_project_exports(" + path + ")");
        String entry = emitTemporary(op, "call %struct.deal_export_entry*"
            + " @deal_export_lookup(%struct.deal_module* " + record
            + ", " + name + ")");
        String missing = emitTemporary(op, "icmp eq %struct.deal_export_entry* "
            + entry + ", null");
        String absent = newLabel("exportabsent");
        String present = newLabel("exportpresent");
        emit("br i1 " + missing + ", label %" + absent + ", label %" + present);
        terminated = true;
        startSegment(absent);
        session.referenceRuntimeEntry("deal_platform_fault");
        emit("call void @deal_platform_fault("
            + session.data.operand(session.data.intern(
                "the imported export is not published")) + ")");
        terminate("unreachable");
        startSegment(present);
        String valuePointer = emitTemporary(op, "getelementptr"
            + " %struct.deal_export_entry, %struct.deal_export_entry* " + entry
            + ", i32 0, i32 4");
        String value = emitTemporary(op, "load " + LlvmTypeMapper.FN_POINTER
            + ", " + LlvmTypeMapper.FN_POINTER + "* " + valuePointer);
        storeResult(op, LlvmTypeMapper.FN_POINTER, value);
    }

    /**
     * {@code EXPORT_PUBLISH}: stores the published function value into this
     * unit's export record. The record becomes visible only after the unit's
     * initialization succeeds, and every import reads it through the emitted
     * export table (C2/C3).
     */
    private void emitExportPublish(SemanticOp op) {
        KindPayload.ExportPublishPayload payload =
            (KindPayload.ExportPublishPayload) op.payload();
        int index = session.exportIndex(payload.module(), payload.name());
        String value = loadRepresentation(payload.value(),
            descriptorOfValue(payload.value()), op);
        String table = "@" + LlvmData.exportTable(session.moduleIndex(payload.module()));
        String entry = emitTemporary(op, "getelementptr ["
            + session.exportCount(payload.module()) + " x %struct.deal_export_entry], ["
            + session.exportCount(payload.module()) + " x %struct.deal_export_entry]* "
            + table + ", i64 0, i64 " + index);
        String field = emitTemporary(op, "getelementptr %struct.deal_export_entry,"
            + " %struct.deal_export_entry* " + entry + ", i32 0, i32 4");
        emit("store " + LlvmTypeMapper.FN_POINTER + " " + value + ", "
            + LlvmTypeMapper.FN_POINTER + "* " + field);
    }

    /**
     * {@code RECURSIVE_GROUP_INIT}: allocates every member's cell first (so no
     * member is observable before the group exists), then publishes each
     * member's closure into its cell (C5). The two phases are the recorded
     * "identities allocated first, then published atomically" contract.
     */
    private void emitRecursiveGroupInit(SemanticOp op) {
        KindPayload.RecursiveGroupInitPayload payload =
            (KindPayload.RecursiveGroupInitPayload) op.payload();
        if (payload.bindings().size() != payload.functions().size()) {
            throw new IllegalStateException("the recursive group " + op.opId()
                + " records " + payload.bindings().size() + " bindings for "
                + payload.functions().size() + " functions");
        }
        for (BindingId binding : payload.bindings()) {
            emitBindingAllocCell(binding, op);
        }
        for (int index = 0; index < payload.functions().size(); index++) {
            FunctionId member = payload.functions().get(index);
            LoweredFunction function = session.function(member);
            BindingId binding = payload.bindings().get(index);
            String carrier = emitClosure(member, function.captures(),
                function.descriptor(), op);
            String slot = bindingSlot(binding, 0, "i8*");
            String cell = load(op, "i8*", slot);
            String datum = emitDatum(function.descriptor(), carrier, op);
            String datumSlot = newAlloca(LlvmTypeMapper.DATA, "groupdatum");
            emit("store " + LlvmTypeMapper.DATA + " " + datum + ", "
                + LlvmTypeMapper.DATA + "* " + datumSlot);
            session.referenceRuntimeEntry("deal_cell_store_data");
            emit("call void @deal_cell_store_data(i8* " + cell + ", "
                + LlvmTypeMapper.DATA + "* " + datumSlot + ")"
                + debugAttachment(op));
        }
    }

    /** Allocates one group member's cell without any recorded allocation op. */
    private void emitBindingAllocCell(BindingId binding, SemanticOp op) {
        session.referenceRuntimeEntry("deal_cell_alloc_data");
        session.referenceRuntimeEntry("deal_data_of_undefined");
        String undefined = callRecord(op, LlvmTypeMapper.DATA,
            "deal_data_of_undefined", "");
        String datumSlot = newAlloca(LlvmTypeMapper.DATA, "celldatum");
        emit("store " + LlvmTypeMapper.DATA + " " + undefined + ", "
            + LlvmTypeMapper.DATA + "* " + datumSlot);
        String cell = emitTemporary(op, "call i8* @deal_cell_alloc_data("
            + LlvmTypeMapper.DATA + "* " + datumSlot + ", i32 0)");
        String slot = bindingSlot(binding, 0, "i8*");
        emit("store i8* " + cell + ", i8** " + slot);
        rootCellAt(slot, op);
    }

    private void emitCall(SemanticOp op) {
        KindPayload.CallPayload payload = (KindPayload.CallPayload) op.payload();
        if (payload.callee() instanceof KindPayload.CallCallee.Dynamic dynamic) {
            emitDynamicCall(op, payload, dynamic.callee());
            return;
        }
        if (payload.callee() instanceof KindPayload.CallCallee.Indirect indirect) {
            emitDynamicCall(op, payload, indirect.callee());
            return;
        }
        if (payload.callee() instanceof KindPayload.CallCallee.Static fixed
                && fixed.binding() instanceof deal.semantic.ir.FunctionExecutionBinding.AdapterBinding adapter) {
            SemanticOp creation = unitOp(adapter.adaptOpId());
            emitDynamicCall(op, payload, (ValueId) creation.result());
            return;
        }
        FunctionId callee = calleeOf(payload);
        LoweredFunction calleeFunction = session.function(callee);
        List<RuntimeDescriptor> parameterTypes =
            calleeFunction.descriptor().paramTypes();
        if (parameterTypes.size() != op.operands().size()) {
            throw new IllegalStateException("the call " + op.opId() + " supplies "
                + op.operands().size() + " operands for " + parameterTypes.size()
                + " parameters");
        }
        // The owned parameter and return boundaries render as their static
        // type proofs exactly because the lowered static type already proves
        // the declared descriptor (the classifier's rule).
        for (OpId boundary : payload.parameterBoundaryOpIds()) {
            recordOrigin(unitOp(boundary));
        }
        if (payload.returnBoundaryOpId() != null) {
            recordOrigin(unitOp(payload.returnBoundaryOpId()));
        }
        String environment = "%struct.deal_fn* null";
        if (!calleeFunction.captures().isEmpty()) {
            for (BindingGeneration capture : calleeFunction.captures()) {
                if (!session.moduleScopedAcrossProject(capture.binding(),
                        capture.generation())) {
                    throw new deal.codegen.SharedEmissionFailure(
                        "LlvmSemanticEmitter rejects the direct call of "
                            + callee + " of " + op.opId() + ": its capture of "
                            + capture.binding() + " is no module-scope cell",
                        op.origin().span().range());
                }
            }
            environment = LlvmTypeMapper.FN_POINTER + " "
                + emitClosure(callee, calleeFunction.captures(),
                    calleeFunction.descriptor(), op);
        }
        StringBuilder arguments = new StringBuilder();
        for (int index = 0; index < parameterTypes.size(); index++) {
            if (index > 0) {
                arguments.append(", ");
            }
            RuntimeDescriptor declared = parameterTypes.get(index);
            ValueId argument = op.operands().get(index);
            RuntimeDescriptor actual = op.operandTypes().get(index);
            String value = convert(argument, actual, declared, op);
            String abi = LlvmTypeMapper.abiParameterType(declared);
            if (declared instanceof RuntimeDescriptor.Boolean) {
                value = emitTemporary(op, "zext i1 " + value + " to " + abi);
            }
            arguments.append(abi).append(' ').append(value);
        }
        String outcome = LlvmTypeMapper.outcomeType(
            calleeFunction.descriptor().returnType());
        String result = "%call" + op.opId().id();
        emit(result + " = call " + outcome + " @F" + callee.id()
            + "(" + environment
            + (arguments.length() == 0 ? "" : ", " + arguments) + ")"
            + debugAttachment(op));
        emit("%callerr" + op.opId().id() + " = extractvalue " + outcome + " "
            + result + ", 0");
        branchOnFailure(op, "%callerr" + op.opId().id(), "call");
        RuntimeDescriptor returned = calleeFunction.descriptor().returnType();
        if (LlvmTypeMapper.isStaticSingleton(returned)
                || returned instanceof RuntimeDescriptor.Void) {
            return;
        }
        String extracted = emitTemporary(op, "extractvalue " + outcome + " "
            + result + ", 1");
        String type = resultRepresentation(op).orElseThrow(() ->
            new IllegalStateException("the call result "
                + returned.canonicalSpecText() + " carries no representation"));
        storeResult(op, type, narrowFromAbi(returned, extracted, op));
    }

    /**
     * The dynamically dispatched call of C5: the closure's signature atom is
     * validated against the recorded call signature (the one E8010 arm at the
     * call origin), each argument is boxed into its recorded presentation, and
     * the carrier's boxed apply thunk is invoked with the call origin. A
     * carried Error routes exactly like a direct call's and the completion
     * datum is unboxed at the declared result descriptor.
     */
    private void emitDynamicCall(SemanticOp op, KindPayload.CallPayload payload,
                                 ValueId calleeValue) {
        RuntimeDescriptor.Func signature = payload.signature();
        for (OpId boundary : payload.parameterBoundaryOpIds()) {
            recordOrigin(unitOp(boundary));
        }
        if (payload.dynamicReturnBoundary() != null) {
            // The recorded return-boundary cells carry the completion's own
            // origins; each non-null cell is one source-mapped location.
            KindPayload.DynamicReturnBoundary cells =
                payload.dynamicReturnBoundary();
            for (OpId boundary : new OpId[] {cells.dealBodyBoundaryOpId(),
                    cells.hostBoundaryOpId(), cells.externalBoundaryOpId()}) {
                if (boundary != null) {
                    recordOrigin(unitOp(boundary));
                }
            }
        } else if (payload.returnBoundaryOpId() != null) {
            recordOrigin(unitOp(payload.returnBoundaryOpId()));
        }
        RuntimeDescriptor calleeType = descriptorOfValue(calleeValue);
        if (!(calleeType instanceof RuntimeDescriptor.Func)) {
            throw new IllegalStateException("the dynamic callee " + calleeValue
                + " is not a function value: "
                + calleeType.canonicalSpecText());
        }
        requireBoxedSignature(signature, op, "the dynamically dispatched call");
        String carrier = loadRepresentation(calleeValue, calleeType, op);
        emitSignatureCheck(op, signature, carrier);
        String arguments = checkInputArray(op, op.operands(),
            op.operandTypes());
        session.referenceRuntimeEntry("deal_fn_apply");
        String applied = callRecord(op, "%struct.deal_outcome_data",
            "deal_fn_apply", LlvmTypeMapper.FN_POINTER + " " + carrier + ", "
                + "%struct.deal_check_input* " + arguments + ", i32 "
                + op.operands().size() + ", %struct.deal_origin* "
                + originValue(op));
        String appliedError = emitTemporary(op,
            "extractvalue %struct.deal_outcome_data " + applied + ", 0");
        branchOnFailure(op, appliedError, "apply");
        RuntimeDescriptor returned = signature.returnType();
        Optional<String> representation = resultRepresentation(op);
        if (representation.isEmpty()) {
            return;
        }
        String datumValue = emitTemporary(op,
            "extractvalue %struct.deal_outcome_data " + applied + ", 1");
        storeResult(op, representation.get(),
            datumToRepresentation(returned, datumValue, op));
    }

    /**
     * The one E8010 arm of a dynamically checked callee (C5): the carrier's
     * own signature atom is validated against the recorded call signature at
     * the {@code FUNCTION_SIGNATURE} boundary, so a wrong-signature function
     * value is rejected at the call. Both the synchronously dispatched call
     * and the blocking async start render exactly this check.
     */
    private void emitSignatureCheck(SemanticOp op,
                                    RuntimeDescriptor.Func signature,
                                    String carrier) {
        session.referenceRuntimeEntry("deal_fn_signature");
        session.referenceRuntimeEntry("deal_descriptor_intern");
        session.referenceRuntimeEntry("deal_check_value");
        String signaturePointer = emitTemporary(op,
            "call i8* @deal_fn_signature(" + LlvmTypeMapper.FN_POINTER + " "
                + carrier + ")");
        String reduced = emitTemporary(op, "bitcast "
            + LlvmTypeMapper.FN_POINTER + " " + carrier + " to i8*");
        String datum = emitTemporary(op, "insertvalue " + LlvmTypeMapper.DATA
            + " zeroinitializer, i32 " + LlvmTypeMapper.DATA_FUNCTION + ", 0");
        datum = emitTemporary(op, "insertvalue " + LlvmTypeMapper.DATA + " "
            + datum + ", i8* " + reduced + ", 3");
        String record = emitTemporary(op, "insertvalue"
            + " %struct.deal_check_input zeroinitializer, "
            + LlvmTypeMapper.DATA + " " + datum + ", 2");
        record = emitTemporary(op, "insertvalue %struct.deal_check_input "
            + record + ", i8* " + signaturePointer + ", 4");
        String input = newAlloca("%struct.deal_check_input", "signatureinput");
        emit("store %struct.deal_check_input " + record
            + ", %struct.deal_check_input* " + input);
        String atom = emitTemporary(op, "call"
            + " %struct.deal_descriptor* @deal_descriptor_intern("
            + session.data.operand(session.data.intern(
                signature.canonicalSpecText())) + ")");
        String context = checkContext(op);
        String checked = emitTemporary(op, "call %struct.deal_outcome"
            + " @deal_check_value(%struct.deal_descriptor* " + atom
            + ", %struct.deal_check_input* byval(%struct.deal_check_input) "
            + input + ", i32 " + LlvmRuntimeAbi.BOUNDARY_FUNCTION_SIGNATURE
            + ", %struct.deal_check_context* " + context + ")");
        String error = emitTemporary(op, "extractvalue %struct.deal_outcome "
            + checked + ", 0");
        branchOnFailure(op, error, "signature");
    }

    /** Rejects one boxed signature whose positions this stage cannot box. */
    private void requireBoxedSignature(RuntimeDescriptor.Func signature,
                                       SemanticOp op, String what) {
        List<RuntimeDescriptor> positions = new ArrayList<>(
            signature.paramTypes());
        positions.add(signature.returnType());
        for (RuntimeDescriptor descriptor : positions) {
            if (thunkBoxable(descriptor)) {
                continue;
            }
            throw new deal.codegen.SharedEmissionFailure(
                "LlvmSemanticEmitter rejects " + what + " of the signature '"
                    + signature.canonicalSpecText() + "': the position '"
                    + descriptor.canonicalSpecText()
                    + "' has no staged boxed apply form",
                op.origin().span().range());
        }
    }

    /**
     * Boxes the ordered call arguments into the recorded presentation array
     * (C5): each operand is evaluated once, converted to the persistent datum
     * of its own static descriptor and stored into the array the thunk reads.
     */
    private String checkInputArray(SemanticOp op, List<ValueId> operands,
                                   List<RuntimeDescriptor> types) {
        if (operands.isEmpty()) {
            return "null";
        }
        String arrayType = "[" + operands.size() + " x %struct.deal_check_input]";
        String array = newAlloca(arrayType, "arguments");
        String first = null;
        for (int index = 0; index < operands.size(); index++) {
            RuntimeDescriptor actual = types.get(index);
            String datum = LlvmTypeMapper.isStaticSingleton(actual)
                ? emitDatum(actual, "", op)
                : emitDatum(actual, loadRepresentation(operands.get(index),
                    actual, op), op);
            String element = emitTemporary(op, "getelementptr " + arrayType
                + ", " + arrayType + "* " + array + ", i64 0, i64 " + index);
            emit("store %struct.deal_check_input "
                + checkInputRecordOf(op, actual, datum) + ", "
                + "%struct.deal_check_input* " + element);
            if (index == 0) {
                first = element;
            }
        }
        return first;
    }

    /**
     * The staged representation of one persistent datum (C3): the inverse of
     * {@link #emitDatum}, used by the captured-binding cell read, a container
     * or class capture, the dynamic call completion, the stdlib result and the
     * await completion. A scalar keeps its payload, a managed tag its referent
     * (preserving the value's identity and, for an array, its persistent
     * element descriptor), and the two nullish tags their singletons; no
     * coercion, construction or retagging happens. A descriptor outside the
     * staged datum set is a producer defect and fails closed with the
     * offending op's own origin (C9).
     */
    private String datumToRepresentation(RuntimeDescriptor descriptor,
                                         String datum, SemanticOp op) {
        if (LlvmTypeMapper.isManagedPointer(descriptor)) {
            String representation = stagedRepresentation(descriptor);
            String reference = emitTemporary(op, "extractvalue "
                + LlvmTypeMapper.DATA + " " + datum + ", 3");
            return representation.equals("i8*")
                ? reference
                : emitTemporary(op, "bitcast i8* " + reference + " to "
                    + representation);
        }
        return switch (descriptor) {
            case RuntimeDescriptor.Number _ -> emitTemporary(op,
                "extractvalue " + LlvmTypeMapper.DATA + " " + datum + ", 2");
            case RuntimeDescriptor.Boolean _ -> {
                String number = emitTemporary(op, "extractvalue "
                    + LlvmTypeMapper.DATA + " " + datum + ", 2");
                yield emitTemporary(op, "fcmp une double " + number + ", 0.0");
            }
            case RuntimeDescriptor.Func _ -> {
                String reference = emitTemporary(op, "extractvalue "
                    + LlvmTypeMapper.DATA + " " + datum + ", 3");
                yield emitTemporary(op, "bitcast i8* " + reference + " to "
                    + LlvmTypeMapper.FN_POINTER);
            }
            case RuntimeDescriptor.Unknown _ -> datum;
            case RuntimeDescriptor.Nullable nullable -> nullableFromDatum(
                nullable, datum, op);
            default -> throw new deal.codegen.SharedEmissionFailure(
                "LlvmSemanticEmitter has no staged datum projection for the"
                    + " descriptor '" + descriptor.canonicalSpecText() + "'",
                op == null ? null : op.origin().span().range());
        };
    }

    /** The nullish representation of one persistent datum. */
    private String nullableFromDatum(RuntimeDescriptor.Nullable nullable,
                                     String datum, SemanticOp op) {
        String representation = stagedRepresentation(nullable);
        String tag = emitTemporary(op, "extractvalue " + LlvmTypeMapper.DATA
            + " " + datum + ", 0");
        String isNull = emitTemporary(op, "icmp eq i32 " + tag + ", "
            + LlvmTypeMapper.DATA_NULL);
        String isUndefined = emitTemporary(op, "icmp eq i32 " + tag + ", "
            + LlvmTypeMapper.DATA_UNDEFINED);
        if (representation.equals(LlvmTypeMapper.REFERENCE_NULLISH)) {
            String reference = emitTemporary(op, "extractvalue "
                + LlvmTypeMapper.DATA + " " + datum + ", 3");
            String nullPointer = loadSingleton(true, op);
            String undefinedPointer = loadSingleton(false, op);
            String withNull = emitTemporary(op, "select i1 " + isNull
                + ", i8* " + nullPointer + ", i8* " + reference);
            return emitTemporary(op, "select i1 " + isUndefined + ", i8* "
                + undefinedPointer + ", i8* " + withNull);
        }
        boolean number = representation.equals(LlvmTypeMapper.NULLISH_NUMBER);
        String payload = emitTemporary(op, "extractvalue " + LlvmTypeMapper.DATA
            + " " + datum + ", 2");
        String base = number
            ? payload
            : emitTemporary(op, "fcmp une double " + payload + ", 0.0");
        int baseValue = nullable.inner() instanceof RuntimeDescriptor.Boolean
            ? LlvmTypeMapper.DATA_BOOLEAN : LlvmTypeMapper.DATA_NUMBER;
        String isBase = emitTemporary(op, "icmp eq i32 " + tag + ", "
            + baseValue);
        String baseTag = "i32 " + LlvmTypeMapper.NULLISH_BASE;
        String nullTag = "i32 " + LlvmTypeMapper.NULLISH_NULL;
        String undefinedTag = "i32 " + LlvmTypeMapper.NULLISH_UNDEFINED;
        String undefinedSelected = emitTemporary(op, "select i1 " + isUndefined
            + ", " + undefinedTag + ", " + baseTag);
        String discriminant = emitTemporary(op, "select i1 " + isNull + ", "
            + nullTag + ", i32 " + undefinedSelected);
        String zero = number ? "double 0.0" : "i1 false";
        String value = number ? "double " : "i1 ";
        String baseSelected = emitTemporary(op, "select i1 " + isBase + ", "
            + value + base + ", " + zero);
        String withTag = emitTemporary(op, "insertvalue " + representation
            + " { i32 0, " + zero + " }, i32 " + discriminant + ", 0");
        return emitTemporary(op, "insertvalue " + representation + " "
            + withTag + ", " + value + baseSelected + ", 1");
    }

    /** {@code FUNCTION_ADAPT}: the adapter closure of one recorded source. */
    private void emitFunctionAdapt(SemanticOp op) {
        KindPayload.FunctionAdaptPayload payload =
            (KindPayload.FunctionAdaptPayload) op.payload();
        requireAdapterShape(op, payload);
        // One private immutable cell retains the evaluated function value.
        String cell;
        switch (payload.source()) {
            case AdaptSourceRef.Value value -> {
                String source = loadRepresentation(value.value(),
                    descriptorOfValue(value.value()), op);
                session.referenceRuntimeEntry("deal_cell_alloc_data");
                String datum = emitDatum(
                    descriptorOfValue(value.value()), source, op);
                String datumSlot = newAlloca(LlvmTypeMapper.DATA, "adapterdatum");
                emit("store " + LlvmTypeMapper.DATA + " " + datum + ", "
                    + LlvmTypeMapper.DATA + "* " + datumSlot);
                cell = emitTemporary(op, "call i8* @deal_cell_alloc_data("
                    + LlvmTypeMapper.DATA + "* " + datumSlot + ", i32 0)");
                rootTemporaryCell(cell, op);
            }
            default -> throw new deal.codegen.SharedEmissionFailure(
                "LlvmSemanticEmitter rejects the FUNCTION_ADAPT of " + op.opId()
                    + ": the recorded adapter source " + payload.source()
                    + " has no staged boxed form",
                op.origin().span().range());
        }
        int captureCount = 1;
        String arrayType = "[" + captureCount + " x i8*]";
        String array = newAlloca(arrayType, "adaptercells");
        String first = null;
        for (int index = 0; index < captureCount; index++) {
            String captured = cell;
            String element = emitTemporary(op, "getelementptr " + arrayType
                + ", " + arrayType + "* " + array + ", i64 0, i64 " + index);
            emit("store i8* " + captured + ", i8** " + element);
            if (index == 0) {
                first = element;
            }
        }
        session.referenceRuntimeEntry("deal_fn_create");
        String thunk = "bitcast (" + thunkType() + " @"
            + adapterThunkName(op.opId()) + " to i8*)";
        String signatureText = session.data.operand(session.data.intern(
            payload.targetSignature().canonicalSpecText()));
        String carrier = emitTemporary(op, "call " + LlvmTypeMapper.FN_POINTER
            + " @deal_fn_create(i8* " + thunk + ", i8** " + first
            + ", i32 " + captureCount + ", " + signatureText + ", i32 "
            + (payload.targetSignature().isAsync() ? 1 : 0) + ")");
        storeResult(op, LlvmTypeMapper.FN_POINTER, carrier);
    }

    /**
     * A fresh heap cell holding {@code undefined} for a recursive-group member
     * before its function value is published.
     */
    private String emitUndefinedCell(SemanticOp op) {
        session.referenceRuntimeEntry("deal_cell_alloc_data");
        session.referenceRuntimeEntry("deal_data_of_undefined");
        String undefined = callRecord(op, LlvmTypeMapper.DATA,
            "deal_data_of_undefined", "");
        String datumSlot = newAlloca(LlvmTypeMapper.DATA, "celldatum");
        emit("store " + LlvmTypeMapper.DATA + " " + undefined + ", "
            + LlvmTypeMapper.DATA + "* " + datumSlot);
        return emitTemporary(op, "call i8* @deal_cell_alloc_data("
            + LlvmTypeMapper.DATA + "* " + datumSlot + ", i32 0)");
    }

    /** The emitted name of one adapter's boxed apply thunk. */
    static String adapterThunkName(OpId opId) {
        return "A" + opId.id();
    }

    /**
     * Roots one scratch cell pointer that is live across the next allocating
     * call (M4): the adapter's value-source cell must survive
     * {@code deal_fn_create}'s safepoint, so its own root-frame slot holds it
     * for the rest of the activation.
     */
    private void rootTemporaryCell(String cell, SemanticOp op) {
        String slot = newAlloca("i8*", "scratchcell");
        emit("store i8* " + cell + ", i8** " + slot);
        rootCellAt(slot, op);
    }

    /**
     * The recorded source-to-target rule of the staged adapter: the target's
     * parameter prefix is exactly the source's parameter list, the result
     * descriptor is shared, and both signatures have the staged boxed form, so
     * arity extension stores the same value with the target's own identity.
     */
    private void requireAdapterShape(SemanticOp op,
                                     KindPayload.FunctionAdaptPayload payload) {
        RuntimeDescriptor.Func source = payload.sourceSignature();
        RuntimeDescriptor.Func target = payload.targetSignature();
        boolean shape = source.paramTypes().size()
            == target.paramTypes().size()
            || (source.paramTypes().size() < target.paramTypes().size()
                && target.paramTypes().subList(0, source.paramTypes().size())
                    .equals(source.paramTypes()));
        if (!shape || !source.returnType().equals(target.returnType())
                || !boxedSignature(source) || !boxedSignature(target)) {
            throw new deal.codegen.SharedEmissionFailure(
                "LlvmSemanticEmitter rejects the FUNCTION_ADAPT of " + op.opId()
                    + ": the recorded rule '" + source.canonicalSpecText()
                    + "' -> '" + target.canonicalSpecText()
                    + "' has no staged boxed adapter form",
                op.origin().span().range());
        }
    }

    private static boolean boxedSignature(RuntimeDescriptor.Func signature) {
        for (RuntimeDescriptor parameter : signature.paramTypes()) {
            if (!thunkBoxable(parameter)) {
                return false;
            }
        }
        return thunkBoxable(signature.returnType());
    }

    /**
     * The boxed apply thunk of one adapter: reads the retained source value,
     * validates the source signature atom against the
     * recorded source signature, invokes the source with the target's leading
     * parameters and answers its completion unchanged.
     *
     * <p>The adapter is a synthesized dispatch shim, not a DEAL activation
     * (C8): its own frame label covers nothing but the arity test and is
     * popped before user code runs, so an Error reports exactly the frames
     * of the activations that raised and dispatched it.</p>
     */
    String emitAdapterThunk(SemanticOp adaptOp) {
        KindPayload.FunctionAdaptPayload payload =
            (KindPayload.FunctionAdaptPayload) adaptOp.payload();
        OpId opId = adaptOp.opId();
        RuntimeDescriptor.Func source = payload.sourceSignature();
        RuntimeDescriptor.Func target = payload.targetSignature();
        for (String entry : List.of("deal_call_frame_push",
                "deal_call_frame_pop", "deal_root_frame_enter",
                "deal_root_frame_leave", "deal_root_frame_write",
                "deal_fn_capture", "deal_cell_read_data",
                "deal_fn_apply", "deal_fn_signature", "deal_check_value",
                "deal_descriptor_intern", "deal_platform_fault")) {
            session.referenceRuntimeEntry(entry);
        }
        StringBuilder out = new StringBuilder();
        out.append("define internal %struct.deal_outcome_data @")
            .append(adapterThunkName(opId))
            .append("(i8* %functionvalue, %struct.deal_check_input* %arguments,"
                + " i32 %count, %struct.deal_origin* %origin) {\n");
        out.append("Lthunk:\n");
        out.append(LlvmIrPrinter.indent(1)).append("call void"
            + " @deal_call_frame_push(")
            .append(session.data.operand(session.data.intern(
                "adapter@A" + opId.id())))
            .append(")\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("call void @deal_root_frame_enter(i32 1)\n");
        out.append(LlvmIrPrinter.indent(1)).append("%arity = icmp eq i32"
            + " %count, ").append(target.paramTypes().size()).append('\n');
        out.append(LlvmIrPrinter.indent(1)).append("br i1 %arity, label %bind,"
            + " label %badcount\n");
        out.append("badcount:\n");
        out.append(LlvmIrPrinter.indent(1)).append("call void"
            + " @deal_platform_fault(")
            .append(session.data.operand(session.data.intern(
                "function value: argument count"))).append(")\n");
        out.append(LlvmIrPrinter.indent(1)).append("unreachable\n");
        out.append("bind:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("call void @deal_call_frame_pop()\n");
        out.append(LlvmIrPrinter.indent(1)).append("%carrier = bitcast i8*"
            + " %functionvalue to %struct.deal_fn*\n");
        out.append(LlvmIrPrinter.indent(1)).append("%cell = call i8*"
            + " @deal_fn_capture(%struct.deal_fn* %carrier, i32 0)\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%sourcedatumslot = alloca %struct.deal_data\n");
        out.append(LlvmIrPrinter.indent(1)).append("call void"
            + " @deal_cell_read_data(%struct.deal_data*"
            + " sret(%struct.deal_data) %sourcedatumslot, i8* %cell)\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%sourcedatum = load %struct.deal_data,"
                + " %struct.deal_data* %sourcedatumslot\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%sourcereference = extractvalue %struct.deal_data"
                + " %sourcedatum, 3\n");
        out.append(LlvmIrPrinter.indent(1)).append("%source = bitcast i8*"
            + " %sourcereference to %struct.deal_fn*\n");
        out.append(LlvmIrPrinter.indent(1)).append("call void"
            + " @deal_root_frame_write(i32 0, i8* %sourcereference)\n");
        // The source signature atom is validated at the adapter invocation.
        out.append(LlvmIrPrinter.indent(1)).append("%signature = call i8*"
            + " @deal_fn_signature(%struct.deal_fn* %source)\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%signatureinput = alloca %struct.deal_check_input\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%functiondatum = insertvalue %struct.deal_data"
                + " { i32 ").append(LlvmTypeMapper.DATA_FUNCTION)
            .append(", i32 0, double 0.0, i8* null }, i8* %sourcereference, 3\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%signaturerecord = insertvalue %struct.deal_check_input"
                + " zeroinitializer, %struct.deal_data %functiondatum, 2\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%signaturefields = insertvalue %struct.deal_check_input"
                + " %signaturerecord, i8* %signature, 4\n");
        out.append(LlvmIrPrinter.indent(1)).append("store"
            + " %struct.deal_check_input %signaturefields,"
            + " %struct.deal_check_input* %signatureinput\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%context = alloca %struct.deal_check_context\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%contextorigin = insertvalue %struct.deal_check_context"
                + " zeroinitializer, %struct.deal_origin* %origin, 0\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%contextindex = insertvalue %struct.deal_check_context"
                + " %contextorigin, i32 0, 1\n");
        out.append(LlvmIrPrinter.indent(1)).append("store"
            + " %struct.deal_check_context %contextindex,"
            + " %struct.deal_check_context* %context\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%expected = call %struct.deal_descriptor*"
                + " @deal_descriptor_intern(")
            .append(session.data.operand(session.data.intern(
                source.canonicalSpecText()))).append(")\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%checked = call %struct.deal_outcome @deal_check_value("
                + "%struct.deal_descriptor* %expected,"
                + " %struct.deal_check_input* byval(%struct.deal_check_input)"
                + " %signatureinput, i32 ")
            .append(LlvmRuntimeAbi.BOUNDARY_FUNCTION_SIGNATURE)
            .append(", %struct.deal_check_context* %context)\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%signatureerror = extractvalue %struct.deal_outcome"
                + " %checked, 0\n");
        out.append(LlvmIrPrinter.indent(1)).append("%signaturefailed = icmp ne"
            + " i8* %signatureerror, null\n");
        out.append(LlvmIrPrinter.indent(1)).append("br i1 %signaturefailed,"
            + " label %signaturefail, label %invoke\n");
        out.append("signaturefail:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%signaturefailure = insertvalue %struct.deal_outcome_data"
                + " zeroinitializer, i8* %signatureerror, 0\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("call void @deal_root_frame_leave()\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("ret %struct.deal_outcome_data %signaturefailure\n");
        out.append("invoke:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%appliedslot = alloca %struct.deal_outcome_data\n");
        out.append(LlvmIrPrinter.indent(1)).append("call void"
            + " @deal_fn_apply(%struct.deal_outcome_data*"
            + " sret(%struct.deal_outcome_data) %appliedslot,"
            + " %struct.deal_fn* %source, %struct.deal_check_input* %arguments,"
            + " i32 ").append(source.paramTypes().size())
            .append(", %struct.deal_origin* %origin)\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%applied = load %struct.deal_outcome_data,"
                + " %struct.deal_outcome_data* %appliedslot\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("call void @deal_root_frame_leave()\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("ret %struct.deal_outcome_data %applied\n}\n");
        return out.toString();
    }

    /**
     * {@code STDLIB_CALL} (R7/R8): the closed operation id, the already
     * evaluated argument presentations and the call origin reach the one
     * runtime dispatch entry, which owns the {@code STDLIB_PARAMETER} and
     * {@code STDLIB_RETURN} crossings. The emitted module inlines no stdlib
     * semantics; the result is unboxed at the recorded result descriptor.
     * Each operand is boxed from its own static descriptor: the recorded
     * operand types are the declared stdlib parameter descriptors, and
     * {@code deal_std_call} itself validates every presentation against
     * those, so boxing at them would read a value's slot at the wrong
     * representation.
     */
    private void emitStdlibCall(SemanticOp op) {
        KindPayload.StdlibCallPayload payload =
            (KindPayload.StdlibCallPayload) op.payload();
        session.referenceRuntimeEntry("deal_std_call");
        List<RuntimeDescriptor> actual = new ArrayList<>(op.operands().size());
        for (ValueId operand : op.operands()) {
            actual.add(descriptorOfValue(operand));
        }
        String arguments = checkInputArray(op, op.operands(), actual);
        String outcome = callRecord(op, "%struct.deal_outcome_data",
            "deal_std_call", "i32 " + payload.function().ordinal() + ", "
                + "%struct.deal_check_input* " + arguments + ", i32 "
                + op.operands().size() + ", %struct.deal_origin* "
                + originValue(op));
        String error = emitTemporary(op, "extractvalue %struct.deal_outcome_data "
            + outcome + ", 0");
        branchOnFailure(op, error, "stdlib");
        Optional<String> representation = resultRepresentation(op);
        if (representation.isEmpty()) {
            return;
        }
        if (!(op.resultType() instanceof RuntimeDescriptor returned)) {
            throw new IllegalStateException("the STDLIB_CALL " + op.opId()
                + " carries no result descriptor");
        }
        String datum = emitTemporary(op, "extractvalue %struct.deal_outcome_data "
            + outcome + ", 1");
        storeResult(op, representation.get(),
            datumToRepresentation(returned, datum, op));
    }

    /**
     * {@code ASYNC_START} (C7): the runtime-allocated operation record and
     * token, the blocking invocation of the callee to completion and the
     * recorded parameter boundaries. The operation handle is kept in the
     * token's own SSA slot; {@code AWAIT} consumes it exactly once.
     */
    private void emitAsyncStart(SemanticOp op) {
        KindPayload.AsyncStartPayload payload =
            (KindPayload.AsyncStartPayload) op.payload();
        if (!(op.result() instanceof AsyncTokenId token)) {
            throw new IllegalStateException("the ASYNC_START " + op.opId()
                + " carries no canonical token");
        }
        for (OpId boundary : payload.parameterBoundaryOpIds()) {
            recordOrigin(unitOp(boundary));
        }
        if (payload.returnBoundaryOpId() != null) {
            recordOrigin(unitOp(payload.returnBoundaryOpId()));
        }
        String carrier;
        if (payload.callee() instanceof KindPayload.CallCallee.Static callee) {
            FunctionId function = switch (callee.binding()) {
                case deal.semantic.ir.FunctionExecutionBinding.LoweredBody body ->
                    body.functionId();
                case deal.semantic.ir.FunctionExecutionBinding.ExternalFunction ignored ->
                    externalAsyncCallee(payload, op);
                default -> throw new deal.codegen.SharedEmissionFailure(
                    "LlvmSemanticEmitter rejects the ASYNC_START callee "
                        + callee.binding() + " of " + op.opId(),
                    op.origin().span().range());
            };
            LoweredFunction lowered = session.function(function);
            for (BindingGeneration capture : lowered.captures()) {
                if (!session.moduleScopedAcrossProject(capture.binding(),
                        capture.generation())) {
                    throw new deal.codegen.SharedEmissionFailure(
                        "LlvmSemanticEmitter rejects the ASYNC_START of the"
                            + " function " + function + " of " + op.opId()
                            + ": its capture of " + capture.binding()
                            + " is no module-scope cell",
                        op.origin().span().range());
                }
            }
            carrier = emitClosure(function, lowered.captures(),
                lowered.descriptor(), op);
        } else if (payload.callee()
                instanceof KindPayload.CallCallee.Dynamic dynamic) {
            carrier = emitAsyncCalleeCheck(op, dynamic.callee());
        } else if (payload.callee()
                instanceof KindPayload.CallCallee.Indirect indirect) {
            carrier = emitAsyncCalleeCheck(op, indirect.callee());
        } else {
            throw new deal.codegen.SharedEmissionFailure(
                "LlvmSemanticEmitter rejects the ASYNC_START callee of "
                    + op.opId(), op.origin().span().range());
        }
        String arguments = checkInputArray(op, op.operands(),
            op.operandTypes());
        session.referenceRuntimeEntry("deal_async_start");
        String started = emitTemporary(op, "call"
            + " %struct.deal_outcome_reference @deal_async_start("
            + LlvmTypeMapper.FN_POINTER + " " + carrier + ", "
            + "%struct.deal_check_input* " + arguments + ", i32 "
            + op.operands().size() + ", %struct.deal_origin* "
            + originValue(op) + ")");
        String error = emitTemporary(op,
            "extractvalue %struct.deal_outcome_reference " + started + ", 0");
        branchOnFailure(op, error, "asyncstart");
        String operation = emitTemporary(op,
            "extractvalue %struct.deal_outcome_reference " + started + ", 1");
        emit("store i8* " + operation + ", i8** " + tokenSlot(token, "i8*"));
    }

    /**
     * The boxed async callee of a {@code Dynamic} or {@code Indirect}
     * {@code ASYNC_START}: the carrier is loaded and its signature atom is
     * validated against the recorded call signature exactly like the
     * synchronously dispatched call's, so a wrong-signature function value
     * reaches the one E8010 arm at the async call before the operation record
     * is created (C5, C7).
     */
    private String emitAsyncCalleeCheck(SemanticOp op, ValueId calleeValue) {
        RuntimeDescriptor calleeType = descriptorOfValue(calleeValue);
        if (!(calleeType instanceof RuntimeDescriptor.Func signature)) {
            throw new deal.codegen.SharedEmissionFailure(
                "LlvmSemanticEmitter rejects the ASYNC_START callee "
                    + calleeValue + " of " + op.opId()
                    + ": it is no function value",
                op.origin().span().range());
        }
        requireBoxedSignature(signature, op, "the awaited call");
        String carrier = loadRepresentation(calleeValue, signature, op);
        emitSignatureCheck(op, signature, carrier);
        return carrier;
    }

    /**
     * The lowered body of one cross-module {@code ASYNC_START} callee: the
     * payload's own {@link deal.semantic.ir.ExternalAsyncLink} names the callee
     * module and the export name whose published closure the exported function
     * is, so the call resolves to that unit's lowered function exactly as the
     * direct cross-module call resolves its external entry (C7).
     */
    private FunctionId externalAsyncCallee(KindPayload.AsyncStartPayload payload,
                                           SemanticOp op) {
        deal.semantic.ir.ExternalAsyncLink link = payload.externalAsyncLink();
        if (link == null) {
            throw new deal.codegen.SharedEmissionFailure(
                "LlvmSemanticEmitter rejects the cross-module ASYNC_START of "
                    + op.opId() + ": it records no external async link",
                op.origin().span().range());
        }
        LoweredModuleUnit calleeUnit = session.unitOf(link.calleeModuleId());
        for (SemanticOp candidate : calleeUnit.ops()) {
            if (!(candidate.payload()
                    instanceof KindPayload.ExportPublishPayload published)
                    || !published.name().equals(link.exportName())) {
                continue;
            }
            SemanticOp producer = LlvmStageClassifier.producerOf(calleeUnit,
                published.value());
            if (producer != null
                    && producer.payload()
                        instanceof KindPayload.ClosureNewPayload closure) {
                return closure.function();
            }
        }
        throw new deal.codegen.SharedEmissionFailure(
            "LlvmSemanticEmitter rejects the cross-module ASYNC_START of "
                + op.opId() + ": the module " + link.calleeModuleId()
                + " publishes no lowered function named '"
                + link.exportName() + "'",
            op.origin().span().range());
    }

    /** {@code AWAIT} (C7): observe, pump, validate at the await origin. */
    private void emitAwait(SemanticOp op) {
        KindPayload.AwaitPayload payload = (KindPayload.AwaitPayload) op.payload();
        SemanticOp boundary = unitOp(payload.completionBoundaryOpId());
        recordOrigin(boundary);
        String operation = load(op, "i8*",
            tokenSlot(canonical(payload.token()), "i8*"));
        session.referenceRuntimeEntry("deal_async_await");
        String completed = callRecord(op, "%struct.deal_outcome_data",
            "deal_async_await", "i8* " + operation
                + ", " + session.data.operand(session.data.intern(
                    payload.completionDescriptor().canonicalSpecText()))
                + ", %struct.deal_origin* " + originValue(boundary));
        String error = emitTemporary(op,
            "extractvalue %struct.deal_outcome_data " + completed + ", 0");
        branchOnFailure(op, error, "await");
        Optional<String> representation = resultRepresentation(op);
        if (representation.isEmpty()) {
            return;
        }
        String datum = emitTemporary(op,
            "extractvalue %struct.deal_outcome_data " + completed + ", 1");
        storeResult(op, representation.get(),
            datumToRepresentation(payload.completionDescriptor(), datum, op));
    }

    /** The canonical token behind one (possibly aliasing) token identity. */
    private static AsyncTokenId canonical(AsyncTokenId token) {
        AsyncTokenId current = token;
        while (current instanceof AsyncTokenId.Alias alias) {
            current = alias.referent();
        }
        return current;
    }

    /** The SSA slot holding one asynchronous operation handle. */
    private String tokenSlot(AsyncTokenId token, String type) {
        return tokenSlots.computeIfAbsent(token.tokenId(), id -> {
            String name = "%token" + id;
            allocas.add(name + " = alloca " + type);
            return name;
        });
    }

    /** Emits one carrier per stdlib function value a unit reads. */
    private void emitStdlibCarriers(LoweredModuleUnit initUnit) {
        for (LlvmSession.StdlibRead read : session.stdlibReads(initUnit)) {
            session.referenceRuntimeEntry("deal_fn_create");
            String thunk = "bitcast (" + thunkType() + " @"
                + stdlibThunkName(read.function()) + " to i8*)";
            String signatureText = session.data.operand(session.data.intern(
                read.signature().canonicalSpecText()));
            String carrier = emitTemporary(null, "call "
                + LlvmTypeMapper.FN_POINTER + " @deal_fn_create(i8* "
                + thunk + ", i8** null, i32 0, " + signatureText + ", i32 0)");
            emit("store " + LlvmTypeMapper.FN_POINTER + " " + carrier + ", "
                + LlvmTypeMapper.FN_POINTER + "* @" + read.slot());
        }
    }

    /** The emitted name of one stdlib function value's apply thunk. */
    static String stdlibThunkName(deal.semantic.ir.StdlibFunctionId function) {
        return "S" + function.ordinal();
    }

    /**
     * The apply thunk of one stdlib function value (R7): the checked argument
     * presentations and the call origin reach the one runtime dispatch entry,
     * which owns the parameter and return crossings, and its persistent datum
     * result is the thunk's own completion.
     */
    String emitStdlibThunk(deal.semantic.ir.StdlibFunctionId function) {
        session.referenceRuntimeEntry("deal_std_call");
        StringBuilder out = new StringBuilder();
        out.append("define internal %struct.deal_outcome_data @")
            .append(stdlibThunkName(function))
            .append("(i8* %functionvalue, %struct.deal_check_input* %arguments,"
                + " i32 %count, %struct.deal_origin* %origin) {\n");
        out.append("Lthunk:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%slot = alloca %struct.deal_outcome_data\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("call void @deal_std_call(%struct.deal_outcome_data*"
                + " sret(%struct.deal_outcome_data) %slot, i32 ")
            .append(function.ordinal())
            .append(", %struct.deal_check_input* %arguments, i32 %count,"
                + " %struct.deal_origin* %origin)\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%result = load %struct.deal_outcome_data,"
                + " %struct.deal_outcome_data* %slot\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("ret %struct.deal_outcome_data %result\n}\n");
        return out.toString();
    }

    /**
     * The boxed apply thunk of one lowered function (C5): unboxes the
     * recorded argument presentations at the function's own parameter
     * boundaries, calls the native body with the carrier's captured cells, and
     * boxes the completion into the persistent datum. A carried Error is
     * returned as the outcome's error member, never as data.
     */
    String emitThunk(FunctionId id) {
        for (String entry : THUNK_ENTRIES) {
            session.referenceRuntimeEntry(entry);
        }
        LoweredFunction function = session.function(id);
        List<RuntimeDescriptor> parameters = function.descriptor().paramTypes();
        RuntimeDescriptor result = function.descriptor().returnType();
        int slot = 1;
        Map<Integer, Integer> rootSlotsByParam = new LinkedHashMap<>();
        for (int index = 0; index < parameters.size(); index++) {
            if (LlvmTypeMapper.isManagedValue(parameters.get(index))) {
                rootSlotsByParam.put(index, slot++);
            }
        }
        StringBuilder out = new StringBuilder();
        int thunkScope = session.debug.subprogram(
            session.displayName(session.ownerOf(id)), function.sourceName(),
            thunkName(id), Math.max(1, declarationLine(function)));
        out.append("define internal %struct.deal_outcome_data @")
            .append(thunkName(id))
            .append("(i8* %functionvalue, %struct.deal_check_input* %arguments,"
                + " i32 %count, %struct.deal_origin* %origin) !dbg !")
            .append(thunkScope).append(" {\n");
        out.append("Lthunk:\n");
        String thunkDebug = ", " + LlvmDebugInfo.attach(
            session.debug.location(Math.max(1, declarationLine(function)), 1,
                thunkScope));
        out.append(LlvmIrPrinter.indent(1)).append("call void"
            + " @deal_call_frame_push(").append(frameLabelOperand(id))
            .append(")").append(thunkDebug).append('\n');
        out.append(LlvmIrPrinter.indent(1)).append("call void"
            + " @deal_root_frame_enter(i32 ").append(slot).append(")")
            .append(thunkDebug).append('\n');
        out.append(LlvmIrPrinter.indent(1)).append("call void"
            + " @deal_root_frame_write(i32 0, i8* %functionvalue)\n");
        out.append(LlvmIrPrinter.indent(1)).append("%carrier = bitcast i8*"
            + " %functionvalue to %struct.deal_fn*\n");
        out.append(LlvmIrPrinter.indent(1)).append("%arity = icmp eq i32"
            + " %count, ").append(parameters.size()).append('\n');
        out.append(LlvmIrPrinter.indent(1)).append("br i1 %arity, label %bind,"
            + " label %badcount\n");
        out.append("badcount:\n");
        out.append(LlvmIrPrinter.indent(1)).append("call void"
            + " @deal_platform_fault(").append(session.data.operand(
                session.data.intern("function value: argument count")))
            .append(")\n");
        out.append(LlvmIrPrinter.indent(1)).append("unreachable\n");
        out.append("bind:\n");
        for (int index = 0; index < parameters.size(); index++) {
            thunkParameter(out, index, parameters.get(index), rootSlotsByParam);
        }
        StringBuilder arguments = new StringBuilder();
        for (int index = 0; index < parameters.size(); index++) {
            RuntimeDescriptor descriptor = parameters.get(index);
            String value = "%parameter" + index;
            if (descriptor instanceof RuntimeDescriptor.Boolean) {
                String widened = "%argument" + index;
                out.append(LlvmIrPrinter.indent(1)).append(widened)
                    .append(" = zext i1 ").append(value).append(" to i8\n");
                value = widened;
            }
            if (index > 0) {
                arguments.append(", ");
            }
            arguments.append(LlvmTypeMapper.abiParameterType(descriptor))
                .append(' ').append(value);
        }
        // The thunk is the carrier's dispatch shim, not a DEAL activation: its
        // own frame label covers the argument checks and is popped before the
        // body runs, so the body's own label is the single frame the callee
        // contributes to an Error snapshot (C8/R5).
        out.append(LlvmIrPrinter.indent(1)).append("call void"
            + " @deal_call_frame_pop()\n");
        String outcome = LlvmTypeMapper.outcomeType(result);
        out.append(LlvmIrPrinter.indent(1)).append("%body = call ").append(outcome)
            .append(" @F").append(id.id()).append("(%struct.deal_fn* %carrier")
            .append(arguments.length() == 0 ? "" : ", " + arguments)
            .append(")").append(thunkDebug).append('\n');
        out.append(LlvmIrPrinter.indent(1)).append("%bodyerror = extractvalue ")
            .append(outcome).append(" %body, 0\n");
        out.append(LlvmIrPrinter.indent(1)).append("%bodyfailed = icmp ne i8*"
            + " %bodyerror, null\n");
        out.append(LlvmIrPrinter.indent(1)).append("br i1 %bodyfailed, label"
            + " %bodyfail, label %bodyok\n");
        out.append("bodyfail:\n");
        out.append(LlvmIrPrinter.indent(1)).append("%failed = insertvalue"
            + " %struct.deal_outcome_data zeroinitializer, i8* %bodyerror, 0\n");
        out.append(LlvmIrPrinter.indent(1)).append("call void"
            + " @deal_root_frame_leave()\n");
        out.append(LlvmIrPrinter.indent(1)).append("ret %struct.deal_outcome_data"
            + " %failed\n");
        out.append("bodyok:\n");
        String boxed = thunkBox(out, result, outcome, "%body");
        out.append(LlvmIrPrinter.indent(1)).append("%completed = insertvalue"
            + " %struct.deal_outcome_data zeroinitializer, %struct.deal_data ")
            .append(boxed).append(", 1\n");
        out.append(LlvmIrPrinter.indent(1)).append("call void"
            + " @deal_root_frame_leave()\n");
        out.append(LlvmIrPrinter.indent(1)).append("ret %struct.deal_outcome_data"
            + " %completed\n");
        out.append("}\n");
        return out.toString();
    }

    /** The runtime entries every boxed apply thunk references. */
    private static final List<String> THUNK_ENTRIES = List.of(
        "deal_call_frame_push",
        "deal_call_frame_pop",
        "deal_root_frame_enter",
        "deal_root_frame_leave",
        "deal_root_frame_write",
        "deal_root_frame_write_data",
        "deal_check_number",
        "deal_check_boolean",
        "deal_check_string",
        "deal_check_value",
        "deal_descriptor_intern",
        "deal_data_of_number",
        "deal_data_of_boolean",
        "deal_data_of_null",
        "deal_data_of_undefined",
        "deal_data_of_reference",
        "deal_platform_fault",
        "deal_fn_capture");

    /** Unboxes one recorded argument presentation in the thunk. */
    private void thunkParameter(StringBuilder out, int index,
                                RuntimeDescriptor descriptor,
                                Map<Integer, Integer> rootSlotsByParam) {
        out.append(LlvmIrPrinter.indent(1)).append("%input").append(index)
            .append(" = getelementptr %struct.deal_check_input,"
                + " %struct.deal_check_input* %arguments, i64 ").append(index)
            .append('\n');
        out.append(LlvmIrPrinter.indent(1)).append("%datum").append(index)
            .append(" = getelementptr %struct.deal_check_input,"
                + " %struct.deal_check_input* %input").append(index)
            .append(", i32 0, i32 2\n");
        out.append(LlvmIrPrinter.indent(1)).append("%ctx").append(index)
            .append(" = alloca %struct.deal_check_context\n");
        out.append(LlvmIrPrinter.indent(1)).append("%context").append(index)
            .append(" = insertvalue %struct.deal_check_context"
                + " zeroinitializer, %struct.deal_origin* %origin, 0\n");
        out.append(LlvmIrPrinter.indent(1)).append("%indexed").append(index)
            .append(" = insertvalue %struct.deal_check_context %context")
            .append(index).append(", i32 ").append(index + 1).append(", 1\n");
        out.append(LlvmIrPrinter.indent(1)).append("store"
            + " %struct.deal_check_context %indexed").append(index).append(","
            + " %struct.deal_check_context* %ctx").append(index).append('\n');
        String errorName = "%error" + index;
        switch (descriptor) {
            case RuntimeDescriptor.Number _ -> {
                out.append(LlvmIrPrinter.indent(1)).append("%checked")
                    .append(index).append(" = call %struct.deal_outcome_number"
                        + " @deal_check_number(%struct.deal_data* %datum")
                    .append(index).append(", %struct.deal_check_context* %ctx")
                    .append(index).append(")\n");
                out.append(LlvmIrPrinter.indent(1)).append(errorName)
                    .append(" = extractvalue %struct.deal_outcome_number"
                        + " %checked").append(index).append(", 0\n");
                out.append(LlvmIrPrinter.indent(1)).append("%parameter")
                    .append(index).append(" = extractvalue"
                        + " %struct.deal_outcome_number %checked")
                    .append(index).append(", 1\n");
            }
            case RuntimeDescriptor.Boolean _ -> {
                out.append(LlvmIrPrinter.indent(1)).append("%checked")
                    .append(index).append(" = call %struct.deal_outcome_boolean"
                        + " @deal_check_boolean(%struct.deal_data* %datum")
                    .append(index).append(", %struct.deal_check_context* %ctx")
                    .append(index).append(")\n");
                out.append(LlvmIrPrinter.indent(1)).append(errorName)
                    .append(" = extractvalue %struct.deal_outcome_boolean"
                        + " %checked").append(index).append(", 0\n");
                out.append(LlvmIrPrinter.indent(1)).append("%checkedvalue")
                    .append(index).append(" = extractvalue"
                        + " %struct.deal_outcome_boolean %checked")
                    .append(index).append(", 1\n");
                out.append(LlvmIrPrinter.indent(1)).append("%parameter")
                    .append(index).append(" = icmp ne i8 %checkedvalue")
                    .append(index).append(", 0\n");
            }
            case RuntimeDescriptor.String _ -> {
                out.append(LlvmIrPrinter.indent(1)).append("%checked")
                    .append(index).append(" = call %struct.deal_outcome_string"
                        + " @deal_check_string(%struct.deal_data* %datum")
                    .append(index).append(", %struct.deal_check_context* %ctx")
                    .append(index).append(")\n");
                out.append(LlvmIrPrinter.indent(1)).append(errorName)
                    .append(" = extractvalue %struct.deal_outcome_string"
                        + " %checked").append(index).append(", 0\n");
                out.append(LlvmIrPrinter.indent(1)).append("%checkedvalue")
                    .append(index).append(" = extractvalue"
                        + " %struct.deal_outcome_string %checked")
                    .append(index).append(", 1\n");
                out.append(LlvmIrPrinter.indent(1)).append("%parameter")
                    .append(index).append(" = bitcast i8* %checkedvalue")
                    .append(index).append(" to %struct.deal_string*\n");
            }
            case RuntimeDescriptor.Unknown _ -> {
                thunkSignatureCheck(out, index, descriptor);
                out.append(LlvmIrPrinter.indent(1)).append("%parameter")
                    .append(index).append(" = load %struct.deal_data,"
                        + " %struct.deal_data* %datum").append(index).append('\n');
            }
            case RuntimeDescriptor.Func _ -> {
                thunkSignatureCheck(out, index, descriptor);
                out.append(LlvmIrPrinter.indent(1)).append("%reference")
                    .append(index).append(" = getelementptr %struct.deal_data,"
                        + " %struct.deal_data* %datum").append(index)
                    .append(", i32 0, i32 3\n");
                out.append(LlvmIrPrinter.indent(1)).append("%referent")
                    .append(index).append(" = load i8*, i8** %reference")
                    .append(index).append('\n');
                out.append(LlvmIrPrinter.indent(1)).append("%parameter")
                    .append(index).append(" = bitcast i8* %referent")
                    .append(index).append(" to %struct.deal_fn*\n");
            }
            case RuntimeDescriptor.Class classType
                when classType.classId().equals(deal.semantic.ir.ClassId.ERROR) -> {
                thunkSignatureCheck(out, index, descriptor);
                out.append(LlvmIrPrinter.indent(1)).append("%reference")
                    .append(index).append(" = getelementptr %struct.deal_data,"
                        + " %struct.deal_data* %datum").append(index)
                    .append(", i32 0, i32 3\n");
                out.append(LlvmIrPrinter.indent(1)).append("%referent")
                    .append(index).append(" = load i8*, i8** %reference")
                    .append(index).append('\n');
                out.append(LlvmIrPrinter.indent(1)).append("%parameter")
                    .append(index).append(" = bitcast i8* %referent")
                    .append(index).append(" to %struct.deal_error*\n");
            }
            case RuntimeDescriptor.Array _, RuntimeDescriptor.Table _,
                 RuntimeDescriptor.Class _ -> {
                thunkSignatureCheck(out, index, descriptor);
                out.append(LlvmIrPrinter.indent(1)).append("%reference")
                    .append(index).append(" = getelementptr %struct.deal_data,"
                        + " %struct.deal_data* %datum").append(index)
                    .append(", i32 0, i32 3\n");
                out.append(LlvmIrPrinter.indent(1)).append("%referent")
                    .append(index).append(" = load i8*, i8** %reference")
                    .append(index).append('\n');
                out.append(LlvmIrPrinter.indent(1)).append("%parameter")
                    .append(index).append(" = bitcast i8* %referent")
                    .append(index).append(" to ")
                    .append(stagedRepresentation(descriptor)).append('\n');
            }
            case RuntimeDescriptor.Nullable nullable ->
                thunkUnboxNullish(out, index, nullable, errorName);
            default -> throw new IllegalStateException("the boxed apply thunk"
                + " has no unboxing for '" + descriptor.canonicalSpecText()
                + "'");
        }
        out.append(LlvmIrPrinter.indent(1)).append("%failed").append(index)
            .append(" = icmp ne i8* ").append(errorName).append(", null\n");
        out.append(LlvmIrPrinter.indent(1)).append("br i1 %failed")
            .append(index).append(", label %paramfail").append(index)
            .append(", label %paramok").append(index).append('\n');
        out.append("paramfail").append(index).append(":\n");
        out.append(LlvmIrPrinter.indent(1)).append("%parameterfailed")
            .append(index).append(" = insertvalue %struct.deal_outcome_data"
                + " zeroinitializer, i8* ").append(errorName).append(", 0\n");
        out.append(LlvmIrPrinter.indent(1)).append("call void"
            + " @deal_root_frame_leave()\n");
        out.append(LlvmIrPrinter.indent(1)).append("call void"
            + " @deal_call_frame_pop()\n");
        out.append(LlvmIrPrinter.indent(1)).append("ret"
            + " %struct.deal_outcome_data %parameterfailed").append(index)
            .append('\n');
        out.append("paramok").append(index).append(":\n");
        Integer rootIndex = rootSlotsByParam.get(index);
        if (rootIndex != null) {
            if (descriptor instanceof RuntimeDescriptor.Nullable
                    || descriptor instanceof RuntimeDescriptor.Unknown) {
                // The tag-aware entry roots exactly the datum's managed
                // payload, so a nullish or unknown position never presents
                // scalar bits or a singleton as a heap address (M4).
                out.append(LlvmIrPrinter.indent(1)).append("call void"
                    + " @deal_root_frame_write_data(i32 ").append(rootIndex)
                    .append(", %struct.deal_data* %datum").append(index)
                    .append(")\n");
            } else {
                String pointer = "%parameter" + index;
                if (LlvmTypeMapper.isManagedPointer(descriptor)
                        || descriptor instanceof RuntimeDescriptor.Func) {
                    pointer = "%rooted" + index;
                    out.append(LlvmIrPrinter.indent(1)).append(pointer)
                        .append(" = bitcast ")
                        .append(stagedRepresentation(descriptor)).append(' ')
                        .append("%parameter").append(index).append(" to i8*\n");
                }
                out.append(LlvmIrPrinter.indent(1)).append("call void"
                    + " @deal_root_frame_write(i32 ").append(rootIndex)
                    .append(", i8* ").append(pointer).append(")\n");
            }
        }
    }

    /** Unboxes one nullish argument presentation in the thunk. */
    private void thunkUnboxNullish(StringBuilder out, int index,
                                   RuntimeDescriptor.Nullable nullable,
                                   String errorName) {
        thunkSignatureCheck(out, index, nullable);
        String representation = stagedRepresentation(nullable);
        out.append(LlvmIrPrinter.indent(1)).append("%nullishdatum").append(index)
            .append(" = load %struct.deal_data, %struct.deal_data* %datum")
            .append(index).append('\n');
        String tag = "%nullishtag" + index;
        out.append(LlvmIrPrinter.indent(1)).append(tag)
            .append(" = extractvalue %struct.deal_data %nullishdatum")
            .append(index).append(", 0\n");
        String isNull = "%nullishnull" + index;
        String isUndefined = "%nullishundefined" + index;
        out.append(LlvmIrPrinter.indent(1)).append(isNull)
            .append(" = icmp eq i32 ").append(tag).append(", ")
            .append(LlvmTypeMapper.DATA_NULL).append('\n');
        out.append(LlvmIrPrinter.indent(1)).append(isUndefined)
            .append(" = icmp eq i32 ").append(tag).append(", ")
            .append(LlvmTypeMapper.DATA_UNDEFINED).append('\n');
        if (representation.equals(LlvmTypeMapper.REFERENCE_NULLISH)) {
            String reference = "%nullishreference" + index;
            out.append(LlvmIrPrinter.indent(1)).append(reference)
                .append(" = extractvalue %struct.deal_data %nullishdatum")
                .append(index).append(", 3\n");
            String nullPointer = "%nullishnullptr" + index;
            String undefinedPointer = "%nullishundefinedptr" + index;
            session.referenceExternalGlobal("deal_null_singleton");
            session.referenceExternalGlobal("deal_undefined_singleton");
            out.append(LlvmIrPrinter.indent(1)).append(nullPointer)
                .append(" = load i8*, i8** @deal_null_singleton\n");
            out.append(LlvmIrPrinter.indent(1)).append(undefinedPointer)
                .append(" = load i8*, i8** @deal_undefined_singleton\n");
            String withNull = "%nullishwithnull" + index;
            out.append(LlvmIrPrinter.indent(1)).append(withNull)
                .append(" = select i1 ").append(isNull).append(", i8* ")
                .append(nullPointer).append(", i8* ").append(reference).append('\n');
            out.append(LlvmIrPrinter.indent(1)).append("%parameter").append(index)
                .append(" = select i1 ").append(isUndefined).append(", i8* ")
                .append(undefinedPointer).append(", i8* ").append(withNull)
                .append('\n');
            return;
        }
        boolean number = representation.equals(LlvmTypeMapper.NULLISH_NUMBER);
        String payload = "%nullishpayload" + index;
        out.append(LlvmIrPrinter.indent(1)).append(payload)
            .append(" = extractvalue %struct.deal_data %nullishdatum")
            .append(index).append(", 2\n");
        int baseTag = nullable.inner() instanceof RuntimeDescriptor.Boolean
            ? LlvmTypeMapper.DATA_BOOLEAN : LlvmTypeMapper.DATA_NUMBER;
        String isBase = "%nullishbase" + index;
        out.append(LlvmIrPrinter.indent(1)).append(isBase)
            .append(" = icmp eq i32 ").append(tag).append(", ").append(baseTag)
            .append('\n');
        String zero = number ? "double 0.0" : "i1 false";
        String value = number ? "double " : "i1 ";
        String base = payload;
        if (!number) {
            base = "%nullishbool" + index;
            out.append(LlvmIrPrinter.indent(1)).append(base)
                .append(" = fcmp une double ").append(payload)
                .append(", 0.0\n");
        }
        String baseSelected = "%nullishbasevalue" + index;
        out.append(LlvmIrPrinter.indent(1)).append(baseSelected)
            .append(" = select i1 ").append(isBase).append(", ").append(value)
            .append(base).append(", ").append(zero).append('\n');
        String undefinedSelected = "%nullishundefinedtag" + index;
        out.append(LlvmIrPrinter.indent(1)).append(undefinedSelected)
            .append(" = select i1 ").append(isUndefined).append(", i32 ")
            .append(LlvmTypeMapper.NULLISH_UNDEFINED).append(", i32 ")
            .append(LlvmTypeMapper.NULLISH_BASE).append('\n');
        String discriminant = "%nullishdiscriminant" + index;
        out.append(LlvmIrPrinter.indent(1)).append(discriminant)
            .append(" = select i1 ").append(isNull).append(", i32 ")
            .append(LlvmTypeMapper.NULLISH_NULL).append(", i32 ")
            .append(undefinedSelected).append('\n');
        String withTag = "%nullishwithtag" + index;
        out.append(LlvmIrPrinter.indent(1)).append(withTag)
            .append(" = insertvalue ").append(representation).append(" { i32 0, ")
            .append(zero).append(" }, i32 ").append(discriminant).append(", 0\n");
        out.append(LlvmIrPrinter.indent(1)).append("%parameter").append(index)
            .append(" = insertvalue ").append(representation).append(' ')
            .append(withTag).append(", ").append(value).append(baseSelected)
            .append(", 1\n");
    }

    /** The signature check of one function-typed argument presentation. */
    private void thunkSignatureCheck(StringBuilder out, int index,
                                     RuntimeDescriptor descriptor) {
        String text = descriptor.canonicalSpecText();
        out.append(LlvmIrPrinter.indent(1)).append("%atom").append(index)
            .append(" = call %struct.deal_descriptor* @deal_descriptor_intern(")
            .append(session.data.operand(session.data.intern(text)))
            .append(")\n");
        String boundary = Integer.toString(checkBoundaryKind(descriptor));
        out.append(LlvmIrPrinter.indent(1)).append("%checked").append(index)
            .append(" = call %struct.deal_outcome @deal_check_value("
                + "%struct.deal_descriptor* %atom").append(index)
            .append(", %struct.deal_check_input* byval(%struct.deal_check_input)"
                + " %input").append(index).append(", i32 ").append(boundary)
            .append(", %struct.deal_check_context* %ctx").append(index)
            .append(")\n");
        out.append(LlvmIrPrinter.indent(1)).append("%error").append(index)
            .append(" = extractvalue %struct.deal_outcome %checked")
            .append(index).append(", 0\n");
    }

    /** Boxes one completed native value into the persistent datum. */
    private String thunkBox(StringBuilder out, RuntimeDescriptor result,
                            String outcome, String aggregate) {
        out.append(LlvmIrPrinter.indent(1))
            .append("%boxslot = alloca %struct.deal_data\n");
        String entry;
        String arguments;
        switch (result) {
            case RuntimeDescriptor.Number _ -> {
                out.append(LlvmIrPrinter.indent(1)).append("%number = extractvalue ")
                    .append(outcome).append(' ').append(aggregate).append(", 1\n");
                entry = "deal_data_of_number";
                arguments = "double %number";
            }
            case RuntimeDescriptor.Boolean _ -> {
                out.append(LlvmIrPrinter.indent(1)).append("%truthfield = extractvalue ")
                    .append(outcome).append(' ').append(aggregate).append(", 1\n");
                out.append(LlvmIrPrinter.indent(1)).append("%truthbyte")
                    .append(" = zext i8 %truthfield to i32\n");
                entry = "deal_data_of_boolean";
                arguments = "i32 %truthbyte";
            }
            case RuntimeDescriptor.String _ -> {
                out.append(LlvmIrPrinter.indent(1)).append("%text = extractvalue ")
                    .append(outcome).append(' ').append(aggregate).append(", 1\n");
                entry = "deal_data_of_reference";
                arguments = "i32 " + LlvmTypeMapper.DATA_STRING + ", i8* %text";
            }
            case RuntimeDescriptor.Unknown _ -> {
                out.append(LlvmIrPrinter.indent(1)).append("%boxed")
                    .append(" = extractvalue ").append(outcome).append(' ')
                    .append(aggregate).append(", 1\n");
                return "%boxed";
            }
            case RuntimeDescriptor.Func _ -> {
                out.append(LlvmIrPrinter.indent(1)).append("%reference = extractvalue ")
                    .append(outcome).append(' ').append(aggregate).append(", 1\n");
                entry = "deal_data_of_reference";
                arguments = "i32 " + LlvmTypeMapper.DATA_FUNCTION
                    + ", i8* %reference";
            }
            case RuntimeDescriptor.Class classType
                when classType.classId().equals(deal.semantic.ir.ClassId.ERROR) -> {
                out.append(LlvmIrPrinter.indent(1)).append("%reference = extractvalue ")
                    .append(outcome).append(' ').append(aggregate).append(", 1\n");
                entry = "deal_data_of_reference";
                arguments = "i32 " + LlvmTypeMapper.DATA_ERROR
                    + ", i8* %reference";
            }
            case RuntimeDescriptor.Array _, RuntimeDescriptor.Table _,
                 RuntimeDescriptor.Class _ -> {
                // A container or class completion is the managed pointer its
                // datum tag names (C3); the outcome already carries it as the
                // one `i8*` slot of a managed reference.
                int tag = result instanceof RuntimeDescriptor.Array
                    ? LlvmTypeMapper.DATA_ARRAY
                    : result instanceof RuntimeDescriptor.Table
                        ? LlvmTypeMapper.DATA_TABLE
                        : LlvmTypeMapper.DATA_CLASS;
                out.append(LlvmIrPrinter.indent(1)).append("%reference = extractvalue ")
                    .append(outcome).append(' ').append(aggregate).append(", 1\n");
                entry = "deal_data_of_reference";
                arguments = "i32 " + tag + ", i8* %reference";
            }
            case RuntimeDescriptor.Void _, RuntimeDescriptor.Undefined _ -> {
                entry = "deal_data_of_undefined";
                arguments = "";
            }
            case RuntimeDescriptor.Null _ -> {
                entry = "deal_data_of_null";
                arguments = "";
            }
            case RuntimeDescriptor.Nullable nullable -> {
                return thunkBoxNullish(out, nullable, outcome, aggregate);
            }
            default -> throw new IllegalStateException("the boxed apply thunk"
                + " has no boxing for '" + result.canonicalSpecText() + "'");
        }
        out.append(LlvmIrPrinter.indent(1)).append("call void @").append(entry)
            .append("(%struct.deal_data* sret(%struct.deal_data) %boxslot")
            .append(arguments.isEmpty() ? "" : ", " + arguments).append(")\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%boxed = load %struct.deal_data, %struct.deal_data* %boxslot\n");
        return "%boxed";
    }

    /** The datum tag of one non-nullish descriptor (C3/R3). */
    private static int datumTagOf(RuntimeDescriptor descriptor) {
        if (descriptor instanceof RuntimeDescriptor.String) {
            return LlvmTypeMapper.DATA_STRING;
        }
        if (descriptor instanceof RuntimeDescriptor.Array) {
            return LlvmTypeMapper.DATA_ARRAY;
        }
        if (descriptor instanceof RuntimeDescriptor.Table) {
            return LlvmTypeMapper.DATA_TABLE;
        }
        if (descriptor instanceof RuntimeDescriptor.Func) {
            return LlvmTypeMapper.DATA_FUNCTION;
        }
        if (descriptor instanceof RuntimeDescriptor.Class classType) {
            return classType.classId().equals(deal.semantic.ir.ClassId.ERROR)
                ? LlvmTypeMapper.DATA_ERROR : LlvmTypeMapper.DATA_CLASS;
        }
        if (descriptor instanceof RuntimeDescriptor.Number) {
            return LlvmTypeMapper.DATA_NUMBER;
        }
        if (descriptor instanceof RuntimeDescriptor.Boolean) {
            return LlvmTypeMapper.DATA_BOOLEAN;
        }
        if (descriptor instanceof RuntimeDescriptor.Null) {
            return LlvmTypeMapper.DATA_NULL;
        }
        return LlvmTypeMapper.DATA_UNDEFINED;
    }

    /** Boxes one nullish completion into the persistent datum. */
    private String thunkBoxNullish(StringBuilder out,
                                   RuntimeDescriptor.Nullable nullable,
                                   String outcome, String aggregate) {
        String representation = stagedRepresentation(nullable);
        boolean reference = representation.equals(
            LlvmTypeMapper.REFERENCE_NULLISH);
        boolean number = representation.equals(LlvmTypeMapper.NULLISH_NUMBER);
        out.append(LlvmIrPrinter.indent(1)).append("%nullishresult = extractvalue ")
            .append(outcome).append(' ').append(aggregate).append(", 1\n");
        if (reference) {
            // The reference-nullish arm tests the two distinct singletons by
            // identity, so this boxed body declares its own entries (C9).
            session.referenceRuntimeEntry("deal_value_is_null");
            session.referenceRuntimeEntry("deal_value_is_undefined");
            out.append(LlvmIrPrinter.indent(1)).append("%nullishnull")
                .append(" = call i32 @deal_value_is_null(i8* %nullishresult)\n");
            out.append(LlvmIrPrinter.indent(1)).append("%nullishundefined")
                .append(" = call i32 @deal_value_is_undefined(i8* %nullishresult)\n");
            out.append(LlvmIrPrinter.indent(1)).append("%nullishnullflag")
                .append(" = icmp ne i32 %nullishnull, 0\n");
            out.append(LlvmIrPrinter.indent(1)).append("%nullishundefinedflag")
                .append(" = icmp ne i32 %nullishundefined, 0\n");
            String baseTag = Integer.toString(datumTagOf(nullable.inner()));
            out.append(LlvmIrPrinter.indent(1)).append("%nullishundefinedtag")
                .append(" = select i1 %nullishundefinedflag, i32 ")
                .append(LlvmTypeMapper.DATA_UNDEFINED).append(", i32 ")
                .append(baseTag).append('\n');
            out.append(LlvmIrPrinter.indent(1)).append("%nullishdatatag")
                .append(" = select i1 %nullishnullflag, i32 ")
                .append(LlvmTypeMapper.DATA_NULL)
                .append(", i32 %nullishundefinedtag\n");
            out.append(LlvmIrPrinter.indent(1)).append("%nullishreference")
                .append(" = select i1 %nullishnullflag, i8* null, i8* %nullishresult\n");
            out.append(LlvmIrPrinter.indent(1)).append("%nullishwithtag")
                .append(" = insertvalue %struct.deal_data { i32 0, i32 0, double 0.0, i8* null }, i32 %nullishdatatag, 0\n");
            out.append(LlvmIrPrinter.indent(1)).append("%boxed")
                .append(" = insertvalue %struct.deal_data %nullishwithtag, i8*"
                    + " %nullishreference, 3\n");
            return "%boxed";
        }
        out.append(LlvmIrPrinter.indent(1)).append("%nullishtag")
            .append(" = extractvalue ").append(representation)
            .append(" %nullishresult, 0\n");
        out.append(LlvmIrPrinter.indent(1)).append("%nullishnullflag")
            .append(" = icmp eq i32 %nullishtag, ")
            .append(LlvmTypeMapper.NULLISH_NULL).append('\n');
        out.append(LlvmIrPrinter.indent(1)).append("%nullishundefinedflag")
            .append(" = icmp eq i32 %nullishtag, ")
            .append(LlvmTypeMapper.NULLISH_UNDEFINED).append('\n');
        out.append(LlvmIrPrinter.indent(1)).append("%nullishbase")
            .append(" = extractvalue ").append(representation)
            .append(" %nullishresult, 1\n");
        String baseNumber = "%nullishbasenumber";
        out.append(LlvmIrPrinter.indent(1)).append(baseNumber)
            .append(" = ").append(number
                ? "select i1 %nullishnullflag, double 0.0, double %nullishbase"
                : "select i1 %nullishnullflag, i1 false, i1 %nullishbase")
            .append('\n');
        String payload;
        if (number) {
            payload = baseNumber;
        } else {
            payload = "%nullishbasenumber";
            out.append(LlvmIrPrinter.indent(1)).append("%nullishpayload")
                .append(" = zext i1 %nullishbasenumber to i32\n");
            out.append(LlvmIrPrinter.indent(1)).append("%nullishpayloaddouble")
                .append(" = uitofp i32 %nullishpayload to double\n");
            payload = "%nullishpayloaddouble";
        }
        int baseTag = nullable.inner() instanceof RuntimeDescriptor.Boolean
            ? LlvmTypeMapper.DATA_BOOLEAN : LlvmTypeMapper.DATA_NUMBER;
        out.append(LlvmIrPrinter.indent(1)).append("%nullishundefinedtag")
            .append(" = select i1 %nullishundefinedflag, i32 ")
            .append(LlvmTypeMapper.DATA_UNDEFINED).append(", i32 ")
            .append(baseTag).append('\n');
        out.append(LlvmIrPrinter.indent(1)).append("%nullishdatatag")
            .append(" = select i1 %nullishnullflag, i32 ")
            .append(LlvmTypeMapper.DATA_NULL)
            .append(", i32 %nullishundefinedtag\n");
        out.append(LlvmIrPrinter.indent(1)).append("%nullishwithtag")
            .append(" = insertvalue %struct.deal_data { i32 0, i32 0, double 0.0, i8* null }, i32 %nullishdatatag, 0\n");
        out.append(LlvmIrPrinter.indent(1)).append("%boxed")
            .append(" = insertvalue %struct.deal_data %nullishwithtag, double ")
            .append(payload).append(", 2\n");
        return "%boxed";
    }

    /** The C4 outcome value-slot type of one declared result descriptor. */
    private static String outcomeSlotType(RuntimeDescriptor descriptor) {
        if (descriptor instanceof RuntimeDescriptor.Boolean) {
            return LlvmTypeMapper.BOOLEAN_ABI;
        }
        if (LlvmTypeMapper.isManagedPointer(descriptor)
                || descriptor instanceof RuntimeDescriptor.Func) {
            return "i8*";
        }
        return stagedRepresentation(descriptor);
    }

    /**
     * The ABI spelling of one value in a C4 outcome slot: the boolean 0/1
     * byte, the one pointer slot of every managed representation, and the
     * value itself for a scalar or a persistent datum.
     */
    private String abiValue(RuntimeDescriptor descriptor, String value,
                            SemanticOp op) {
        if (descriptor instanceof RuntimeDescriptor.Boolean) {
            return emitTemporary(op, "zext i1 " + value + " to "
                + LlvmTypeMapper.BOOLEAN_ABI);
        }
        String representation = stagedRepresentation(descriptor);
        if (representation.equals("i8*")) {
            return value;
        }
        if (LlvmTypeMapper.isManagedPointer(descriptor)
                || descriptor instanceof RuntimeDescriptor.Func) {
            return emitTemporary(op, "bitcast " + representation + " " + value
                + " to i8*");
        }
        return value;
    }

    /**
     * The SSA form of one returned ABI payload (C3's boolean byte). The outcome
     * value slot already holds the staged representation, so only the managed
     * pointer shapes whose SSA spelling is a distinct pointer type need the one
     * cast: a {@code string} is {@code %struct.deal_string*}, a class instance
     * its own typed record pointer (the builtin {@code Error} the runtime's
     * {@code deal_error*}), and both are carried as {@code i8*}. A
     * managed-pointer representation that is already {@code i8*} is used
     * unchanged, and no nullish result is cast: a scalar-nullish aggregate
     * ({@code { i32, double }} / {@code { i32, i1 }}) and a reference nullish
     * pointer are already the outcome slot's own type, exactly like a
     * {@code double}, a persistent datum and a code pointer.
     */
    private String narrowFromAbi(RuntimeDescriptor descriptor, String value,
                                 SemanticOp op) {
        if (descriptor instanceof RuntimeDescriptor.Boolean) {
            return emitTemporary(op, "trunc i8 " + value + " to "
                + LlvmTypeMapper.BOOLEAN_SSA);
        }
        if (LlvmTypeMapper.isManagedPointer(descriptor)) {
            String representation = stagedRepresentation(descriptor);
            if (representation.equals("i8*")) {
                return value;
            }
            return emitTemporary(op, "bitcast i8* " + value + " to "
                + representation);
        }
        // A nullish outcome slot already holds its staged representation (the
        // `i8*` of a reference nullish position or the tagged scalar struct),
        // so no nullish result is ever cast.
        if (descriptor instanceof RuntimeDescriptor.Func) {
            return emitTemporary(op, "bitcast i8* " + value + " to "
                + LlvmTypeMapper.FN_POINTER);
        }
        return value;
    }

    /** The lowered function one static or statically-registered call resolves to. */
    private FunctionId calleeOf(KindPayload.CallPayload payload) {
        if (payload.callee() instanceof KindPayload.CallCallee.Indirect indirect) {
            deal.semantic.ir.FunctionExecutionBinding binding =
                session.bindingOfIdentity(indirect.callee());
            if (binding instanceof deal.semantic.ir.FunctionExecutionBinding
                    .LoweredBody body) {
                return body.functionId();
            }
            throw new IllegalStateException("the indirect call " + payload
                + " resolves no lowered body");
        }
        if (!(payload.callee() instanceof KindPayload.CallCallee.Static callee)) {
            throw new IllegalStateException(
                "the staged call carries no static callee");
        }
        return switch (callee.binding()) {
            case deal.semantic.ir.FunctionExecutionBinding.LoweredBody body ->
                body.functionId();
            case deal.semantic.ir.FunctionExecutionBinding.ExternalFunction ignored -> {
                OpId reference = payload.externalEntryRef();
                if (reference == null) {
                    throw new IllegalStateException(
                        "the external call records no external-entry reference");
                }
                SemanticOp entry = session.unitOf(reference.module()).ops()
                    .stream()
                    .filter(candidate -> candidate.opId().equals(reference))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                        "the external-entry reference " + reference
                            + " names no recorded op"));
                yield ((KindPayload.ExternalEntryPayload) entry.payload())
                    .function();
            }
            default -> throw new IllegalStateException(
                "the staged call binds an unsupported callee " + callee);
        };
    }

    private SemanticOp unitOp(OpId id) {
        for (SemanticOp candidate : session.unitOf(id.module()).ops()) {
            if (candidate.opId().equals(id)) {
                return candidate;
            }
        }
        throw new IllegalStateException("the op " + id + " is not recorded");
    }

    /**
     * {@code BRANCH} (C6, the shared {@code BranchPayload} traversal): the
     * condition is evaluated once, then the recorded selected block runs.
     * For the logical selectors the selected block holds the right operand and
     * runs only when the left operand does not decide the result — AND: left
     * false decides {@code false} and skips the block, so the block is entered
     * on true; OR: left true decides {@code true} and skips the block, so the
     * block is entered on false. The result slot keeps the left operand's own
     * value on the skipped path, and the block overwrites it with the right
     * operand's value on the evaluated path.
     */
    private void emitBranch(SemanticOp op) {
        KindPayload.BranchPayload payload =
            (KindPayload.BranchPayload) op.payload();
        String condition = loadValue(payload.condition(),
            LlvmTypeMapper.BOOLEAN_SSA, op);
        boolean logical = payload.selector()
            == deal.semantic.ir.ControlSelector.LOGICAL_AND
            || payload.selector() == deal.semantic.ir.ControlSelector.LOGICAL_OR;
        if (logical) {
            storeResult(op, LlvmTypeMapper.BOOLEAN_SSA, condition);
        }
        String join = newLabel("join");
        String selected = blockLabel(payload.selectedBlock());
        String alternate = payload.alternateBlock() == null
            ? join : blockLabel(payload.alternateBlock());
        boolean enterOnTrue = payload.selector()
            != deal.semantic.ir.ControlSelector.LOGICAL_OR;
        Optional<Boolean> decided = constantBoolean(payload.condition());
        if (decided.isPresent()) {
            // A literal condition decides the recorded traversal here, so the
            // untaken child block gains no CFG predecessor.
            terminate("br label %"
                + (decided.get() == enterOnTrue ? selected : alternate));
        } else {
            terminate("br i1 " + condition + ", label %"
                + (enterOnTrue ? selected : alternate) + ", label %"
                + (enterOnTrue ? alternate : selected));
        }
        emitBlock(payload.selectedBlock(), join);
        if (payload.alternateBlock() != null) {
            emitBlock(payload.alternateBlock(), join);
        }
        startSegment(join);
    }

    private void emitLoop(SemanticOp op) {
        KindPayload.LoopPayload payload = (KindPayload.LoopPayload) op.payload();
        String header = newLabel("loop");
        String check = newLabel("loopcheck");
        String exit = newLabel("loopexit");
        terminate("br label %" + header);
        // The recorded init block re-runs every iteration, so a continue
        // re-evaluates the condition exactly like the recorded traversal.
        startSegment(header);
        emitBlock(payload.initBlock(), check);
        startSegment(check);
        String condition = loadValue(payload.condition(),
            LlvmTypeMapper.BOOLEAN_SSA, op);
        String body = blockLabel(payload.bodyBlock());
        Optional<Boolean> decided = constantBoolean(payload.condition());
        if (decided.isPresent()) {
            // The recorded condition is a boolean literal, so the recorded
            // traversal is decided here: the untaken edge is not published as
            // a CFG predecessor.
            terminate("br label %" + (decided.get() ? body : exit));
        } else {
            terminate("br i1 " + condition + ", label %" + body + ", label %"
                + exit);
        }
        String update = payload.updateBlock() == null
            ? null : blockLabel(payload.updateBlock());
        String next = update == null ? header : update;
        loops.push(new LoopTargets(op.opId().id(), header, next, exit));
        emitBlock(payload.bodyBlock(), header);
        if (update != null) {
            // The recorded update block runs on the fall-through path and on
            // the CONTINUE transfer, so every iteration runs it exactly once.
            emitBlock(payload.updateBlock(), check);
        }
        loops.pop();
        startSegment(exit);
    }

    /**
     * {@code TRY_CATCH} (C4): the protected block runs with this handler on
     * the failure-route stack; the handler binds the identical carried
     * {@code deal_error*} as the boxed {@code unknown} datum of the catch
     * binding and then enters the catch block. A successful protected block
     * skips the handler entirely.
     */
    private void emitTryCatch(SemanticOp op) {
        KindPayload.TryCatchPayload payload =
            (KindPayload.TryCatchPayload) op.payload();
        String handlerLabel = newLabel("catch");
        String join = newLabel("tryjoin");
        String pending = newAlloca("i8*", "pending");
        handlers.push(new FailureHandler(handlerLabel, pending));
        emitBlock(payload.tryBlock(), join);
        handlers.pop();
        startSegment(handlerLabel);
        String error = load(op, "i8*", pending);
        BindingId binding = payload.catchBinding();
        String representation = LlvmTypeMapper.DATA;
        String slot = bindingSlot(binding, 0, representation);
        String datum = emitTemporary(op, "insertvalue %struct.deal_data"
            + " { i32 " + LlvmTypeMapper.DATA_ERROR + ", i32 0, double 0.0,"
            + " i8* null }, i8* " + error + ", 3");
        emit("store %struct.deal_data " + datum + ", %struct.deal_data* "
            + slot);
        rootBindingSlot(binding, 0, RuntimeDescriptor.Unknown.INSTANCE, slot, op);
        emitBlock(payload.catchBlock(), join);
        startSegment(join);
    }

    /** {@code THROW}: transfers the thrown instance unchanged (C4). */
    private void emitThrow(SemanticOp op) {
        KindPayload.ThrowPayload payload = (KindPayload.ThrowPayload) op.payload();
        String instance = loadRepresentation(payload.errorValue(),
            descriptorOfValue(payload.errorValue()), op);
        String error = emitTemporary(op, "bitcast " + LlvmTypeMapper.ERROR_POINTER
            + " " + instance + " to i8*");
        routeFailure(error);
        // THROW never completes: the transfer already terminated this block,
        // so no fall-through exit and no unreachable tail is emitted.
    }

    private void emitBreakContinue(SemanticOp op, boolean isBreak) {
        long loopId = op.payload() instanceof KindPayload.BreakPayload breakPayload
            ? breakPayload.loopId().id()
            : ((KindPayload.ContinuePayload) op.payload()).loopId().id();
        for (LoopTargets targets : loops) {
            if (targets.loopId() == loopId) {
                terminate("br label %" + (isBreak
                    ? targets.exit() : targets.next()));
                return;
            }
        }
        throw new IllegalStateException("the " + (isBreak ? "BREAK" : "CONTINUE")
            + " op " + op.opId() + " names the loop " + loopId
            + " outside its emitted activation");
    }

    private void emitReturn(SemanticOp op) {
        KindPayload.ReturnPayload payload =
            (KindPayload.ReturnPayload) op.payload();
        recordOrigin(unitOp(payload.returnBoundaryOpId()));
        LoweredFunction function = session.function(payload.function());
        RuntimeDescriptor result = function.descriptor().returnType();
        String outcome = LlvmTypeMapper.outcomeType(result);
        if (LlvmTypeMapper.isStaticSingleton(result)
                || result instanceof RuntimeDescriptor.Void) {
            emitEpilogue();
            terminate("ret " + outcome + " { i8* null }");
            return;
        }
        RuntimeDescriptor actual = descriptorOfValue(payload.value());
        String value = abiValue(result, convert(payload.value(), actual, result, op),
            op);
        String abi = outcomeSlotType(result);
        emitEpilogue();
        String aggregate = "%return" + op.opId().id();
        emit(aggregate + " = insertvalue " + outcome
            + " zeroinitializer, " + abi + " " + value + ", 1");
        terminate("ret " + outcome + " " + aggregate);
    }

    // =========================================================================
    // Strings, containers, classes and the generated JSON walks (C3, R2, R6)
    // =========================================================================

    /** One interned literal key or field name as a runtime string (R2). */
    private String literalString(String text, SemanticOp op) {
        session.referenceRuntimeEntry("deal_text_new");
        LlvmData.StringConstant constant = session.data.intern(text);
        return emitTemporary(op, "call " + LlvmTypeMapper.STRING
            + " @deal_text_new(" + session.data.operand(constant)
            + ", i64 " + session.data.byteCount(constant) + ")");
    }

    /**
     * Stores one managed operand in an activation scratch slot and roots it
     * (M4), so a temporary that lives across the op's own allocating calls is a
     * precise root and no allocation inside the op can reclaim it.
     */
    private String rootedOperand(RuntimeDescriptor descriptor, String type,
                                 String value, SemanticOp op) {
        String slot = newAlloca(type, "rooted");
        emit("store " + type + " " + value + ", " + type + "* " + slot);
        rootSlotWrite(descriptor, slot, null, op);
        return load(op, type, slot);
    }

    /**
     * {@code STRING_CONCAT} (C3 string templates): the recorded fragments are
     * concatenated in source order through the one text group's concatenation
     * entry, and the running result is rooted across each concatenation's own
     * allocation.
     */
    private void emitStringConcat(SemanticOp op) {
        KindPayload.StringConcatPayload payload =
            (KindPayload.StringConcatPayload) op.payload();
        session.referenceRuntimeEntry("deal_text_concat");
        String accumulated = null;
        for (ValueId fragment : payload.fragments()) {
            String text = loadValue(fragment, LlvmTypeMapper.STRING, op);
            if (accumulated == null) {
                accumulated = text;
                continue;
            }
            String left = rootedOperand(RuntimeDescriptor.String.INSTANCE,
                LlvmTypeMapper.STRING, accumulated, op);
            accumulated = emitTemporary(op, "call " + LlvmTypeMapper.STRING
                + " @deal_text_concat(" + LlvmTypeMapper.STRING + " " + left
                + ", " + LlvmTypeMapper.STRING + " " + text + ")");
        }
        if (accumulated == null) {
            accumulated = literalString("", op);
        }
        storeResult(op, LlvmTypeMapper.STRING, accumulated);
    }

    // -- containers ----------------------------------------------------------

    /** The persistent element-descriptor reference of one element type (R4). */
    private String elementDescriptorReference(RuntimeDescriptor element,
                                              SemanticOp op) {
        session.referenceRuntimeEntry("deal_descriptor_intern");
        session.referenceRuntimeEntry("deal_descriptor_reference");
        String atom = emitTemporary(op, "call %struct.deal_descriptor*"
            + " @deal_descriptor_intern(" + session.data.operand(
                session.data.intern(element.canonicalSpecText())) + ")");
        return emitTemporary(op, "call i32 @deal_descriptor_reference"
            + "(%struct.deal_descriptor* " + atom + ")");
    }

    /**
     * The element-storage operand of one array write: the element in its own
     * layout slot, boxed when the element type is {@code unknown} (C3).
     */
    private String elementOperand(RuntimeDescriptor element,
                                  RuntimeDescriptor actual, ValueId value,
                                  SemanticOp op) {
        String type = LlvmTypeMapper.slotTypeOf(element);
        String stored = convert(value, actual, element, op);
        if (element instanceof RuntimeDescriptor.Boolean) {
            stored = emitTemporary(op, "zext i1 " + stored + " to i8");
        }
        String slot = newAlloca(type, "element");
        emit("store " + type + " " + stored + ", " + type + "* " + slot);
        return emitTemporary(op, "bitcast " + type + "* " + slot + " to i8*");
    }

    /** {@code ARRAY_NEW}: one typed array built from its validated elements. */
    private void emitArrayNew(SemanticOp op) {
        KindPayload.ArrayNewPayload payload =
            (KindPayload.ArrayNewPayload) op.payload();
        RuntimeDescriptor element = payload.elementDescriptor();
        if (payload.values().size() != payload.elementBoundaryOpIds().size()) {
            throw new IllegalStateException("the array literal " + op.opId()
                + " records " + payload.values().size() + " elements and "
                + payload.elementBoundaryOpIds().size() + " element boundaries");
        }
        session.referenceRuntimeEntry("deal_container_array_new_typed");
        String descriptor = elementDescriptorReference(element, op);
        String array = emitTemporary(op, "call " + LlvmTypeMapper.ARRAY
            + " @deal_container_array_new_typed(i32 "
            + session.elementLayout(element) + ", i32 " + descriptor
            + ", i64 0)");
        storeResult(op, LlvmTypeMapper.ARRAY, array);
        session.referenceRuntimeEntry("deal_container_array_append");
        String rooted = loadValue((ValueId) op.result(), LlvmTypeMapper.ARRAY, op);
        for (ValueId value : payload.values()) {
            String operand = elementOperand(element, descriptorOfValue(value),
                value, op);
            emit("call void @deal_container_array_append("
                + LlvmTypeMapper.ARRAY + " " + rooted + ", i8* " + operand + ")"
                + debugAttachment(op));
        }
    }

    /** {@code TABLE_NEW}: one insertion-ordered record in source order (R2). */
    private void emitTableNew(SemanticOp op) {
        KindPayload.TableNewPayload payload =
            (KindPayload.TableNewPayload) op.payload();
        session.referenceRuntimeEntry("deal_container_table_new");
        String table = emitTemporary(op, "call " + LlvmTypeMapper.TABLE
            + " @deal_container_table_new()");
        storeResult(op, LlvmTypeMapper.TABLE, table);
        session.referenceRuntimeEntry("deal_container_table_write");
        String rooted = loadValue((ValueId) op.result(), LlvmTypeMapper.TABLE, op);
        for (KindPayload.TableEntry entry : payload.entries()) {
            String key = rootedOperand(RuntimeDescriptor.String.INSTANCE,
                LlvmTypeMapper.STRING, literalString(entry.key(), op), op);
            String valueSlot = boxedOperandSlot(entry.value(), op);
            emit("call void @deal_container_table_write(" + LlvmTypeMapper.TABLE
                + " " + rooted + ", " + LlvmTypeMapper.STRING + " " + key
                + ", " + LlvmTypeMapper.DATA + "* " + valueSlot + ")"
                + debugAttachment(op));
        }
    }

    /** One boxed operand in the scratch slot a record write copies from. */
    private String boxedOperandSlot(ValueId value, SemanticOp op) {
        RuntimeDescriptor actual = descriptorOfValue(value);
        String datum = LlvmTypeMapper.isStaticSingleton(actual)
            ? emitDatum(actual, null, op)
            : emitDatum(actual, loadRepresentation(value, actual, op), op);
        String slot = newAlloca(LlvmTypeMapper.DATA, "writevalue");
        emit("store " + LlvmTypeMapper.DATA + " " + datum + ", "
            + LlvmTypeMapper.DATA + "* " + slot);
        return slot;
    }

    /** {@code ARRAY_LENGTH}: the exact element count as one binary64 (R2). */
    private void emitArrayLength(SemanticOp op) {
        KindPayload.ArrayLengthPayload payload =
            (KindPayload.ArrayLengthPayload) op.payload();
        session.referenceRuntimeEntry("deal_container_array_count");
        String array = loadValue(payload.arrayValue(), LlvmTypeMapper.ARRAY, op);
        String count = emitTemporary(op, "call i64"
            + " @deal_container_array_count(" + LlvmTypeMapper.ARRAY + " "
            + array + ")");
        storeResult(op, LlvmTypeMapper.NUMBER,
            emitTemporary(op, "sitofp i64 " + count + " to double"));
    }

    /** One runtime record returned through memory (the published sret ABI). */
    private String callRecordReturn(SemanticOp op, String entry,
                                    String recordType, List<String> arguments) {
        session.referenceRuntimeEntry(entry);
        if (!LlvmTypeMapper.recordCrossesInMemory(recordType)) {
            // Two eightbytes cross in registers, exactly as the published C
            // prototype lowers, so no hidden destination exists.
            StringBuilder call = new StringBuilder("call " + recordType
                + " @" + entry + "(");
            for (int index = 0; index < arguments.size(); index++) {
                call.append(index == 0 ? "" : ", ").append(arguments.get(index));
            }
            return emitTemporary(op, call.append(")").toString());
        }
        String slot = newAlloca(recordType, "returned");
        StringBuilder call = new StringBuilder("call void @" + entry + "("
            + recordType + "* sret(" + recordType + ") " + slot);
        for (String argument : arguments) {
            call.append(", ").append(argument);
        }
        emit(call.append(")").append(op == null ? "" : debugAttachment(op))
            .toString());
        return load(op, recordType, slot);
    }

    /** One record-member read: a present key or the undefined datum (R2). */
    private void emitMemberRead(SemanticOp op) {
        KindPayload.MemberReadPayload payload =
            (KindPayload.MemberReadPayload) op.payload();
        String table = loadValue(payload.table(), LlvmTypeMapper.TABLE, op);
        String key = rootedOperand(RuntimeDescriptor.String.INSTANCE,
            LlvmTypeMapper.STRING, literalString(payload.key(), op), op);
        String datum = callRecordReturn(op, "deal_container_table_read",
            LlvmTypeMapper.DATA, List.of(
                LlvmTypeMapper.TABLE + " " + table,
                LlvmTypeMapper.STRING + " " + key));
        storeResultOf(op, RuntimeDescriptor.Unknown.INSTANCE, datum);
    }

    /** One record-member write: null and undefined stay present (R2). */
    private void emitMemberWrite(SemanticOp op) {
        KindPayload.MemberWritePayload payload =
            (KindPayload.MemberWritePayload) op.payload();
        String table = loadValue(payload.table(), LlvmTypeMapper.TABLE, op);
        String key = rootedOperand(RuntimeDescriptor.String.INSTANCE,
            LlvmTypeMapper.STRING, literalString(payload.key(), op), op);
        String value = boxedOperandSlot(payload.value(), op);
        session.referenceRuntimeEntry("deal_container_table_write");
        emit("call void @deal_container_table_write(" + LlvmTypeMapper.TABLE
            + " " + table + ", " + LlvmTypeMapper.STRING + " " + key + ", "
            + LlvmTypeMapper.DATA + "* " + value + ")" + debugAttachment(op));
    }

    /** One record-member delete: removes an own key (R2). */
    private void emitMemberDelete(SemanticOp op) {
        KindPayload.MemberDeletePayload payload =
            (KindPayload.MemberDeletePayload) op.payload();
        String table = loadValue(payload.table(), LlvmTypeMapper.TABLE, op);
        String key = rootedOperand(RuntimeDescriptor.String.INSTANCE,
            LlvmTypeMapper.STRING, literalString(payload.key(), op), op);
        session.referenceRuntimeEntry("deal_container_table_delete");
        emit("call void @deal_container_table_delete(" + LlvmTypeMapper.TABLE
            + " " + table + ", " + LlvmTypeMapper.STRING + " " + key + ")"
            + debugAttachment(op));
    }

    /**
     * {@code INDEX_NORMALIZE}: the recorded slot/key computation. It raises no
     * DEAL failure (policy {@code NO_DEAL_FAILURE}): the discrete-position
     * validation of R2 runs at the named read/write/delete boundary policy, so
     * this op is the identity of the position it normalized.
     */
    private void emitIndexNormalize(SemanticOp op) {
        KindPayload.IndexNormalizePayload payload =
            (KindPayload.IndexNormalizePayload) op.payload();
        RuntimeDescriptor rawType = descriptorOfValue(payload.rawKey());
        storeResultOf(op, rawType,
            loadRepresentation(payload.rawKey(), rawType, op));
    }

    /**
     * The validated native offset of one array position (R2): finiteness,
     * integrality and bounds are checked before any native offset conversion,
     * negative zero addresses zero, an append admits exactly {@code length},
     * and a rejected position is the named E8002 arm at the recorded boundary's
     * own origin — never a converted offset.
     */
    private String emitArrayOffset(SemanticOp op, SemanticOp boundary,
                                   String array, ValueId index,
                                   boolean allowAppend,
                                   deal.semantic.ir.FailureArmId arm) {
        session.referenceRuntimeEntry("deal_container_array_count");
        session.referenceRuntimeEntry("deal_container_position");
        String count = emitTemporary(op, "call i64"
            + " @deal_container_array_count(" + LlvmTypeMapper.ARRAY + " "
            + array + ")");
        String position = loadValue(index, LlvmTypeMapper.NUMBER, op);
        String slot = newAlloca("i64", "offset");
        String accepted = emitTemporary(op, "call i32"
            + " @deal_container_position(double " + position + ", i64 " + count
            + ", i32 " + (allowAppend ? 1 : 0) + ", i64* " + slot + ")");
        String rejected = emitTemporary(op, "icmp eq i32 " + accepted + ", 0");
        String failure = newLabel("position.fail");
        String proceed = newLabel("position.ok");
        emit("br i1 " + rejected + ", label %" + failure + ", label %" + proceed);
        terminated = true;
        startSegment(failure);
        session.referenceRuntimeEntry("deal_error_array_position");
        String origin = originValue(boundary == null ? op : boundary);
        String error = emitTemporary(op, "call " + LlvmTypeMapper.ERROR_POINTER
            + " @deal_error_array_position(i32 " + arm.ordinal() + ","
            + " %struct.deal_origin* " + origin + ")");
        routeFailure(emitTemporary(op, "bitcast "
            + LlvmTypeMapper.ERROR_POINTER + " " + error + " to i8*"));
        startSegment(proceed);
        return load(op, "i64", slot);
    }

    /**
     * The recorded boundary op of one kind over one value: the boundary cell
     * the lowering places between an element's value and its commit, whose
     * origin the rendered failure names.
     */
    private SemanticOp boundaryOf(deal.semantic.ir.BoundaryKind kind,
                                  ValueId value) {
        for (SemanticOp candidate : unit.ops()) {
            if (candidate.payload() instanceof KindPayload.BoundaryPayload boundary
                    && boundary.kind() == kind
                    && boundary.input().equals(value)) {
                return candidate;
            }
        }
        return null;
    }

    /** One index read of a typed array element or of a record member (R2). */
    private void emitIndexRead(SemanticOp op) {
        KindPayload.IndexReadPayload payload =
            (KindPayload.IndexReadPayload) op.payload();
        RuntimeDescriptor container = descriptorOfValue(payload.container());
        SemanticOp boundary = unitOp(payload.elementBoundaryOpId());
        if (container instanceof RuntimeDescriptor.Array arrayType) {
            String array = loadValue(payload.container(), LlvmTypeMapper.ARRAY, op);
            String offset = emitArrayOffset(op, boundary, array, payload.slot(),
                false, deal.semantic.ir.FailureArmId.ARRAY_READ_BOUNDS);
            session.referenceRuntimeEntry("deal_container_array_element");
            String pointer = emitTemporary(op, "call i8*"
                + " @deal_container_array_element(" + LlvmTypeMapper.ARRAY + " "
                + array + ", i64 " + offset + ")");
            RuntimeDescriptor element = arrayType.element();
            String type = LlvmTypeMapper.slotTypeOf(element);
            String slot = emitTemporary(op, "bitcast i8* " + pointer + " to "
                + type + "*");
            String loaded = emitTemporary(op, "load " + type + ", " + type
                + "* " + slot);
            String value = element instanceof RuntimeDescriptor.Boolean
                ? emitTemporary(op, "trunc i8 " + loaded + " to "
                    + LlvmTypeMapper.BOOLEAN_SSA)
                : loaded;
            storeResultOf(op, element, value);
            return;
        }
        if (container instanceof RuntimeDescriptor.Table) {
            String table = loadValue(payload.container(), LlvmTypeMapper.TABLE, op);
            String key = rootedOperand(RuntimeDescriptor.String.INSTANCE,
                LlvmTypeMapper.STRING, loadValue(payload.slot(),
                    LlvmTypeMapper.STRING, op), op);
            String datum = callRecordReturn(op, "deal_container_table_read",
                LlvmTypeMapper.DATA, List.of(
                    LlvmTypeMapper.TABLE + " " + table,
                    LlvmTypeMapper.STRING + " " + key));
            storeResultOf(op, RuntimeDescriptor.Unknown.INSTANCE, datum);
            return;
        }
        throw new IllegalStateException("the index read " + op.opId()
            + " names the container type '" + container.canonicalSpecText() + "'");
    }

    /** One index write: bounds, then the element check, then the commit (R2). */
    private void emitIndexWrite(SemanticOp op) {
        KindPayload.IndexWritePayload payload =
            (KindPayload.IndexWritePayload) op.payload();
        RuntimeDescriptor container = descriptorOfValue(payload.container());
        if (container instanceof RuntimeDescriptor.Array arrayType) {
            SemanticOp boundary = boundaryOf(
                deal.semantic.ir.BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT,
                payload.value());
            String array = loadValue(payload.container(), LlvmTypeMapper.ARRAY, op);
            String offset = emitArrayOffset(op, boundary, array, payload.slot(),
                true, deal.semantic.ir.FailureArmId.ARRAY_WRITE_BOUNDS);
            String element = elementOperand(arrayType.element(),
                descriptorOfValue(payload.value()), payload.value(), op);
            session.referenceRuntimeEntry("deal_container_array_count");
            session.referenceRuntimeEntry("deal_container_array_append");
            session.referenceRuntimeEntry("deal_container_array_store");
            String count = emitTemporary(op, "call i64"
                + " @deal_container_array_count(" + LlvmTypeMapper.ARRAY + " "
                + array + ")");
            String appended = emitTemporary(op, "icmp eq i64 " + offset + ", "
                + count);
            String appendLabel = newLabel("array.append");
            String storeLabel = newLabel("array.store");
            String join = newLabel("array.written");
            emit("br i1 " + appended + ", label %" + appendLabel + ", label %"
                + storeLabel);
            terminated = true;
            startSegment(appendLabel);
            emit("call void @deal_container_array_append("
                + LlvmTypeMapper.ARRAY + " " + array + ", i8* " + element + ")"
                + debugAttachment(op));
            terminate("br label %" + join);
            startSegment(storeLabel);
            emit("call void @deal_container_array_store("
                + LlvmTypeMapper.ARRAY + " " + array + ", i64 " + offset
                + ", i8* " + element + ")" + debugAttachment(op));
            terminate("br label %" + join);
            startSegment(join);
            return;
        }
        if (container instanceof RuntimeDescriptor.Table) {
            String table = loadValue(payload.container(), LlvmTypeMapper.TABLE, op);
            String key = rootedOperand(RuntimeDescriptor.String.INSTANCE,
                LlvmTypeMapper.STRING, loadValue(payload.slot(),
                    LlvmTypeMapper.STRING, op), op);
            String value = boxedOperandSlot(payload.value(), op);
            session.referenceRuntimeEntry("deal_container_table_write");
            emit("call void @deal_container_table_write("
                + LlvmTypeMapper.TABLE + " " + table + ", "
                + LlvmTypeMapper.STRING + " " + key + ", " + LlvmTypeMapper.DATA
                + "* " + value + ")" + debugAttachment(op));
            return;
        }
        throw new IllegalStateException("the index write " + op.opId()
            + " names the container type '" + container.canonicalSpecText() + "'");
    }

    /** One index delete of a record member (R2). */
    private void emitIndexDelete(SemanticOp op) {
        KindPayload.IndexDeletePayload payload =
            (KindPayload.IndexDeletePayload) op.payload();
        RuntimeDescriptor container = descriptorOfValue(payload.container());
        if (!(container instanceof RuntimeDescriptor.Table)) {
            throw new IllegalStateException("the index delete " + op.opId()
                + " names the container type '"
                + container.canonicalSpecText() + "'");
        }
        String table = loadValue(payload.container(), LlvmTypeMapper.TABLE, op);
        String key = rootedOperand(RuntimeDescriptor.String.INSTANCE,
            LlvmTypeMapper.STRING, loadValue(payload.slot(),
                LlvmTypeMapper.STRING, op), op);
        session.referenceRuntimeEntry("deal_container_table_delete");
        emit("call void @deal_container_table_delete(" + LlvmTypeMapper.TABLE
            + " " + table + ", " + LlvmTypeMapper.STRING + " " + key + ")"
            + debugAttachment(op));
    }

    /**
     * {@code HAS_FIELD}: own presence without narrowing (R2). A record answers
     * its key store; a class instance answers its optional-field presence bit
     * and a required field is always present. The receiver is read once.
     */
    private void emitHasField(SemanticOp op) {
        KindPayload.HasFieldPayload payload =
            (KindPayload.HasFieldPayload) op.payload();
        RuntimeDescriptor receiver = descriptorOfValue(payload.receiver());
        if (receiver instanceof RuntimeDescriptor.Table) {
            String key;
            if (payload.dynamicKey() != null) {
                key = rootedOperand(RuntimeDescriptor.String.INSTANCE,
                    LlvmTypeMapper.STRING, loadValue(payload.dynamicKey(),
                        LlvmTypeMapper.STRING, op), op);
            } else {
                key = rootedOperand(RuntimeDescriptor.String.INSTANCE,
                    LlvmTypeMapper.STRING, literalString(payload.key(), op), op);
            }
            String table = loadValue(payload.receiver(), LlvmTypeMapper.TABLE, op);
            session.referenceRuntimeEntry("deal_container_table_has");
            String present = emitTemporary(op, "call i32"
                + " @deal_container_table_has(" + LlvmTypeMapper.TABLE + " "
                + table + ", " + LlvmTypeMapper.STRING + " " + key + ")");
            storeResult(op, LlvmTypeMapper.BOOLEAN_SSA, emitTemporary(op,
                "icmp ne i32 " + present + ", 0"));
            return;
        }
        if (receiver instanceof RuntimeDescriptor.Class classType
                && !classType.classId().equals(deal.semantic.ir.ClassId.ERROR)
                && payload.dynamicKey() == null) {
            LlvmClassPlans.ClassDef def = session.classDefinition(
                classType.classId());
            int index = def.indexOf(payload.key());
            if (index < 0) {
                throw new IllegalStateException("the class "
                    + classType.classId().text() + " declares no field '"
                    + payload.key() + "'");
            }
            LlvmClassPlans.Field field = def.fields().get(index);
            if (field.presenceIndex() < 0) {
                storeResult(op, LlvmTypeMapper.BOOLEAN_SSA, "true");
                return;
            }
            String instance = loadValue(payload.receiver(),
                LlvmTypeMapper.classRepresentation(classType.classId()), op);
            String presence = emitTemporary(op, "load i8, i8* "
                + fieldPointer(op, def.recordName(), instance, 0,
                    1 + field.presenceIndex()));
            storeResult(op, LlvmTypeMapper.BOOLEAN_SSA, emitTemporary(op,
                "icmp ne i8 " + presence + ", 0"));
            return;
        }
        if (receiver instanceof RuntimeDescriptor.Class classType
                && classType.classId().equals(deal.semantic.ir.ClassId.ERROR)
                && payload.dynamicKey() == null) {
            // The builtin Error shape is `{ code: string; message: string }`:
            // both fields are required-present on every Error record.
            storeResult(op, LlvmTypeMapper.BOOLEAN_SSA, "true");
            return;
        }
        throw new IllegalStateException("the presence test " + op.opId()
            + " names the receiver type '" + receiver.canonicalSpecText() + "'");
    }

    /**
     * {@code FOR_EACH} (R2): the iterable is evaluated once and retained; an
     * array traversal re-reads the live element count and the live element at
     * every step, so an append is visited and an unvisited replacement is
     * observed; a string traversal yields one single-scalar string per Unicode
     * scalar value. Each iteration writes the fresh iteration binding.
     */
    private void emitForEach(SemanticOp op) {
        KindPayload.ForEachPayload payload =
            (KindPayload.ForEachPayload) op.payload();
        RuntimeDescriptor iterable = descriptorOfValue(payload.iterable());
        BindingId binding = payload.binding();
        long generation = payload.generation();
        RuntimeDescriptor element = session.bindingType(unit, binding, generation);
        String representation = stagedRepresentation(element);
        String header = newLabel("for");
        String check = newLabel("forcheck");
        String exit = newLabel("forexit");
        String latch = newLabel("fornext");
        if (payload.mode() == deal.semantic.ir.IterationMode.STRING_SCALARS) {
            String text = rootedOperand(RuntimeDescriptor.String.INSTANCE,
                LlvmTypeMapper.STRING, loadValue(payload.iterable(),
                    LlvmTypeMapper.STRING, op), op);
            session.referenceRuntimeEntry("deal_text_bytes");
            session.referenceRuntimeEntry("deal_text_scalar_length");
            String bytes = emitTemporary(op, "call i8* @deal_text_bytes("
                + LlvmTypeMapper.STRING + " " + text + ")");
            String count = emitTemporary(op, "call i64"
                + " @deal_text_scalar_length(" + LlvmTypeMapper.STRING + " "
                + text + ")");
            String indexSlot = newAlloca("i64", "index");
            emit("store i64 0, i64* " + indexSlot);
            terminate("br label %" + header);
            startSegment(header);
            String index = load(op, "i64", indexSlot);
            String inRange = emitTemporary(op, "icmp ult i64 " + index + ", "
                + count);
            emit("br i1 " + inRange + ", label %" + check + ", label %" + exit);
            terminated = true;
            startSegment(check);
            session.referenceRuntimeEntry("deal_text_scalar_offset");
            session.referenceRuntimeEntry("deal_text_new");
            String from = emitTemporary(op, "call i64"
                + " @deal_text_scalar_offset(" + LlvmTypeMapper.STRING + " "
                + text + ", i64 " + index + ")");
            String next = emitTemporary(op, "add i64 " + index + ", 1");
            String to = emitTemporary(op, "call i64"
                + " @deal_text_scalar_offset(" + LlvmTypeMapper.STRING + " "
                + text + ", i64 " + next + ")");
            String length = emitTemporary(op, "sub i64 " + to + ", " + from);
            String start = emitTemporary(op, "getelementptr i8, i8* " + bytes
                + ", i64 " + from);
            String scalar = emitTemporary(op, "call " + LlvmTypeMapper.STRING
                + " @deal_text_new(i8* " + start + ", i64 " + length + ")");
            bindIteration(binding, generation, element, representation, scalar,
                op);
            loops.push(new LoopTargets(op.opId().id(), header, latch, exit));
            emitBlock(payload.body(), latch);
            loops.pop();
            startSegment(latch);
            String current = load(op, "i64", indexSlot);
            emit("store i64 " + emitTemporary(op, "add i64 " + current + ", 1")
                + ", i64* " + indexSlot);
            terminate("br label %" + header);
            startSegment(exit);
            return;
        }
        if (!(iterable instanceof RuntimeDescriptor.Array arrayType)) {
            throw new IllegalStateException("the for-of op " + op.opId()
                + " iterates the type '" + iterable.canonicalSpecText() + "'");
        }
        String array = rootedOperand(iterable, LlvmTypeMapper.ARRAY,
            loadValue(payload.iterable(), LlvmTypeMapper.ARRAY, op), op);
        String indexSlot = newAlloca("i64", "index");
        emit("store i64 0, i64* " + indexSlot);
        terminate("br label %" + header);
        startSegment(header);
        String index = load(op, "i64", indexSlot);
        session.referenceRuntimeEntry("deal_container_array_count");
        String liveCount = emitTemporary(op, "call i64"
            + " @deal_container_array_count(" + LlvmTypeMapper.ARRAY + " "
            + array + ")");
        String inRange = emitTemporary(op, "icmp ult i64 " + index + ", "
            + liveCount);
        emit("br i1 " + inRange + ", label %" + check + ", label %" + exit);
        terminated = true;
        startSegment(check);
        session.referenceRuntimeEntry("deal_container_array_element");
        String pointer = emitTemporary(op, "call i8*"
            + " @deal_container_array_element(" + LlvmTypeMapper.ARRAY + " "
            + array + ", i64 " + index + ")");
        String type = LlvmTypeMapper.slotTypeOf(arrayType.element());
        String slot = emitTemporary(op, "bitcast i8* " + pointer + " to "
            + type + "*");
        String loaded = emitTemporary(op, "load " + type + ", " + type + "* "
            + slot);
        String value = arrayType.element() instanceof RuntimeDescriptor.Boolean
            ? emitTemporary(op, "trunc i8 " + loaded + " to "
                + LlvmTypeMapper.BOOLEAN_SSA)
            : loaded;
        bindIteration(binding, generation, element, representation, value, op);
        loops.push(new LoopTargets(op.opId().id(), header, latch, exit));
        emitBlock(payload.body(), latch);
        loops.pop();
        startSegment(latch);
        String current = load(op, "i64", indexSlot);
        emit("store i64 " + emitTemporary(op, "add i64 " + current + ", 1")
            + ", i64* " + indexSlot);
        terminate("br label %" + header);
        startSegment(exit);
    }

    /**
     * Writes one iteration's binding and roots it (M4, R2, C5). Every iteration
     * binding generation owns a fresh heap cell, so a closure created in one
     * iteration captures that iteration's own cell and two iterations never
     * share one binding; a direct iteration binding keeps its native slot.
     */
    private void bindIteration(BindingId binding, long generation,
                               RuntimeDescriptor element, String representation,
                               String value, SemanticOp op) {
        if (!session.cellBinding(unit, binding, generation)) {
            String slot = bindingSlot(binding, generation, representation);
            emit("store " + representation + " " + value + ", " + representation
                + "* " + slot);
            rootBindingSlot(binding, generation, element, slot, op);
            return;
        }
        // The element is live across the cell's allocating call, so its own
        // datum owns a root-frame slot (M4) before the cell is created.
        session.referenceRuntimeEntry("deal_cell_alloc_data");
        String datum = element instanceof RuntimeDescriptor.Unknown
            ? value : emitDatum(element, value, op);
        String datumSlot = newAlloca(LlvmTypeMapper.DATA, "iterationdatum");
        emit("store " + LlvmTypeMapper.DATA + " " + datum + ", "
            + LlvmTypeMapper.DATA + "* " + datumSlot);
        if (LlvmTypeMapper.isManagedValue(element)) {
            rootSlotWrite(RuntimeDescriptor.Unknown.INSTANCE, datumSlot, null, op);
        }
        String cell = emitTemporary(op, "call i8* @deal_cell_alloc_data("
            + LlvmTypeMapper.DATA + "* " + datumSlot + ", i32 " + generation
            + ")");
        String slot = bindingSlot(binding, generation, "i8*");
        emit("store i8* " + cell + ", i8** " + slot);
        rootCellAt(slot, op);
    }

    // -- classes -------------------------------------------------------------

    /** One class construction through its recorded provider (R2, R4). */
    private void emitClassNew(SemanticOp op) {
        KindPayload.ClassNewPayload payload =
            (KindPayload.ClassNewPayload) op.payload();
        switch (payload.constructionProvider()) {
            case BUILTIN_ERROR -> emitBuiltinErrorNew(op, payload);
            case LOCAL -> emitLocalClassNew(op, payload);
            default -> throw new deal.codegen.SharedEmissionFailure(
                "LlvmSemanticEmitter rejects the construction provider "
                    + payload.constructionProvider() + " of "
                    + payload.classId().text()
                    + ": the emitted construction of a shared-factory, retained"
                    + " ABI or host-declared class belongs to the module-export"
                    + " and host stages",
                op.origin().span().range());
        }
    }

    /**
     * The builtin {@code Error} construction ({@code ConstructionProvider
     * .BUILTIN_ERROR}): the supplied {@code code} and {@code message} class
     * fields become the runtime's own Error record, so the instance a program
     * constructs is the same object the runtime carries (C3, R2, R5).
     */
    private void emitBuiltinErrorNew(SemanticOp op,
                                     KindPayload.ClassNewPayload payload) {
        ValueId code = providedField(payload, "code");
        ValueId message = providedField(payload, "message");
        if (code == null || message == null) {
            throw new deal.codegen.SharedEmissionFailure(
                "LlvmSemanticEmitter rejects the builtin Error construction "
                    + op.opId() + " without its code and message class fields",
                op.origin().span().range());
        }
        session.referenceRuntimeEntry("deal_error_create_strings");
        String instance = emitTemporary(op, "call " + LlvmTypeMapper.ERROR_POINTER
            + " @deal_error_create_strings(" + LlvmTypeMapper.STRING + " "
            + loadValue(code, LlvmTypeMapper.STRING, op) + ", "
            + LlvmTypeMapper.STRING + " "
            + loadValue(message, LlvmTypeMapper.STRING, op) + ","
            + " %struct.deal_origin* " + originValue(op) + ")");
        storeResult(op, LlvmTypeMapper.ERROR_POINTER, instance);
    }

    /** The supplied value of one named field of a class construction. */
    private static ValueId providedField(KindPayload.ClassNewPayload payload,
                                         String name) {
        for (KindPayload.ProvidedField field : payload.providedFields()) {
            if (field.name().equals(name)) {
                return field.valueOpId();
            }
        }
        return null;
    }

    /**
     * One locally declared class construction (R2): the supplied fields stay in
     * literal (source) order, the runtime validates them at their own recorded
     * boundaries — an extra field is the E8007 arm and a mismatched value its
     * own descriptor arm — and the instance is allocated, tagged and published
     * only after every check succeeded, so a failed construction keeps the
     * earlier effects and publishes nothing.
     */
    private void emitLocalClassNew(SemanticOp op,
                                   KindPayload.ClassNewPayload payload) {
        LlvmClassPlans.ClassDef def =
            session.classDefinition(payload.classId());
        List<KindPayload.ProvidedField> provided = payload.providedFields();
        String suppliedSlot = null;
        if (!provided.isEmpty()) {
            suppliedSlot = newAlloca("[" + provided.size()
                + " x %struct.deal_class_supplied]", "supplied");
            for (int index = 0; index < provided.size(); index++) {
                KindPayload.ProvidedField field = provided.get(index);
                String element = emitTemporary(op, "getelementptr ["
                    + provided.size() + " x %struct.deal_class_supplied], ["
                    + provided.size() + " x %struct.deal_class_supplied]* "
                    + suppliedSlot + ", i64 0, i64 " + index);
                String origin = originValue(fieldBoundaryOrigin(payload,
                    field.name(), op));
                String record = emitTemporary(op, "insertvalue"
                    + " %struct.deal_class_supplied zeroinitializer, "
                    + session.data.operand(
                        session.data.intern(field.name())) + ", 0");
                record = emitTemporary(op, "insertvalue"
                    + " %struct.deal_class_supplied " + record + ","
                    + " %struct.deal_check_input "
                    + checkInputRecord(op, field.valueOpId()) + ", 1");
                record = emitTemporary(op, "insertvalue"
                    + " %struct.deal_class_supplied " + record + ","
                    + " %struct.deal_origin "
                    + loadQuiet("%struct.deal_origin", origin) + ", 2");
                emit("store %struct.deal_class_supplied " + record + ","
                    + " %struct.deal_class_supplied* " + element);
            }
        }
        session.referenceRuntimeEntry("deal_class_construct");
        String atomSlot = newAlloca("i8*", "classatom");
        String outcome = emitTemporary(op, "call %struct.deal_outcome"
            + " @deal_class_construct(%struct.deal_class_layout* @"
            + def.constructionSymbol() + ", %struct.deal_class_supplied* "
            + (suppliedSlot == null ? "null"
                : arrayElementOf(op, suppliedSlot, provided.size())) + ", i64 "
            + provided.size() + ", %struct.deal_origin* " + originValue(op)
            + ", i8** " + atomSlot + ")");
        String error = emitTemporary(op, "extractvalue %struct.deal_outcome "
            + outcome + ", 0");
        branchOnFailure(op, error, "construct");
        String atom = load(op, "i8*", atomSlot);
        session.referenceRuntimeEntry("deal_alloc");
        String instance = emitTemporary(op, "call i8* @deal_alloc(i32 "
            + def.layoutId() + ", i64 " + def.size() + ")");
        String typed = emitTemporary(op, "bitcast i8* " + instance + " to "
            + def.recordName() + "*");
        emit("store i8* " + atom + ", i8** "
            + fieldPointer(op, def.recordName(), typed, 0, 0));
        for (int index = 0; index < def.fields().size(); index++) {
            LlvmClassPlans.Field field = def.fields().get(index);
            ValueId value = providedField(payload, field.layout().name());
            if (value == null) {
                if (field.layout().required()) {
                    throw new deal.codegen.SharedEmissionFailure(
                        "LlvmSemanticEmitter rejects the class construction "
                            + op.opId() + " of " + payload.classId().text()
                            + " without its required field '"
                            + field.layout().name() + "'",
                        op.origin().span().range());
                }
                continue;
            }
            String stored = convert(value, descriptorOfValue(value),
                field.layout().descriptor(), op);
            if (field.layout().descriptor()
                    instanceof RuntimeDescriptor.Boolean) {
                stored = emitTemporary(op, "zext i1 " + stored + " to i8");
            }
            String target = fieldPointer(op, def.recordName(), typed, 0,
                field.structIndex());
            emit("store " + field.slotType() + " " + stored + ", "
                + field.slotType() + "* " + target);
            emitFieldBarrier(op, def, field, typed, target, stored);
            if (field.presenceIndex() >= 0) {
                emit("store i8 1, i8* "
                    + fieldPointer(op, def.recordName(), typed, 0,
                        1 + field.presenceIndex()));
            }
        }
        storeResult(op, LlvmTypeMapper.classRepresentation(payload.classId()),
            typed);
    }

    /** The first-element pointer of one supplied-field array (an instruction). */
    private String arrayElementOf(SemanticOp op, String slot, int count) {
        return emitTemporary(op, "getelementptr [" + count
            + " x %struct.deal_class_supplied], [" + count
            + " x %struct.deal_class_supplied]* " + slot + ", i64 0, i64 0");
    }

    /** One instruction-form member pointer of an emitted class record (C3). */
    private String fieldPointer(SemanticOp op, String record, String pointer,
                                int... indices) {
        StringBuilder instruction = new StringBuilder("getelementptr ")
            .append(record).append(", ").append(record).append("* ")
            .append(pointer);
        for (int index : indices) {
            instruction.append(", i32 ").append(index);
        }
        return emitTemporary(op, instruction.toString());
    }

    /** The recorded origin of one supplied field's own boundary check. */
    private SemanticOp fieldBoundaryOrigin(KindPayload.ClassNewPayload payload,
                                           String field, SemanticOp op) {
        for (KindPayload.FieldBoundary boundary : payload.fieldBoundaries()) {
            if (boundary.field().equals(field)) {
                return unitOp(boundary.boundaryOpId());
            }
        }
        return op;
    }

    /**
     * The closed check presentation of one completed value (R3/R4): its
     * persistent datum plus, for a class-typed value, the carried canonical
     * class atom the runtime classification reads.
     */
    private String checkInputRecord(SemanticOp op, ValueId value) {
        RuntimeDescriptor actual = descriptorOfValue(value);
        String datum = LlvmTypeMapper.isStaticSingleton(actual)
            ? emitDatum(actual, null, op)
            : emitDatum(actual, loadRepresentation(value, actual, op), op);
        return checkInputRecordOf(op, actual, datum);
    }

    /**
     * The closed check presentation of one already built persistent datum
     * (R3/R4): its datum plus, for a class-typed value, the carried canonical
     * class atom the runtime classification reads.
     */
    private String checkInputRecordOf(SemanticOp op, RuntimeDescriptor actual,
                                      String datum) {
        String record = emitTemporary(op, "insertvalue %struct.deal_check_input"
            + " zeroinitializer, %struct.deal_data " + datum + ", 2");
        // A class datum carries its canonical atom beside the datum (R3/R4):
        // a statically class-typed value answers its own atom and an untyped
        // one its instance's own atom; no other datum carries one.
        String atom = LlvmDataValues.classAtomOf(session, sink, actual, datum);
        if (!atom.equals("i8* null")) {
            record = emitTemporary(op, "insertvalue %struct.deal_check_input "
                + record + ", " + atom + ", 3");
        }
        // A datum that can carry the function tag also carries the carrier's
        // interned signature text (R4): the runtime classifies the datum with
        // it and a function position compares it against the declared
        // signature, so no boxed function datum reaches the check without one.
        String signature = carriedSignature(op, actual, datum);
        if (signature != null) {
            record = emitTemporary(op, "insertvalue %struct.deal_check_input "
                + record + ", i8* " + signature + ", 4");
        }
        return record;
    }

    /**
     * One class field read (R2): a required field yields its declared type, an
     * optional field yields presence-projected undefined for absence without
     * erasing the presence bit, and the builtin Error's {@code code}/{@code
     * message} fields read the runtime record's own fields. A declared
     * {@code unknown} field absorbs the nullish members (R4), so its read type
     * is the persistent datum and its absence projects to the undefined datum.
     */
    private void emitFieldRead(SemanticOp op) {
        KindPayload.FieldReadPayload payload =
            (KindPayload.FieldReadPayload) op.payload();
        RuntimeDescriptor readType = op.resultType()
            instanceof RuntimeDescriptor descriptor ? descriptor : null;
        if (readType == null) {
            throw new IllegalStateException("the field read " + op.opId()
                + " records no declared result type");
        }
        if (payload.classId().equals(deal.semantic.ir.ClassId.ERROR)) {
            String instance = loadValue(payload.classValue(),
                LlvmTypeMapper.ERROR_POINTER, op);
            String accessor = switch (payload.field()) {
                case "code" -> "deal_error_code_value";
                case "message" -> "deal_error_message_value";
                default -> throw new deal.codegen.SharedEmissionFailure(
                    "LlvmSemanticEmitter rejects the read of the builtin Error"
                        + " field '" + payload.field() + "' of " + op.opId(),
                    op.origin().span().range());
            };
            session.referenceRuntimeEntry(accessor);
            String text = emitTemporary(op, "call " + LlvmTypeMapper.STRING
                + " @" + accessor + "(" + LlvmTypeMapper.ERROR_POINTER + " "
                + instance + ")");
            storeResultOf(op, RuntimeDescriptor.String.INSTANCE, text);
            return;
        }
        LlvmClassPlans.ClassDef def = session.classDefinition(payload.classId());
        int index = def.indexOf(payload.field());
        if (index < 0) {
            throw new IllegalStateException("the class "
                + payload.classId().text() + " declares no field '"
                + payload.field() + "'");
        }
        LlvmClassPlans.Field field = def.fields().get(index);
        String instance = loadValue(payload.classValue(),
            LlvmTypeMapper.classRepresentation(payload.classId()), op);
        String slot = fieldPointer(op, def.recordName(), instance, 0,
            field.structIndex());
        if (field.presenceIndex() < 0) {
            String loaded = emitTemporary(op, "load " + field.slotType() + ", "
                + field.slotType() + "* " + slot);
            String value = field.layout().descriptor()
                instanceof RuntimeDescriptor.Boolean
                ? emitTemporary(op, "trunc i8 " + loaded + " to "
                    + LlvmTypeMapper.BOOLEAN_SSA) : loaded;
            storeResultOf(op, field.layout().descriptor(), value);
            return;
        }
        // An optional field's read type is its declared type with absence
        // added, except that `unknown` absorbs the nullish members (R4): the
        // presence bit then projects absence to the persistent undefined datum
        // of the same `unknown` slot, never to a nullish value form.
        RuntimeDescriptor.Nullable nullable =
            readType instanceof RuntimeDescriptor.Nullable nullish
                ? nullish : null;
        if (nullable == null
                && !(readType instanceof RuntimeDescriptor.Unknown)) {
            throw new deal.codegen.SharedEmissionFailure(
                "LlvmSemanticEmitter rejects the optional field read " + op.opId()
                    + ": the recorded result type '"
                    + readType.canonicalSpecText()
                    + "' has no absence projection (C9)",
                op.origin().span().range());
        }
        String representation = stagedRepresentation(readType);
        String target = valueSlot((ValueId) op.result(), representation);
        String presence = emitTemporary(op, "load i8, i8* "
            + fieldPointer(op, def.recordName(), instance, 0,
                1 + field.presenceIndex()));
        String present = emitTemporary(op, "icmp ne i8 " + presence + ", 0");
        String presentLabel = newLabel("field.present");
        String absentLabel = newLabel("field.absent");
        String join = newLabel("field.join");
        emit("br i1 " + present + ", label %" + presentLabel + ", label %"
            + absentLabel);
        terminated = true;
        startSegment(presentLabel);
        String loaded = emitTemporary(op, "load " + field.slotType() + ", "
            + field.slotType() + "* " + slot);
        String value = field.layout().descriptor()
            instanceof RuntimeDescriptor.Boolean
            ? emitTemporary(op, "trunc i8 " + loaded + " to "
                + LlvmTypeMapper.BOOLEAN_SSA) : loaded;
        String converted = coerce(field.layout().descriptor(), readType, value, op);
        emit("store " + representation + " " + converted + ", "
            + representation + "* " + target);
        rootSlotWrite(readType, target, converted, op);
        terminate("br label %" + join);
        startSegment(absentLabel);
        String missing = nullable != null
            ? LlvmDataValues.absent(session, sink, nullable)
            : emitDatum(RuntimeDescriptor.Undefined.INSTANCE, null, op);
        emit("store " + representation + " " + missing + ", " + representation
            + "* " + target);
        rootSlotWrite(readType, target, missing, op);
        terminate("br label %" + join);
        startSegment(join);
    }

    /** One class field write: the declared type, then the presence bit (R2). */
    private void emitFieldWrite(SemanticOp op) {
        KindPayload.FieldWritePayload payload =
            (KindPayload.FieldWritePayload) op.payload();
        if (payload.classId().equals(deal.semantic.ir.ClassId.ERROR)) {
            String accessor = switch (payload.field()) {
                case "code" -> "deal_error_set_code_value";
                case "message" -> "deal_error_set_message_value";
                default -> throw new deal.codegen.SharedEmissionFailure(
                    "LlvmSemanticEmitter rejects the write of the builtin Error"
                        + " field '" + payload.field() + "' of " + op.opId(),
                    op.origin().span().range());
            };
            session.referenceRuntimeEntry(accessor);
            emit("call void @" + accessor + "(" + LlvmTypeMapper.ERROR_POINTER
                + " " + loadValue(payload.classValue(),
                    LlvmTypeMapper.ERROR_POINTER, op) + ", "
                + LlvmTypeMapper.STRING + " "
                + loadValue(payload.value(), LlvmTypeMapper.STRING, op) + ")"
                + debugAttachment(op));
            return;
        }
        LlvmClassPlans.ClassDef def = session.classDefinition(payload.classId());
        int index = def.indexOf(payload.field());
        if (index < 0) {
            throw new IllegalStateException("the class "
                + payload.classId().text() + " declares no field '"
                + payload.field() + "'");
        }
        LlvmClassPlans.Field field = def.fields().get(index);
        String instance = loadValue(payload.classValue(),
            LlvmTypeMapper.classRepresentation(payload.classId()), op);
        String stored = convert(payload.value(), descriptorOfValue(payload.value()),
            field.layout().descriptor(), op);
        if (field.layout().descriptor() instanceof RuntimeDescriptor.Boolean) {
            stored = emitTemporary(op, "zext i1 " + stored + " to i8");
        }
        String slot = fieldPointer(op, def.recordName(), instance, 0,
            field.structIndex());
        emit("store " + field.slotType() + " " + stored + ", "
            + field.slotType() + "* " + slot + debugAttachment(op));
        emitFieldBarrier(op, def, field, instance, slot, stored);
        if (field.presenceIndex() >= 0) {
            emit("store i8 1, i8* "
                + fieldPointer(op, def.recordName(), instance, 0,
                    1 + field.presenceIndex()) + debugAttachment(op));
        }
    }

    /**
     * The M2 insertion barrier of one class-field store, run on the committed
     * slot: a persistent datum slot shades the datum's managed payload through
     * the tag-aware entry, and every other managed-reference slot shades the
     * just-stored referent. A scalar, scalar-nullish or presence slot stores no
     * managed reference and needs none.
     */
    private void emitFieldBarrier(SemanticOp op, LlvmClassPlans.ClassDef def,
                                  LlvmClassPlans.Field field, String instance,
                                  String slot, String stored) {
        if (!LlvmClassPlans.holdsManagedReference(field)) {
            return;
        }
        String container = emitTemporary(op, "bitcast " + def.recordName()
            + "* " + instance + " to i8*");
        if (LlvmClassPlans.isTaggedDatum(field)) {
            session.referenceRuntimeEntry("deal_store_barrier_data");
            emit("call void @deal_store_barrier_data(i8* " + container + ", "
                + LlvmTypeMapper.DATA + "* " + slot + ")"
                + debugAttachment(op));
            return;
        }
        session.referenceRuntimeEntry("deal_store_barrier");
        String referent = LlvmTypeMapper.REFERENCE_NULLISH.equals(
            field.slotType())
            ? stored
            : emitTemporary(op, "bitcast " + field.slotType() + " " + stored
                + " to i8*");
        emit("call void @deal_store_barrier(i8* " + container + ", i8* "
            + referent + ")" + debugAttachment(op));
    }

    /** One class field delete: the field's presence returns to absent (R2). */
    private void emitFieldDelete(SemanticOp op) {
        KindPayload.FieldDeletePayload payload =
            (KindPayload.FieldDeletePayload) op.payload();
        LlvmClassPlans.ClassDef def = session.classDefinition(payload.classId());
        int index = def.indexOf(payload.field());
        if (index < 0) {
            throw new IllegalStateException("the class "
                + payload.classId().text() + " declares no field '"
                + payload.field() + "'");
        }
        LlvmClassPlans.Field field = def.fields().get(index);
        if (field.presenceIndex() < 0) {
            throw new deal.codegen.SharedEmissionFailure(
                "LlvmSemanticEmitter rejects the delete of the required field '"
                    + payload.field() + "' of " + payload.classId().text()
                    + ": only an optional field has presence metadata",
                op.origin().span().range());
        }
        String instance = loadValue(payload.classValue(),
            LlvmTypeMapper.classRepresentation(payload.classId()), op);
        emit("store i8 0, i8* "
            + fieldPointer(op, def.recordName(), instance, 0,
                1 + field.presenceIndex()) + debugAttachment(op));
    }

    // -- the generated JSON walks --------------------------------------------

    /**
     * {@code JSON_FROM_CLASS} ({@code C$fromJson}, R6): the runtime parses and
     * walks the emitted class plan in declaration order and answers the
     * language null datum on every parse, extra-key, required-presence or field
     * failure, so nothing partial is ever published.
     */
    private void emitJsonFromClass(SemanticOp op) {
        KindPayload.JsonFromClassPayload payload =
            (KindPayload.JsonFromClassPayload) op.payload();
        LlvmClassPlans.ClassDef def =
            session.classDefinition(payload.layout().classId());
        String text = loadValue(payload.jsonString(), LlvmTypeMapper.STRING, op);
        String outcome = callRecordReturn(op, "deal_json_from_class",
            "%struct.deal_outcome_data", List.of(
                LlvmTypeMapper.STRING + " " + text,
                "%struct.deal_json_class_plan* @" + def.planSymbol(),
                "i8* bitcast (" + LlvmClassPlans.PLAN_TYPE + "* (i8*, i8*)* @"
                    + LlvmClassPlans.LOOKUP_NAME + " to i8*)",
                "i8* null",
                "%struct.deal_origin* " + originValue(op)));
        String error = emitTemporary(op, "extractvalue"
            + " %struct.deal_outcome_data " + outcome + ", 0");
        branchOnFailure(op, error, "json");
        String datum = emitTemporary(op, "extractvalue"
            + " %struct.deal_outcome_data " + outcome + ", 1");
        RuntimeDescriptor readType = op.resultType()
            instanceof RuntimeDescriptor descriptor ? descriptor
            : RuntimeDescriptor.Unknown.INSTANCE;
        storeResult(op, stagedRepresentation(readType),
            LlvmDataValues.unbox(session, sink, readType, datum));
    }

    /**
     * {@code JSON_TO_CLASS} ({@code C$toJson}, R6): the runtime encodes the
     * instance's declared fields in declaration order, omits absent optionals
     * and undefined-valued present fields, encodes non-finite numbers as null
     * and rejects a cycle, publishing either the complete text or the arm's own
     * failure and never partial output.
     */
    private void emitJsonToClass(SemanticOp op) {
        KindPayload.JsonToClassPayload payload =
            (KindPayload.JsonToClassPayload) op.payload();
        LlvmClassPlans.ClassDef def =
            session.classDefinition(payload.layout().classId());
        RuntimeDescriptor actual = descriptorOfValue(payload.classValue());
        String instance = loadValue(payload.classValue(),
            LlvmTypeMapper.classRepresentation(payload.layout().classId()), op);
        String slot = newAlloca(LlvmTypeMapper.DATA, "jsonvalue");
        emit("store " + LlvmTypeMapper.DATA + " "
            + emitDatum(actual, instance, op) + ", " + LlvmTypeMapper.DATA
            + "* " + slot);
        String outcome = callRecordReturn(op, "deal_json_to_class",
            "%struct.deal_outcome_string", List.of(
                "%struct.deal_json_class_plan* @" + def.planSymbol(),
                LlvmTypeMapper.DATA + "* " + slot,
                "i8* bitcast (" + LlvmClassPlans.PLAN_TYPE + "* (i8*, i8*)* @"
                    + LlvmClassPlans.LOOKUP_NAME + " to i8*)",
                "i8* null",
                "%struct.deal_origin* " + originValue(op)));
        String error = emitTemporary(op, "extractvalue"
            + " %struct.deal_outcome_string " + outcome + ", 0");
        branchOnFailure(op, error, "json");
        String text = emitTemporary(op, "extractvalue"
            + " %struct.deal_outcome_string " + outcome + ", 1");
        storeResult(op, LlvmTypeMapper.STRING, narrowFromAbi(
            RuntimeDescriptor.String.INSTANCE, text, op));
    }

    /** Stores one op result from a recorded source descriptor (C3). */
    private void storeResultOf(SemanticOp op, RuntimeDescriptor from,
                               String value) {
        // An op whose recorded result type is the internal-missing marker
        // carries the read's own representation; the consuming envelope
        // decides what it means.
        RuntimeDescriptor to = op.resultType()
            instanceof RuntimeDescriptor descriptor ? descriptor : from;
        if (!(op.result() instanceof ValueId)) {
            return;
        }
        String type = stagedRepresentation(to);
        storeResult(op, type, to.equals(from)
            ? value : coerce(from, to, value, op));
    }

    // =========================================================================
    // The entry-surface functions (C2)
    // =========================================================================

    /**
     * {@code deal_project_run_main}: the entry delegation. The entry
     * module's recorded {@code ENTRY_INVOKE} delegates exactly one
     * {@code CALL(DIRECT)} to the project's {@code main}, so this function
     * invokes that lowered function and returns its own outcome.
     */
    String emitRunMain(ModuleId entryModule) {
        this.functionId = null;
        LoweredModuleUnit unit = session.unitOf(entryModule);
        FunctionId main = null;
        for (SemanticOp op : unit.ops()) {
            if (op.payload() instanceof KindPayload.EntryInvokePayload invoke) {
                main = invoke.mainFunction();
                break;
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("define %struct.deal_outcome @deal_project_run_main() {\n");
        out.append("Lentry:\n");
        if (main == null) {
            out.append(LlvmIrPrinter.indent(1))
                .append("ret %struct.deal_outcome { i8* null }\n");
        } else {
            LoweredFunction function = session.function(main);
            RuntimeDescriptor result = function.descriptor().returnType();
            if (!LlvmTypeMapper.outcomeType(result)
                    .equals("%struct.deal_outcome")) {
                throw new IllegalStateException("the project entry function "
                    + main + " carries the result '"
                    + result.canonicalSpecText()
                    + "'; the entry delegation returns its error-only outcome");
            }
            int export = session.exportIndexOfFunction(entryModule, main);
            if (export < 0) {
                if (!function.captures().isEmpty()) {
                    throw new IllegalStateException("the entry function " + main
                        + " captures cells but the module publishes no export"
                        + " of it");
                }
                out.append(LlvmIrPrinter.indent(1)).append("%main = call ")
                    .append(LlvmTypeMapper.outcomeType(result))
                    .append(" @F").append(main.id())
                    .append("(%struct.deal_fn* null)\n");
                out.append(LlvmIrPrinter.indent(1))
                    .append("ret %struct.deal_outcome %main\n");
            } else {
                // The entry invoke drives the closure the unit published, so
                // the entry function's own captured cells are the recorded
                // ones (C2/C5) and no second closure identity is created.
                session.referenceRuntimeEntry("deal_fn_apply");
                int count = session.exportCount(entryModule);
                String entryPointer = "%entry" + main.id();
                out.append(LlvmIrPrinter.indent(1)).append(entryPointer)
                    .append(" = getelementptr [").append(count)
                    .append(" x %struct.deal_export_entry], [").append(count)
                    .append(" x %struct.deal_export_entry]* @")
                    .append(LlvmData.exportTable(
                        session.moduleIndex(entryModule)))
                    .append(", i64 0, i64 ").append(export).append('\n');
                out.append(LlvmIrPrinter.indent(1)).append("%mainvalue")
                    .append(" = getelementptr %struct.deal_export_entry,"
                        + " %struct.deal_export_entry* ").append(entryPointer)
                    .append(", i32 0, i32 4\n");
                out.append(LlvmIrPrinter.indent(1)).append("%mainfunction")
                    .append(" = load %struct.deal_fn*, %struct.deal_fn**"
                        + " %mainvalue\n");
                String origin = "@" + session.data.origin(
                    session.displayName(entryModule), 1, 1);
                out.append(LlvmIrPrinter.indent(1))
                    .append("%appliedslot = alloca %struct.deal_outcome_data\n");
                out.append(LlvmIrPrinter.indent(1))
                    .append("call void @deal_fn_apply(%struct.deal_outcome_data*"
                        + " sret(%struct.deal_outcome_data) %appliedslot,"
                        + " %struct.deal_fn* %mainfunction,"
                        + " %struct.deal_check_input* null, i32 0,"
                        + " %struct.deal_origin* " + origin + ")\n");
                out.append(LlvmIrPrinter.indent(1))
                    .append("%applied = load %struct.deal_outcome_data,"
                        + " %struct.deal_outcome_data* %appliedslot\n");
                out.append(LlvmIrPrinter.indent(1))
                    .append("%error = extractvalue %struct.deal_outcome_data"
                        + " %applied, 0\n");
                out.append(LlvmIrPrinter.indent(1))
                    .append("%result = insertvalue %struct.deal_outcome"
                        + " zeroinitializer, i8* %error, 0\n");
                out.append(LlvmIrPrinter.indent(1))
                    .append("ret %struct.deal_outcome %result\n");
            }
        }
        out.append("}\n");
        return out.toString();
    }

    /**
     * {@code deal_project_exports}: the module record of one successfully
     * initialized unit, or null.
     */
    String emitExports(List<ModuleId> modules) {
        List<String> entry = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        out.append("define %struct.deal_module* @deal_project_exports"
            + "(i8* %module_path) {\nLentry:\n");
        for (int index = 0; index < modules.size(); index++) {
            long moduleIndex = session.moduleIndex(modules.get(index));
            String path = session.data.operand(
                session.data.intern(modules.get(index).path()));
            entry.add(LlvmIrPrinter.indent(1) + "%eq" + index
                + " = call i1 @deal_text_equals(i8* %module_path, "
                + path + ")");
            entry.add(LlvmIrPrinter.indent(1) + "%state" + index
                + " = getelementptr %struct.deal_module, %struct.deal_module* @"
                + LlvmData.moduleRecord(moduleIndex) + ", i32 0, i32 1");
            entry.add(LlvmIrPrinter.indent(1) + "%statevalue" + index
                + " = load i32, i32* %state" + index);
            entry.add(LlvmIrPrinter.indent(1) + "%ready" + index
                + " = icmp eq i32 %statevalue" + index + ", 2");
            entry.add(LlvmIrPrinter.indent(1) + "%found" + index
                + " = and i1 %eq" + index + ", %ready" + index);
            entry.add(LlvmIrPrinter.indent(1) + "br i1 %found" + index
                + ", label %hit" + index + ", label %next" + index);
            entry.add("hit" + index + ":");
            entry.add(LlvmIrPrinter.indent(1) + "ret %struct.deal_module* @"
                + LlvmData.moduleRecord(moduleIndex));
            entry.add("next" + index + ":");
        }
        entry.add(LlvmIrPrinter.indent(1) + "ret %struct.deal_module* null");
        for (String line : entry) {
            out.append(line).append('\n');
        }
        out.append("}\n");
        return out.toString();
    }

    /**
     * {@code deal_export_lookup}: the first export record of one module
     * whose name equals the given name, or null.
     */
    String emitExportLookup() {
        StringBuilder out = new StringBuilder();
        out.append("define %struct.deal_export_entry* @deal_export_lookup"
            + "(%struct.deal_module* %module, i8* %name) {\nLentry:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%isnull = icmp eq %struct.deal_module* %module, null\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("br i1 %isnull, label %missing, label %scan\n");
        out.append("scan:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%countp = getelementptr %struct.deal_module,"
                + " %struct.deal_module* %module, i32 0, i32 2\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%count = load i32, i32* %countp\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%tablep = getelementptr %struct.deal_module,"
                + " %struct.deal_module* %module, i32 0, i32 3\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%table = load %struct.deal_export_entry*,"
                + " %struct.deal_export_entry** %tablep\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("br label %loop\n");
        out.append("loop:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%index = phi i32 [ 0, %scan ], [ %nextindex, %advance ]\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%inrange = icmp ult i32 %index, %count\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("br i1 %inrange, label %body, label %missing\n");
        out.append("body:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%slot = getelementptr %struct.deal_export_entry,"
                + " %struct.deal_export_entry* %table, i32 %index\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%namep = getelementptr %struct.deal_export_entry,"
                + " %struct.deal_export_entry* %slot, i32 0, i32 0\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%entryname = load i8*, i8** %namep\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%equal = call i1 @deal_text_equals(i8* %name, i8* %entryname)\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("br i1 %equal, label %hit, label %advance\n");
        out.append("hit:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("ret %struct.deal_export_entry* %slot\n");
        out.append("advance:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%nextindex = add i32 %index, 1\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("br label %loop\n");
        out.append("missing:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("ret %struct.deal_export_entry* null\n}\n");
        return out.toString();
    }

    /**
     * {@code deal_export_invoke}: the zero-arity synchronous invocation of
     * one recorded export through its own code pointer.
     */
    String emitExportInvoke() {
        session.referenceRuntimeEntry("deal_fn_apply");
        String origin = "@" + session.data.origin(
            session.displayName(unit.moduleId()), 1, 1);
        StringBuilder out = new StringBuilder();
        out.append("define %struct.deal_outcome @deal_export_invoke"
            + "(%struct.deal_export_entry* %entry) {\nLentry:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%isnull = icmp eq %struct.deal_export_entry* %entry, null\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("br i1 %isnull, label %absent, label %invoke\n");
        out.append("invoke:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%valuep = getelementptr %struct.deal_export_entry,"
                + " %struct.deal_export_entry* %entry, i32 0, i32 4\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%function = load %struct.deal_fn*,"
                + " %struct.deal_fn** %valuep\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%appliedslot = alloca %struct.deal_outcome_data\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("call void @deal_fn_apply(%struct.deal_outcome_data*"
                + " sret(%struct.deal_outcome_data) %appliedslot,"
                + " %struct.deal_fn* %function,"
                + " %struct.deal_check_input* null, i32 0,"
                + " %struct.deal_origin* " + origin + ")\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%applied = load %struct.deal_outcome_data,"
                + " %struct.deal_outcome_data* %appliedslot\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%error = extractvalue %struct.deal_outcome_data"
                + " %applied, 0\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%result = insertvalue %struct.deal_outcome"
                + " zeroinitializer, i8* %error, 0\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("ret %struct.deal_outcome %result\n");
        out.append("absent:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("ret %struct.deal_outcome { i8* null }\n}\n");
        return out.toString();
    }

    /** {@code deal_async_export_invoke}: the recorded export's own outcome. */
    String emitAsyncExportInvoke() {
        StringBuilder out = new StringBuilder();
        out.append("define %struct.deal_outcome @deal_async_export_invoke"
            + "(%struct.deal_export_entry* %entry) {\nLentry:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%result = call %struct.deal_outcome @deal_export_invoke"
                + "(%struct.deal_export_entry* %entry)\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("ret %struct.deal_outcome %result\n}\n");
        return out.toString();
    }

    /**
     * The module's byte-equality helper (no libc dependency): the path and
     * export-name comparisons of the entry surface use it.
     */
    String emitTextEquals() {
        return """
define internal i1 @deal_text_equals(i8* %left, i8* %right) {
Lentry:
  %leftnull = icmp eq i8* %left, null
  %rightnull = icmp eq i8* %right, null
  %eithernull = or i1 %leftnull, %rightnull
  br i1 %eithernull, label %nullcase, label %loop
nullcase:
  %bothnull = and i1 %leftnull, %rightnull
  ret i1 %bothnull
loop:
  %index = phi i64 [ 0, %Lentry ], [ %nextindex, %same ]
  %leftp = getelementptr i8, i8* %left, i64 %index
  %rightp = getelementptr i8, i8* %right, i64 %index
  %leftc = load i8, i8* %leftp
  %rightc = load i8, i8* %rightp
  %equal = icmp eq i8 %leftc, %rightc
  br i1 %equal, label %same, label %different
same:
  %leftend = icmp eq i8 %leftc, 0
  %nextindex = add i64 %index, 1
  br i1 %leftend, label %matched, label %loop
matched:
  ret i1 true
different:
  ret i1 false
}
""";
    }

}
