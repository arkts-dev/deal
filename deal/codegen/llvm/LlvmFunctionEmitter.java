package deal.codegen.llvm;

import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
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
 * The basic-block, SSA-value and failure-route emitter of the native backend
 * ({@code llvm-native-codegen-cfg-ssa} Architecture, {@code LlvmFunctionEmitter};
 * C2, C4, C8, {@code OpRendering}).
 *
 * <p>Structured IR maps directly to a CFG: a {@code BRANCH} op emits its
 * condition and its two child blocks around a join, a {@code LOOP} emits its
 * per-iteration init block, its condition check, its body and its back edge,
 * and {@code BREAK}/{@code CONTINUE} transfer to the recorded loop's own
 * labels. Results are SSA values; a managed temporary would need a root slot
 * (M4), and the staged subset renders none, so every emitted function enters
 * its root frame with the statically known slot count zero.</p>
 *
 * <p>Every generated activation emits the ABI task's prologue and epilogue:
 * the call-frame push with its frame label, the root-frame enter with the
 * static slot count, and — on every exit path — the root-frame leave and the
 * call-frame pop, so the caller's frame and root depth are restored
 * ({@code CallFrameContract}). The failure carrier of C4 is the ABI outcome
 * struct: this stage emits no failure-materializing entry, so every emitted
 * path completes successfully and the classifier has already rejected every
 * operation that could detect a failure.</p>
 */
final class LlvmFunctionEmitter {

    private final LlvmSession session;
    private final LoweredModuleUnit unit;
    private final StringBuilder text = new StringBuilder();
    private final List<String> allocas = new ArrayList<>();
    private final List<String> entryInit = new ArrayList<>();
    private final Map<Long, String> valueSlots = new LinkedHashMap<>();
    private final Map<String, String> bindingSlots = new LinkedHashMap<>();
    private final Map<Long, String> blockLabels = new LinkedHashMap<>();
    private final Deque<LoopTargets> loops = new ArrayDeque<>();
    private int loadCounter;
    private int labelCounter;
    private boolean terminated;
    private boolean fallsThrough;
    private int currentDebugScope;
    private FunctionId functionId;

    /** One open loop's transfer labels. */
    private record LoopTargets(long loopId, String header, String exit) {
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
        List<String> prologue = new ArrayList<>();
        prologue.add(LlvmIrPrinter.indent(1) + "call void @deal_call_frame_push("
            + frameLabelOperand(id) + ")");
        prologue.add(LlvmIrPrinter.indent(1)
            + "call void @deal_root_frame_enter(i32 0)");
        entryInit.addAll(prologue);
        emitParameters(id, function);
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
        for (int index = 0; index < parameters.size(); index++) {
            RuntimeDescriptor descriptor = types.get(index);
            String abi = LlvmTypeMapper.abiParameterType(descriptor);
            String ssa = descriptor instanceof RuntimeDescriptor.Boolean
                ? LlvmTypeMapper.BOOLEAN_SSA : abi;
            String slot = bindingSlot(parameters.get(index), 0,
                session.bindingRepresentation(unit, parameters.get(index), 0)
                    .orElseThrow(() -> new IllegalStateException(
                        "a parameter binding carries no representation")));
            String value = "%p" + index;
            if (!abi.equals(ssa)) {
                String narrowed = "%pn" + index;
                entryInit.add(LlvmIrPrinter.indent(1) + narrowed
                    + " = icmp ne " + abi + " " + value + ", 0");
                value = narrowed;
            }
            entryInit.add(LlvmIrPrinter.indent(1) + "store " + ssa + " "
                + value + ", " + ssa + "* " + slot);
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
     * carried {@code deal_error*} with no value slot read (C4).
     */
    private void terminateFailure(String error) {
        String aggregate = "%failure" + (labelCounter++) + "v";
        emit(aggregate + " = insertvalue %struct.deal_outcome zeroinitializer,"
            + " i8* " + error + ", 0");
        terminate("ret %struct.deal_outcome " + aggregate);
    }

    private void emitEpilogue() {
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
        entryInit.add(LlvmIrPrinter.indent(1)
            + "call void @deal_call_frame_push("
            + session.data.operand(label) + ")");
        entryInit.add(LlvmIrPrinter.indent(1)
            + "call void @deal_root_frame_enter(i32 0)");
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
        text.append(label).append(":\n");
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
            if (next == null) {
                fallsThrough = true;
            }
            terminate("br label %" + (next == null
                ? functionEndLabel() : next));
        }
    }

