package deal.codegen.llvm;

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
 * root-frame leave plus call-frame pop on every exit path. A failure routed to
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
        // Lentry already terminates in the branch assemble() writes.
        terminated = true;
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
            .append(name).append('(');
        for (int index = 0; index < function.descriptor().paramTypes().size();
                index++) {
            if (index > 0) {
                out.append(", ");
            }
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
        out.append(LlvmIrPrinter.indent(1)).append("br label %")
            .append(blockLabel(function.body())).append('\n');
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

    private void emitEpilogue() {
        session.referenceRuntimeEntry("deal_root_frame_leave");
        session.referenceRuntimeEntry("deal_call_frame_pop");
        emit("call void @deal_root_frame_leave()");
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
        entryInit.add(LlvmIrPrinter.indent(1) + "%st" + index
            + " = load i32, i32* " + statePointer);
        entryInit.add(LlvmIrPrinter.indent(1) + "%uninitialized" + index
            + " = icmp eq i32 %st" + index + ", 0");
        entryInit.add(LlvmIrPrinter.indent(1) + "br i1 %uninitialized" + index
            + ", label %" + start + ", label %" + finish);
        text.append(start).append(":\n");
        terminated = false;
        emit("store i32 1, i32* " + statePointer);
        emit("br label %" + blockLabel(initUnit.moduleInit().initBlock()));
        terminated = true;
        emitBlock(initUnit.moduleInit().initBlock(), finish);
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
            case BINDING_ALLOC, ENTRY_INVOKE -> {
                // Structural in this stage: the binding slot is declared on
                // first use and the entry delegation is
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
            case MODULE_INIT, EXPORT_PUBLISH -> {
                // The module-init state machine wraps the init block and the
                // export record is static data (C2).
            }
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
                String leftOperand = referenceOperand(op, left, leftDescriptor);
                String rightOperand = referenceOperand(op, right, rightDescriptor);
                yield emitTemporary(op, "icmp " + arm.predicate() + " i8* "
                    + leftOperand + ", " + rightOperand);
            }
        };
        storeResult(op, resultRepresentation(op).orElseThrow(), result);
    }

    /** One reference operand as the byte pointer managed identity compares. */
    private String referenceOperand(SemanticOp op, ValueId value,
                                    RuntimeDescriptor descriptor) {
        String representation = stagedRepresentation(descriptor);
        String operand = loadRepresentation(value, descriptor, op);
        if (representation.equals("i8*")) {
            return operand;
        }
        if (LlvmTypeMapper.isManagedPointer(descriptor)) {
            return emitTemporary(op, "bitcast " + representation + " "
                + operand + " to i8*");
        }
        return operand;
    }

    /** {@code STRING_*}: the one {@code deal_text_compare} authority (R8). */
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
                "icmp " + arm.predicate() + " i8* " + left + ", " + right);
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
            return pointer;
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
            + inputSlot + ", i32 0, %struct.deal_check_context* " + context + ")");
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
        if (session.moduleScoped(unit, binding, generation)) {
            return "@" + session.data.globalSlot(binding, generation, type);
        }
        String key = binding.id() + "_" + generation;
        String slot = bindingSlots.computeIfAbsent(key, ignored -> {
            String name = session.cell(binding, generation);
            allocas.add(name + " = alloca " + type);
            return name;
        });
        String bound = bindingSlotTypes.putIfAbsent(key, type);
        if (bound != null && !bound.equals(type)) {
            throw new IllegalStateException("the binding " + binding
                + " is used as both " + bound + " and " + type);
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
        if (LlvmTypeMapper.isManagedPointer(descriptor)) {
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
        if (LlvmTypeMapper.isManagedPointer(descriptor)) {
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

    private void emitClosureNew(SemanticOp op) {
        KindPayload.ClosureNewPayload payload =
            (KindPayload.ClosureNewPayload) op.payload();
        FunctionId target = payload.function();
        String literal = "bitcast (" + functionCodeType(target) + " @F"
            + target.id() + " to i8*)";
        storeResult(op, LlvmTypeMapper.CODE_POINTER, literal);
    }

    private String functionCodeType(FunctionId target) {
        LoweredFunction function = session.function(target);
        StringBuilder type = new StringBuilder(
            LlvmTypeMapper.outcomeType(function.descriptor().returnType()));
        type.append(" (");
        for (int index = 0; index < function.descriptor().paramTypes().size();
                index++) {
            if (index > 0) {
                type.append(", ");
            }
            type.append(LlvmTypeMapper.abiParameterType(
                function.descriptor().paramTypes().get(index)));
        }
        return type.append(")*").toString();
    }

    private void emitExportRead(SemanticOp op) {
        KindPayload.ExportReadPayload payload =
            (KindPayload.ExportReadPayload) op.payload();
        FunctionId target = exportedFunction(payload.module(), payload.name());
        String literal = "bitcast (" + functionCodeType(target) + " @F"
            + target.id() + " to i8*)";
        storeResult(op, LlvmTypeMapper.CODE_POINTER, literal);
    }

    /**
     * The lowered function one export name resolves to: the target unit's
     * recorded {@code EXPORT_PUBLISH} of that name, whose value is the
     * published closure (the classifier accepted it).
     */
    private FunctionId exportedFunction(ModuleId module, String name) {
        LoweredModuleUnit target = session.unitOf(module);
        for (SemanticOp op : target.ops()) {
            if (!(op.payload() instanceof KindPayload.ExportPublishPayload published)
                    || !published.name().equals(name)) {
                continue;
            }
            SemanticOp producer = session.producerOf(published.value());
            if (producer != null
                    && producer.payload() instanceof KindPayload
                        .ClosureNewPayload closure) {
                return closure.function();
            }
        }
        throw new IllegalStateException("the module " + module
            + " publishes no closure export named '" + name + "'");
    }

    private void emitCall(SemanticOp op) {
        KindPayload.CallPayload payload = (KindPayload.CallPayload) op.payload();
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
        emit(result + " = call " + outcome + " @F" + callee.id() + "("
            + arguments + ")" + debugAttachment(op));
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

    /** The C4 outcome value-slot type of one declared result descriptor. */
    private static String outcomeSlotType(RuntimeDescriptor descriptor) {
        if (descriptor instanceof RuntimeDescriptor.Boolean) {
            return LlvmTypeMapper.BOOLEAN_ABI;
        }
        if (LlvmTypeMapper.isManagedPointer(descriptor)) {
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
        if (LlvmTypeMapper.isManagedPointer(descriptor)) {
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

    /** Writes one iteration's fresh binding and roots it (M4, R2). */
    private void bindIteration(BindingId binding, long generation,
                               RuntimeDescriptor element, String representation,
                               String value, SemanticOp op) {
        String slot = bindingSlot(binding, generation, representation);
        emit("store " + representation + " " + value + ", " + representation
            + "* " + slot);
        rootBindingSlot(binding, generation, element, slot, op);
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
            out.append(LlvmIrPrinter.indent(1)).append("%main = call ")
                .append(LlvmTypeMapper.outcomeType(result))
                .append(" @F").append(main.id()).append("()\n");
            out.append(LlvmIrPrinter.indent(1))
                .append("ret %struct.deal_outcome %main\n");
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
        StringBuilder out = new StringBuilder();
        out.append("define %struct.deal_outcome @deal_export_invoke"
            + "(%struct.deal_export_entry* %entry) {\nLentry:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%isnull = icmp eq %struct.deal_export_entry* %entry, null\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("br i1 %isnull, label %absent, label %invoke\n");
        out.append("invoke:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%codep = getelementptr %struct.deal_export_entry,"
                + " %struct.deal_export_entry* %entry, i32 0, i32 4\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%code = load i8*, i8** %codep\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%hasCode = icmp ne i8* %code, null\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("br i1 %hasCode, label %invokecode, label %absent\n");
        out.append("invokecode:\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%call = bitcast i8* %code to %struct.deal_outcome ()*\n");
        out.append(LlvmIrPrinter.indent(1))
            .append("%result = call %struct.deal_outcome %call()\n");
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
