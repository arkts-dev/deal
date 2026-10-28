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
            case BINDING_ALLOC, ENTRY_INVOKE -> {
                // Structural in this stage: the binding slot is declared on
                // first use and the entry delegation is
                // DEAL_PROJECT_RUN_MAIN's own call.
            }
            case ASSIGN -> {
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
        if (operandDescriptor instanceof RuntimeDescriptor.Class classType
                && classType.classId().equals(target instanceof
                    RuntimeDescriptor.Class targetClass
                        ? targetClass.classId() : null)) {
            return "true";
        }
        if (!(operandDescriptor instanceof RuntimeDescriptor.Unknown)) {
            // A number, boolean, string, nullish or function value is never a
            // nominal class instance.
            return "false";
        }
        String datum = loadRepresentation(input, operandDescriptor, op);
        String tag = emitTemporary(op, "extractvalue %struct.deal_data "
            + datum + ", 0");
        return emitTemporary(op, "icmp eq i32 " + tag + ", "
            + LlvmTypeMapper.DATA_ERROR);
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
                String leftOperand = loadRepresentation(left, leftDescriptor, op);
                String rightOperand = loadRepresentation(right, rightDescriptor, op);
                yield emitTemporary(op, "icmp " + arm.predicate() + " i8* "
                    + leftOperand + ", " + rightOperand);
            }
        };
        storeResult(op, resultRepresentation(op).orElseThrow(), result);
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
            default -> throw new IllegalStateException(
                "the nullish base '" + inner.canonicalSpecText()
                    + "' has no staged equality");
        };
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
        return switch (inner) {
            case RuntimeDescriptor.String ignored -> emitTemporary(op,
                "bitcast i8* " + pointer + " to " + LlvmTypeMapper.STRING);
            case RuntimeDescriptor.Func ignored -> pointer;
            default -> throw new IllegalStateException(
                "the nullish base '" + inner.canonicalSpecText()
                    + "' has no staged representation");
        };
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
        storeResult(op, representation.get(), loaded);
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
        if (to instanceof RuntimeDescriptor.Unknown) {
            String operand = LlvmTypeMapper.isStaticSingleton(from)
                ? "" : loadRepresentation(value, from, op);
            return emitDatum(from, operand, op);
        }
        if (to instanceof RuntimeDescriptor.Nullable nullable) {
            return emitNullish(from, nullable, value, op);
        }
        throw new IllegalStateException("the value " + value + " of type '"
            + from.canonicalSpecText() + "' has no coercion into '"
            + to.canonicalSpecText() + "'");
    }

    /**
     * Builds one nullish value of {@code target} from a static singleton, its
     * base type or the same nullish type (C3): a scalar base uses the tagged
     * struct, a reference base the pointer or the admitted singleton.
     */
    private String emitNullish(RuntimeDescriptor from,
                               RuntimeDescriptor.Nullable target,
                               ValueId value, SemanticOp op) {
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
            return loadRepresentation(value, from, op);
        }
        if (!admittedBase(target, from)) {
            throw new IllegalStateException("the value's type '"
                + from.canonicalSpecText() + "' is not admitted by '"
                + target.canonicalSpecText() + "'");
        }
        String operand = loadRepresentation(value, from, op);
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
        boolean admitted = nullish ? target.admitsNull() : undefined
            ? target.admitsUndefined() : false;
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
     * {@code unknown} position (C3, R3). The emitted module constructs the
     * structural {@code deal_data} record directly — the header publishes its
     * layout and the emitted module owns these values — so no runtime
     * constructor and no allocation is involved. A nullish operand takes the
     * tag of its own stored value, so absence is never confused with a base
     * value.
     */
    private String emitDatum(RuntimeDescriptor from, String operand,
                             SemanticOp op) {
        if (from instanceof RuntimeDescriptor.Unknown) {
            return operand;
        }
        if (from instanceof RuntimeDescriptor.Null) {
            return datumLiteral(LlvmTypeMapper.DATA_NULL, "double 0.0",
                "i8* null");
        }
        if (from instanceof RuntimeDescriptor.Undefined) {
            return datumLiteral(LlvmTypeMapper.DATA_UNDEFINED, "double 0.0",
                "i8* null");
        }
        if (from instanceof RuntimeDescriptor.Number) {
            return emitTemporary(op, "insertvalue " + LlvmTypeMapper.DATA
                + " " + datumLiteral(LlvmTypeMapper.DATA_NUMBER,
                    "double 0.0", "i8* null")
                + ", double " + operand + ", 2");
        }
        if (from instanceof RuntimeDescriptor.Boolean) {
            String folded = booleanToNumber(operand, op);
            return emitTemporary(op, "insertvalue " + LlvmTypeMapper.DATA
                + " " + datumLiteral(LlvmTypeMapper.DATA_BOOLEAN,
                    "double 0.0", "i8* null")
                + ", double " + folded + ", 2");
        }
        if (from instanceof RuntimeDescriptor.String) {
            String bytes = emitTemporary(op, "bitcast " + LlvmTypeMapper.STRING
                + " " + operand + " to i8*");
            return emitTemporary(op, "insertvalue " + LlvmTypeMapper.DATA
                + " " + datumLiteral(LlvmTypeMapper.DATA_STRING,
                    "double 0.0", "i8* null")
                + ", i8* " + bytes + ", 3");
        }
        if (from instanceof RuntimeDescriptor.Func) {
            // The function tag alone classifies the value (the `typeof`
            // projection needs no payload): the managed `reference` member
            // stays NULL, because a code pointer is not a `deal_fn*` and a
            // managed tag over a `.text` address is a collector defect (C3,
            // R2). A function value at a declared data position is rejected
            // by the classifier's `unknown` relation instead of boxed here.
            return datumLiteral(LlvmTypeMapper.DATA_FUNCTION, "double 0.0",
                "i8* null");
        }
        if (from instanceof RuntimeDescriptor.Class classType
                && classType.classId().equals(deal.semantic.ir.ClassId.ERROR)) {
            String reduced = emitTemporary(op, "bitcast "
                + LlvmTypeMapper.ERROR_POINTER + " " + operand + " to i8*");
            return emitTemporary(op, "insertvalue " + LlvmTypeMapper.DATA
                + " " + datumLiteral(LlvmTypeMapper.DATA_ERROR,
                    "double 0.0", "i8* null")
                + ", i8* " + reduced + ", 3");
        }
        if (from instanceof RuntimeDescriptor.Nullable nullable) {
            return emitNullishDatum(nullable, operand, op);
        }
        throw new IllegalStateException("the value of type '"
            + from.canonicalSpecText() + "' has no persistent data form");
    }

    /**
     * One structural {@code deal_data} literal of this stage, spelled without
     * its type so the use site writes the type exactly once (a {@code store},
     * an {@code insertvalue} aggregate or a call argument).
     */
    private static String datumLiteral(int tag, String number, String reference) {
        return "{ i32 " + tag + ", i32 0, " + number + ", " + reference + " }";
    }

    /**
     * The binary64 payload of one boolean datum: the published
     * {@code deal_data_of_boolean} stores 0.0/1.0, and the closed data
     * relation compares that payload by its zero-ness.
     */
    private String booleanToNumber(String operand, SemanticOp op) {
        String widened = emitTemporary(op, "zext i1 " + operand + " to i32");
        return emitTemporary(op, "uitofp i32 " + widened + " to double");
    }

    /** The tag-aware datum of one nullish operand. */
    private String emitNullishDatum(RuntimeDescriptor.Nullable nullable,
                                    String operand, SemanticOp op) {
        String representation = stagedRepresentation(nullable);
        String slot = newAlloca("%struct.deal_data", "nullishdatum");
        String baseLabel = newLabel("datum.base");
        String nullLabel = newLabel("datum.null");
        String undefinedLabel = newLabel("datum.undefined");
        String joinLabel = newLabel("datum.join");
        if (representation.equals(LlvmTypeMapper.REFERENCE_NULLISH)) {
            String isNull = emitTemporary(op,
                "call i32 @deal_value_is_null(i8* " + operand + ")");
            String notNull = emitTemporary(op, "icmp eq i32 " + isNull + ", 0");
            emit("br i1 " + notNull + ", label %" + baseLabel + ", label %"
                + nullLabel);
            terminated = true;
            startSegment(nullLabel);
            emitNullishDatumArm(slot, "null", joinLabel, op);
            startSegment(baseLabel);
            session.referenceRuntimeEntry("deal_value_is_undefined");
            String isUndefined = emitTemporary(op,
                "call i32 @deal_value_is_undefined(i8* " + operand + ")");
            String notUndefined = emitTemporary(op,
                "icmp eq i32 " + isUndefined + ", 0");
            String baseDatumLabel = newLabel("datum.value");
            String baseArm = nullable.inner() instanceof RuntimeDescriptor.Undefined
                ? undefinedLabel : baseDatumLabel;
            emit("br i1 " + notUndefined + ", label %" + baseArm
                + ", label %" + undefinedLabel);
            terminated = true;
            startSegment(undefinedLabel);
            emitNullishDatumArm(slot, "undefined", joinLabel, op);
            startSegment(baseDatumLabel);
            String baseDatum = emitDatum(nullable.inner(),
                castBase(nullable.inner(), operand, op), op);
            emit("store %struct.deal_data " + baseDatum
                + ", %struct.deal_data* " + slot);
            terminate("br label %" + joinLabel);
            startSegment(joinLabel);
            return emitTemporary(op, "load %struct.deal_data, "
                + "%struct.deal_data* " + slot);
        }
        String tag = emitTemporary(op, "extractvalue " + representation + " "
            + operand + ", 0");
        emit("switch i32 " + tag + ", label %" + baseLabel + " [ i32 "
            + LlvmTypeMapper.NULLISH_NULL + ", label %" + nullLabel + " i32 "
            + LlvmTypeMapper.NULLISH_UNDEFINED + ", label %" + undefinedLabel
            + " ]");
        terminated = true;
        startSegment(nullLabel);
        emitNullishDatumArm(slot, "null", joinLabel, op);
        startSegment(undefinedLabel);
        emitNullishDatumArm(slot, "undefined", joinLabel, op);
        startSegment(baseLabel);
        String base = emitTemporary(op, "extractvalue " + representation + " "
            + operand + ", 1");
        String datum = emitDatum(nullable.inner(), base, op);
        emit("store %struct.deal_data " + datum + ", %struct.deal_data* " + slot);
        terminate("br label %" + joinLabel);
        startSegment(joinLabel);
        return emitTemporary(op, "load %struct.deal_data, %struct.deal_data* "
            + slot);
    }

    /** Stores one nullish datum arm and jumps to the join. */
    private void emitNullishDatumArm(String slot, String kind, String joinLabel,
                                     SemanticOp op) {
        int tag = kind.equals("null") ? LlvmTypeMapper.DATA_NULL
            : LlvmTypeMapper.DATA_UNDEFINED;
        emit("store %struct.deal_data "
            + datumLiteral(tag, "double 0.0", "i8* null") + ", "
            + "%struct.deal_data* " + slot);
        terminate("br label %" + joinLabel);
    }

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
        String representation = LlvmTypeMapper.representationOf(declared)
            .orElseThrow(() -> new IllegalStateException(
                "the checked type '" + declared.canonicalSpecText()
                    + "' has no staged representation"));
        if (LlvmTypeMapper.convertible(declared, actual)) {
            // The operand's own static type already proves the declared
            // descriptor, so the boundary is the identity of that value.
            storeResult(op, representation,
                convert(payload.input(), actual, declared, op));
            return;
        }
        if (!(declared instanceof RuntimeDescriptor.Number
                || declared instanceof RuntimeDescriptor.Boolean
                || declared instanceof RuntimeDescriptor.String
                || declared instanceof RuntimeDescriptor.Class)) {
            throw new IllegalStateException("the assertion to '"
                + declared.canonicalSpecText() + "' has no staged check");
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
        session.referenceRuntimeEntry("deal_descriptor_intern");
        session.referenceRuntimeEntry("deal_check_value");
        String datum = checkInput(op, input, actual);
        String atom = emitTemporary(op, "call"
            + " %struct.deal_descriptor* @deal_descriptor_intern("
            + session.data.operand(session.data.intern(
                declared.canonicalSpecText())) + ")");
        String context = checkContext(op);
        String outcome = emitTemporary(op, "call %struct.deal_outcome"
            + " @deal_check_value(%struct.deal_descriptor* " + atom
            + ", %struct.deal_check_input* byval(%struct.deal_check_input) "
            + datum + ", i32 0, %struct.deal_check_context* " + context + ")");
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
        return checkedValue(op, declared, datum);
    }

    /** The declared-typed value of one checked datum. */
    private String checkedValue(SemanticOp op, RuntimeDescriptor declared,
                                String inputSlot) {
        String input = loadQuiet("%struct.deal_check_input", inputSlot);
        String datum = emitTemporary(op, "extractvalue "
            + "%struct.deal_check_input " + input + ", 2");
        return switch (declared) {
            case RuntimeDescriptor.Number _ -> emitTemporary(op,
                "extractvalue " + LlvmTypeMapper.DATA + " " + datum + ", 2");
            case RuntimeDescriptor.Boolean _ -> {
                String number = emitTemporary(op, "extractvalue "
                    + LlvmTypeMapper.DATA + " " + datum + ", 2");
                yield emitTemporary(op, "fcmp une double " + number + ", 0.0");
            }
            case RuntimeDescriptor.String _ -> {
                String reference = emitTemporary(op, "extractvalue "
                    + LlvmTypeMapper.DATA + " " + datum + ", 3");
                yield emitTemporary(op, "bitcast i8* " + reference + " to "
                    + LlvmTypeMapper.STRING);
            }
            case RuntimeDescriptor.Class _ -> {
                String reference = emitTemporary(op, "extractvalue "
                    + LlvmTypeMapper.DATA + " " + datum + ", 3");
                yield emitTemporary(op, "bitcast i8* " + reference + " to "
                    + LlvmTypeMapper.ERROR_POINTER);
            }
            default -> throw new IllegalStateException("the checked type '"
                + declared.canonicalSpecText() + "' has no staged value form");
        };
    }

    /**
     * Stores the datum of one checked operand into the scratch slot the
     * {@code byval} check input copies from.
     */
    private String checkInput(SemanticOp op, ValueId input,
                              RuntimeDescriptor actual) {
        String datum;
        if (LlvmTypeMapper.isStaticSingleton(actual)) {
            datum = emitDatum(actual, "", op);
        } else {
            datum = emitDatum(actual, loadRepresentation(input, actual, op), op);
        }
        String slot = newAlloca("%struct.deal_check_input", "checked");
        String record = emitTemporary(op, "insertvalue"
            + " %struct.deal_check_input zeroinitializer, "
            + LlvmTypeMapper.DATA + " " + datum + ", 2");
        emit("store %struct.deal_check_input " + record
            + ", %struct.deal_check_input* " + slot);
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
        if (descriptor instanceof RuntimeDescriptor.String
                || descriptor instanceof RuntimeDescriptor.Class) {
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
        if (descriptor instanceof RuntimeDescriptor.String
                || descriptor instanceof RuntimeDescriptor.Class) {
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
        String representation = stagedRepresentation(descriptor);
        if (representation.equals(LlvmTypeMapper.STRING)
                || representation.equals(LlvmTypeMapper.ERROR_POINTER)) {
            return "i8*";
        }
        return representation;
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
        if (representation.equals(LlvmTypeMapper.STRING)
                || representation.equals(LlvmTypeMapper.ERROR_POINTER)) {
            return emitTemporary(op, "bitcast " + representation + " " + value
                + " to i8*");
        }
        return value;
    }

    /**
     * The SSA form of one returned ABI payload (C3's boolean byte). The outcome
     * value slot already holds the staged representation, so only the two
     * managed pointer shapes whose SSA spelling is a distinct pointer type need
     * the one cast: a {@code string} is {@code %struct.deal_string*} and an
     * {@code Error} is {@code %struct.deal_error*}, both carried as {@code i8*}.
     * A scalar-nullish aggregate ({@code { i32, double }} / {@code { i32, i1 }}),
     * a reference nullish pointer, a {@code double}, a persistent datum and a
     * code pointer are their own slot type and are never cast.
     */
    private String narrowFromAbi(RuntimeDescriptor descriptor, String value,
                                 SemanticOp op) {
        if (descriptor instanceof RuntimeDescriptor.Boolean) {
            return emitTemporary(op, "trunc i8 " + value + " to "
                + LlvmTypeMapper.BOOLEAN_SSA);
        }
        if (descriptor instanceof RuntimeDescriptor.String) {
            return emitTemporary(op, "bitcast i8* " + value + " to "
                + LlvmTypeMapper.STRING);
        }
        if (descriptor instanceof RuntimeDescriptor.Class) {
            return emitTemporary(op, "bitcast i8* " + value + " to "
                + LlvmTypeMapper.ERROR_POINTER);
        }
        return value;
    }

    /** The lowered function one static call resolves to. */
    private FunctionId calleeOf(KindPayload.CallPayload payload) {
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