    // =========================================================================
    // Op rendering (the staged subset)
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
            case BINDING_ALLOC, ASSIGN, DISCARD, BOUNDARY, MODULE_INIT,
                 EXPORT_PUBLISH, ENTRY_INVOKE -> {
                // Structural or elided in this stage: the binding slot is
                // declared on first use, a chain's children carry the effects,
                // an elided boundary's static type already proves the declared
                // descriptor, the module-init state machine wraps the init
                // block, the export record is static data, and the entry
                // delegation is DEAL_PROJECT_RUN_MAIN's own call.
            }
            case BINDING_INIT, BINDING_STORE -> emitBindingWrite(op);
            case BINDING_LOAD -> emitBindingLoad(op);
            case MODULE_IMPORT -> emitModuleImport(op);
            case CLOSURE_NEW -> emitClosureNew(op);
            case EXPORT_READ -> emitExportRead(op);
            case CALL -> emitCall(op);
            case BRANCH -> emitBranch(op);
            case LOOP -> emitLoop(op);
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

    private void emitConst(SemanticOp op) {
        KindPayload.ConstPayload payload = (KindPayload.ConstPayload) op.payload();
        Optional<String> representation = resultRepresentation(op);
        if (representation.isEmpty()) {
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

    private void emitUnary(SemanticOp op) {
        KindPayload.UnaryPayload payload = (KindPayload.UnaryPayload) op.payload();
        ValueId input = op.operands().get(0);
        String operandRepresentation = representationOf(input);
        String operand = loadValue(input, operandRepresentation, op);
        String result = switch (payload.selector()) {
            case BOOL_NOT -> {
                yield emitTemporary(op, "xor i1 " + operand + ", true");
            }
            case NUMBER_NEG -> {
                yield emitTemporary(op, "fneg double " + operand);
            }
            default -> throw new IllegalStateException(
                "the unary selector " + payload.selector() + " is not staged");
        };
        storeResult(op, resultRepresentation(op).orElseThrow(), result);
    }

    private void emitBinary(SemanticOp op) {
        KindPayload.BinaryPayload payload = (KindPayload.BinaryPayload) op.payload();
        ValueId left = op.operands().get(0);
        ValueId right = op.operands().get(1);
        String leftType = representationOf(left);
        String rightType = representationOf(right);
        String leftOperand = loadValue(left, leftType, op);
        String rightOperand = loadValue(right, rightType, op);
        String instruction = switch (payload.selector()) {
            case NUMBER_ADD -> "fadd double " + leftOperand + ", " + rightOperand;
            case NUMBER_SUB -> "fsub double " + leftOperand + ", " + rightOperand;
            case NUMBER_MUL -> "fmul double " + leftOperand + ", " + rightOperand;
            case NUMBER_DIV_IEEE -> "fdiv double " + leftOperand + ", "
                + rightOperand;
            case NUMBER_EQ -> "fcmp oeq double " + leftOperand + ", " + rightOperand;
            case NUMBER_NE -> "fcmp une double " + leftOperand + ", " + rightOperand;
            case NUMBER_LT -> "fcmp olt double " + leftOperand + ", " + rightOperand;
            case NUMBER_LE -> "fcmp ole double " + leftOperand + ", " + rightOperand;
            case NUMBER_GT -> "fcmp ogt double " + leftOperand + ", " + rightOperand;
            case NUMBER_GE -> "fcmp oge double " + leftOperand + ", " + rightOperand;
            case BOOLEAN_EQ -> "icmp eq i1 " + leftOperand + ", " + rightOperand;
            case BOOLEAN_NE -> "icmp ne i1 " + leftOperand + ", " + rightOperand;
            default -> throw new IllegalStateException(
                "the binary selector " + payload.selector() + " is not staged");
        };
        String result = emitTemporary(op, instruction);
        storeResult(op, resultRepresentation(op).orElseThrow(), result);
    }

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
        String operand = loadValue(value, representation.get(), op);
        emit("store " + representation.get() + " " + operand + ", "
            + representation.get() + "* "
            + bindingSlot(binding, generation, representation.get()));
    }

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
        String fail = newLabel("importfail");
        String proceed = newLabel("importok");
        emit("br i1 " + (emitTemporary(null, "icmp ne i8* %importerr"
            + op.opId().id() + ", null")) + ", label %" + fail + ", label %"
            + proceed);
        terminated = true;
        startSegment(fail);
        emitEpilogue();
        terminateFailure("%importerr" + op.opId().id());
        startSegment(proceed);
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
        // The owned parameter and return boundaries are elided exactly because
        // the static operand types already equal their declared descriptors.
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
            String abi = LlvmTypeMapper.abiParameterType(declared);
            String value = loadValue(op.operands().get(index),
                declared instanceof RuntimeDescriptor.Boolean
                    ? LlvmTypeMapper.BOOLEAN_SSA : abi, op);
            if (declared instanceof RuntimeDescriptor.Boolean) {
                String widened = emitTemporary(null,
                    "zext i1 " + value + " to " + abi);
                arguments.append(abi).append(' ').append(widened);
            } else {
                arguments.append(abi).append(' ').append(value);
            }
        }
        String outcome = LlvmTypeMapper.outcomeType(
            calleeFunction.descriptor().returnType());
        String result = "%call" + op.opId().id();
        emit(result + " = call " + outcome + " @F" + callee.id() + "("
            + arguments + ")" + debugAttachment(op));
        emit("%callerr" + op.opId().id() + " = extractvalue " + outcome + " "
            + result + ", 0");
        String fail = newLabel("callfail");
        String proceed = newLabel("callok");
        emit("br i1 " + emitTemporary(null, "icmp ne i8* %callerr"
            + op.opId().id() + ", null") + ", label %" + fail + ", label %"
            + proceed);
        terminated = true;
        startSegment(fail);
        emitEpilogue();
        terminateFailure("%callerr" + op.opId().id());
        startSegment(proceed);
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
        if (returned instanceof RuntimeDescriptor.Boolean) {
            extracted = emitTemporary(op, "trunc i8 " + extracted
                + " to " + LlvmTypeMapper.BOOLEAN_SSA);
        }
        storeResult(op, type, extracted);
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

    private void emitBranch(SemanticOp op) {
        KindPayload.BranchPayload payload =
            (KindPayload.BranchPayload) op.payload();
        String condition = loadValue(payload.condition(),
            LlvmTypeMapper.BOOLEAN_SSA, op);
        if (payload.selector() == deal.semantic.ir.ControlSelector.LOGICAL_AND
                || payload.selector() == deal.semantic.ir.ControlSelector.LOGICAL_OR) {
            storeResult(op, LlvmTypeMapper.BOOLEAN_SSA, condition);
        }
        String join = newLabel("join");
        String selected = blockLabel(payload.selectedBlock());
        String alternate = payload.alternateBlock() == null
            ? join : blockLabel(payload.alternateBlock());
        terminate("br i1 " + condition + ", label %" + selected + ", label %"
            + alternate);
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
        terminate("br i1 " + condition + ", label %" + body + ", label %"
            + exit);
        loops.push(new LoopTargets(op.opId().id(), header, exit));
        emitBlock(payload.bodyBlock(), header);
        loops.pop();
        startSegment(exit);
    }

    private void emitBreakContinue(SemanticOp op, boolean isBreak) {
        long loopId = op.payload() instanceof KindPayload.BreakPayload breakPayload
            ? breakPayload.loopId().id()
            : ((KindPayload.ContinuePayload) op.payload()).loopId().id();
        for (LoopTargets targets : loops) {
            if (targets.loopId() == loopId) {
                terminate("br label %" + (isBreak
                    ? targets.exit() : targets.header()));
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
        String representation = resultRepresentationOf(result);
        String value = loadValue(payload.value(), representation, op);
        String abi = result instanceof RuntimeDescriptor.Boolean
            ? LlvmTypeMapper.BOOLEAN_ABI : representation;
        if (result instanceof RuntimeDescriptor.Boolean) {
            value = emitTemporary(null, "zext i1 " + value + " to " + abi);
        }
        emitEpilogue();
        String aggregate = "%return" + op.opId().id();
        emit(aggregate + " = insertvalue " + outcome
            + " zeroinitializer, " + abi + " " + value + ", 1");
        terminate("ret " + outcome + " " + aggregate);
    }

    // =========================================================================
    // Values, slots and temporaries
    // =========================================================================

    private static String resultRepresentationOf(RuntimeDescriptor descriptor) {
        return LlvmTypeMapper.representationOf(descriptor).orElseThrow(() ->
            new IllegalStateException("the descriptor "
                + descriptor.canonicalSpecText() + " has no native"
                + " representation"));
    }

    private Optional<String> resultRepresentation(SemanticOp op) {
        if (!(op.result() instanceof ValueId)) {
            return Optional.empty();
        }
        return session.valueRepresentation(unit, (ValueId) op.result());
    }

    private String representationOf(ValueId value) {
        return session.valueRepresentation(unit, value).orElseThrow(() ->
            new IllegalStateException("the value " + value
                + " carries no native representation"));
    }

    private String valueSlot(ValueId value, String type) {
        return valueSlots.computeIfAbsent(value.id(), id -> {
            String name = session.slot(value);
            allocas.add(name + " = alloca " + type);
            return name;
        });
    }

    private String bindingSlot(BindingId binding, long generation, String type) {
        if (session.moduleScoped(unit, binding, generation)) {
            return "@" + session.data.globalSlot(binding, generation, type);
        }
        String key = binding.id() + "_" + generation;
        return bindingSlots.computeIfAbsent(key, ignored -> {
            String name = session.cell(binding, generation);
            allocas.add(name + " = alloca " + type);
            return name;
        });
    }

    private void storeResult(SemanticOp op, String type, String value) {
        if (!(op.result() instanceof ValueId result)) {
            return;
        }
        emit("store " + type + " " + value + ", " + type + "* "
            + valueSlot(result, type));
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

    private String initializedOperand(long moduleIndex) {
        return "%statevalue" + moduleIndex;
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
