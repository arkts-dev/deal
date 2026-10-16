package deal.codegen.js;

import deal.codegen.SharedEmissionFailure;
import deal.codegen.SourceMapGenerator;
import deal.diagnostics.DiagnosticRange;
import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.ChainOperandCompletion;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.ControlSelector;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.ExternalExecutionOwner;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.IndexMode;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.IterationMode;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleImportKind;
import deal.semantic.ir.NullableSide;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The JavaScript semantic-IR renderer foundation: one CommonJS artifact per
 * implementation unit, rendered from the validated executable project and
 * its admitted structural companions (design source
 * {@code wiki:javascript-semantic-ir-emission}).
 *
 * <p>The renderer consumes one canonical {@link JsEmissionInput} and returns
 * one complete {@link JsProjectEmission}: content and coordinate-only
 * mappings, with no filesystem access, lowering, Node execution, transcript
 * or compile-time default/host invocation. Inputs remain immutable and
 * independent calls share no mutable state. A shape this foundation does not
 * realize fails closed with a named {@link SharedEmissionFailure} carrying
 * the offending operation's own origin; no silent output, reroute, retry or
 * timer exists.</p>
 *
 * <p>Realized families: {@code CONST}/{@code UNARY}/{@code BINARY}/
 * {@code STRING_CONCAT}/{@code INTRINSIC_CALL}; {@code ARRAY_NEW}/
 * {@code TABLE_NEW}/{@code ARRAY_LENGTH}/member and index read/write/delete/
 * optional/has; the binding, closure, recursive-group and function-adapter
 * creation ops; the account chains; the direct and indirect calls; the
 * structured control-flow ops; and the module init/import/export/entry ops.
 * The builtin {@code Error} construction is realized because {@code THROW}
 * transport needs the tagged error carrier. Declared-class construction,
 * JSON walkers, field ops, stdlib calls, async operations, host imports and
 * callbacks belong to the later decomposition units of the same migration
 * and fail closed here; no public dispatch selects this renderer until the
 * production cutover.</p>
 */
public final class JsSemanticEmitter {

    private JsSemanticEmitter() {
        // Static entry only; no instances.
    }

    /**
     * Renders the complete project emission of one validated rendering
     * input. The result carries exactly one artifact per implementation
     * unit, in project dependency order; declaration units have no member.
     *
     * @param input the canonical rendering input; non-null
     * @return the complete content and mappings
     * @throws SharedEmissionFailure on an invalid or unsupported shape
     */
    public static JsProjectEmission emitProductionProject(JsEmissionInput input) {
        Objects.requireNonNull(input, "input must not be null");
        return new Session(input).emit();
    }

    /** One rendering session over one immutable project closure. */
    private static final class Session {

        private final JsEmissionInput input;
        private final Map<ModuleId, LoweredModuleUnit> units = new LinkedHashMap<>();
        private final Map<ModuleId, StructuredBodyTable> tables = new LinkedHashMap<>();
        private final Map<ModuleId, ClassFactoryRegistry> registries =
            new LinkedHashMap<>();
        private final Map<ModuleId, JsSourceFile> sourceFiles = new LinkedHashMap<>();
        private final Map<OpId, SemanticOp> opsById = new HashMap<>();
        private final Map<OpId, ModuleId> opModule = new HashMap<>();
        private final Map<OpId, BlockId> opBlock = new HashMap<>();
        private final Map<BlockId, StructuredBodyTable> blockTableOf =
            new HashMap<>();
        private final Map<Long, SemanticOp> producerByValue = new HashMap<>();
        /** Registered intrinsic kind per allocation identity (value id). */
        private final Map<Long, IntrinsicKind> intrinsicKinds = new HashMap<>();
        /** The binding cell holding each lowered function's closure. */
        private final Map<Long, String> closureBindings = new HashMap<>();
        private final Map<Long, LoweredFunction> functions = new HashMap<>();
        /** Parameter bindings of one lowered function, in JS parameter order. */
        private final Map<Long, List<BindingId>> functionParameters =
            new HashMap<>();
        private final Set<OpId> structuralOwned = new HashSet<>();
        private final Set<OpId> ownedChildren = new HashSet<>();
        private final Set<OpId> skippedOps = new HashSet<>();

        private final StringBuilder out = new StringBuilder();
        private int indent;
        private int line = 1;
        private List<SourceMapGenerator.Mapping> mappings = new ArrayList<>();
        private ModuleId currentModule;

        Session(JsEmissionInput input) {
            this.input = input;
            for (Map.Entry<ModuleId, LoweredModuleUnit> entry
                    : input.project().modules().entrySet()) {
                ModuleId module = entry.getKey();
                units.put(module, entry.getValue());
                tables.put(module, input.tables().get(module));
                registries.put(module, input.registries().get(module));
                sourceFiles.put(module, input.sourceFiles().get(module));
                registerUnit(entry.getValue());
            }
            markNonEntryModuleDelegations();
        }

        // =====================================================================
        // Indexing
        // =====================================================================

        private void registerUnit(LoweredModuleUnit unit) {
            StructuredBodyTable table = tables.get(unit.moduleId());
            for (Map.Entry<BlockId, List<OpId>> entry : table.blockOps().entrySet()) {
                blockTableOf.put(entry.getKey(), table);
                for (OpId memberId : entry.getValue()) {
                    opBlock.putIfAbsent(memberId, entry.getKey());
                }
            }
            for (SemanticOp op : unit.ops()) {
                opsById.put(op.opId(), op);
                opModule.put(op.opId(), unit.moduleId());
                if (op.result() instanceof ValueId valueId) {
                    producerByValue.putIfAbsent(valueId.id(), op);
                }
            }
            for (Map.Entry<FunctionAllocationIdentity, FunctionExecutionBinding>
                    entry : unit.functionBindings().entrySet()) {
                if (entry.getValue()
                        instanceof FunctionExecutionBinding.IntrinsicFunction
                            intrinsic) {
                    intrinsicKinds.put(entry.getKey().id(), intrinsic.kind());
                }
            }
            for (LoweredFunction function : unit.functions().values()) {
                functions.put(function.functionId().id(), function);
            }
            for (SemanticOp op : unit.ops()) {
                if (op.kind() == SemanticOpKind.CLOSURE_NEW
                        && op.result() instanceof ValueId valueId) {
                    KindPayload.ClosureNewPayload payload =
                        (KindPayload.ClosureNewPayload) op.payload();
                    closureBindings.putIfAbsent(payload.function().id(),
                        slot(valueId));
                }
            }
            // The publication pairs: a binding initialized from a closure
            // value gains that closure's binding-cell spelling, so a direct
            // call to a static body resolves the created function value.
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.BINDING_INIT) {
                    continue;
                }
                KindPayload.BindingInitPayload payload =
                    (KindPayload.BindingInitPayload) op.payload();
                SemanticOp producer = producerByValue.get(payload.value().id());
                if (producer != null
                        && producer.kind() == SemanticOpKind.CLOSURE_NEW) {
                    KindPayload.ClosureNewPayload closure =
                        (KindPayload.ClosureNewPayload) producer.payload();
                    closureBindings.put(closure.function().id(),
                        cell(payload.binding(), payload.generation()));
                }
            }
            // Parameter bindings per function: the unit's recorded parameter
            // bindings whose allocation scope is the function's body block,
            // in allocation (declaration) order.
            for (LoweredFunction function : unit.functions().values()) {
                List<BindingId> parameters = new ArrayList<>();
                for (SemanticOp op : unit.ops()) {
                    if (op.kind() == SemanticOpKind.BINDING_ALLOC
                            && op.payload() instanceof KindPayload
                                .BindingAllocPayload payload
                            && unit.classBindingFacts().parameters()
                                .contains(payload.binding())
                            && payload.scope().equals(function.body())) {
                        parameters.add(payload.binding());
                    }
                }
                functionParameters.put(function.functionId().id(),
                    List.copyOf(parameters));
            }
            Set<OpId> structural = ChainOperandCompletion.structuralOwners(unit);
            structuralOwned.addAll(structural);
            ownedChildren.addAll(structural);
            ChainOperandCompletion.registerChainOperandOwners(unit, structural,
                ownedChildren);
        }

        /**
         * Marks the ENTRY_INVOKE delegation of a non-entry module as skipped
         * (the entry op and every op parented to it): one artifact per
         * implementation unit, and only the entry module's artifact runs the
         * direct-run guard.
         */
        private void markNonEntryModuleDelegations() {
            for (Map.Entry<ModuleId, LoweredModuleUnit> entry : units.entrySet()) {
                if (entry.getKey().equals(input.project().entryModule())) {
                    continue;
                }
                for (SemanticOp op : entry.getValue().ops()) {
                    if (op.kind() != SemanticOpKind.ENTRY_INVOKE) {
                        continue;
                    }
                    skippedOps.add(op.opId());
                    for (SemanticOp candidate : entry.getValue().ops()) {
                        if (op.opId().equals(candidate.origin().parentOpId())) {
                            skippedOps.add(candidate.opId());
                        }
                    }
                }
            }
        }

        // =====================================================================
        // Project emission
        // =====================================================================

        JsProjectEmission emit() {
            List<JsModuleArtifact> modules = new ArrayList<>();
            for (ModuleId module : units.keySet()) {
                modules.add(emitModule(module));
            }
            return new JsProjectEmission(
                artifactRelativePath(input.project().entryModule()), modules);
        }

        private JsModuleArtifact emitModule(ModuleId module) {
            LoweredModuleUnit unit = units.get(module);
            currentModule = module;
            out.setLength(0);
            line = 1;
            indent = 0;
            mappings = input.sourceMap() ? new ArrayList<>() : null;

            emit("// Generated by DEAL compiler — JavaScript semantic-IR "
                + "renderer. DO NOT EDIT.");
            emit("// Source: " + sourceFiles.get(module).displayName());
            emit("\"use strict\";");
            emit("const $require = require; const $module = module; "
                + "const $exports = exports;");
            emit("const $rt = $require(" + jsString(runtimeSpecifier(module))
                + ");");
            emit("");
            emit("// The module's private implementation record is registered"
                + " before any");
            emit("// implementation dependency is required; a failed"
                + " initialization stays sticky");
            emit("// and never publishes a partial surface.");
            emit("$rt.initModule(" + jsString(module.path()) + ", () => {");
            indent++;
            emitFunctionBody(unit, unit.moduleInit().initBlock());
            indent--;
            emit("});");
            emit("$module.exports = $rt.moduleSurface(" + jsString(module.path())
                + ");");

            return new JsModuleArtifact(module, artifactRelativePath(module),
                out.toString(),
                mappings == null ? null : List.copyOf(mappings));
        }

        /**
         * Emits one function scope's body: the predeclared value slots, then
         * the block's ops. A function scope is the module-init body or one
         * lowered closure body; nested closure bodies own their own slots.
         */
        private void emitFunctionBody(LoweredModuleUnit unit, BlockId block) {
            Set<String> slots = new LinkedHashSet<>();
            collectSlots(unit, block, slots);
            if (!slots.isEmpty()) {
                StringBuilder declaration = new StringBuilder();
                for (String slotName : slots) {
                    if (declaration.length() > 0) {
                        declaration.append(", ");
                    }
                    declaration.append(slotName);
                }
                emit("let " + declaration + ";");
            }
            declareBlockBindings(unit, block);
            emitOps(unit, block);
        }

        /** Collects every value slot an emitted scope needs (structures included). */
        private void collectSlots(LoweredModuleUnit unit, BlockId block,
                                  Set<String> slots) {
            List<OpId> members = memberOps(unit, block);
            if (members == null) {
                return;
            }
            for (OpId opId : members) {
                SemanticOp op = opsById.get(opId);
                if (op == null) {
                    continue;
                }
                if (op.result() instanceof ValueId valueId) {
                    slots.add(slot(valueId));
                }
                if (op.payload() instanceof KindPayload.IndexNormalizePayload) {
                    slots.add(lengthSlot(op.opId()));
                }
                switch (op.payload()) {
                    case KindPayload.BranchPayload payload -> {
                        collectSlots(unit, payload.selectedBlock(), slots);
                        if (payload.alternateBlock() != null) {
                            collectSlots(unit, payload.alternateBlock(), slots);
                        }
                    }
                    case KindPayload.LoopPayload payload -> {
                        if (payload.initBlock() != null) {
                            collectSlots(unit, payload.initBlock(), slots);
                        }
                        collectSlots(unit, payload.bodyBlock(), slots);
                        if (payload.updateBlock() != null) {
                            collectSlots(unit, payload.updateBlock(), slots);
                        }
                    }
                    case KindPayload.ForEachPayload payload ->
                        collectSlots(unit, payload.body(), slots);
                    case KindPayload.TryCatchPayload payload -> {
                        collectSlots(unit, payload.tryBlock(), slots);
                        collectSlots(unit, payload.catchBlock(), slots);
                    }
                    default -> {
                    }
                }
            }
        }

        /** The member op list of one block in its owning unit's table. */
        private List<OpId> memberOps(LoweredModuleUnit unit, BlockId block) {
            StructuredBodyTable table = blockTableOf.get(block);
            if (table == null) {
                return null;
            }
            return table.blockOps().get(block);
        }

        /** Emits one block's ops (no braces; the caller owns the scope). */
        private void emitOps(LoweredModuleUnit unit, BlockId block) {
            List<OpId> members = memberOps(unit, block);
            if (members == null) {
                throw new IllegalStateException("block " + block
                    + " has no membership row in its owning unit's table"
                    + " (a malformed table; the production validator rejects"
                    + " this)");
            }
            for (OpId opId : members) {
                if (ownedChildren.contains(opId) || skippedOps.contains(opId)) {
                    continue;
                }
                SemanticOp op = opsById.get(opId);
                if (op == null) {
                    throw new IllegalStateException("block " + block
                        + " names the unknown op " + opId);
                }
                emitOp(unit, op);
            }
        }

        /** Emits a braced child block with its own binding-cell declarations. */
        private void emitScopedBlock(LoweredModuleUnit unit, BlockId block) {
            emit("{");
            indent++;
            emitBlockContent(unit, block);
            indent--;
            emit("}");
        }

        /** Emits one block's declarations and ops (the caller owns the braces). */
        private void emitBlockContent(LoweredModuleUnit unit, BlockId block) {
            declareBlockBindings(unit, block);
            emitOps(unit, block);
        }

        /**
         * Declares the binding cells allocated in one block (the IR's
         * recorded cell scope): a fresh {@code let} per incarnation, so a
         * closure created inside a repeated block captures that execution's
         * own cell. Recursive-group members and import alias cells are
         * declared here too — the group's publication and the import's
         * completion write exactly these cells.
         */
        private void declareBlockBindings(LoweredModuleUnit unit, BlockId block) {
            Set<String> declared = new LinkedHashSet<>();
            for (SemanticOp op : unit.ops()) {
                if (op.kind() == SemanticOpKind.BINDING_ALLOC
                        && op.payload() instanceof KindPayload.BindingAllocPayload
                            alloc
                        && alloc.scope().equals(block)) {
                    if (unit.classBindingFacts().parameters()
                            .contains(alloc.binding())
                            || isCatchBinding(alloc.binding())) {
                        // A parameter binding is the closure's own JS
                        // parameter; the catch binding is the catch clause's
                        // own binding.
                        continue;
                    }
                    declared.add(cell(alloc.binding(), alloc.generation()));
                }
                if (op.kind() == SemanticOpKind.RECURSIVE_GROUP_INIT
                        && op.payload() instanceof KindPayload
                            .RecursiveGroupInitPayload group
                        && block.equals(opBlock.get(op.opId()))) {
                    for (BindingId binding : group.bindings()) {
                        declared.add(cell(binding, 0));
                    }
                }
                if (op.kind() == SemanticOpKind.MODULE_IMPORT
                        && op.payload() instanceof KindPayload
                            .ModuleImportPayload importPayload
                        && block.equals(opBlock.get(op.opId()))) {
                    for (BindingId alias : importPayload.aliasCells()) {
                        declared.add(cell(alias, 0));
                    }
                }
            }
            if (!declared.isEmpty()) {
                StringBuilder declaration = new StringBuilder();
                for (String name : declared) {
                    if (declaration.length() > 0) {
                        declaration.append(", ");
                    }
                    declaration.append(name);
                }
                emit("let " + declaration + ";");
            }
        }

        /** Whether one binding is some TRY_CATCH op's catch binding. */
        private boolean isCatchBinding(BindingId binding) {
            for (LoweredModuleUnit unit : units.values()) {
                for (SemanticOp op : unit.ops()) {
                    if (op.payload() instanceof KindPayload.TryCatchPayload
                            tryCatch
                            && tryCatch.catchBinding().equals(binding)) {
                        return true;
                    }
                }
            }
            return false;
        }

        // =====================================================================
        // Op dispatch
        // =====================================================================

        private void emitOp(LoweredModuleUnit unit, SemanticOp op) {
            beginMappedGroup(op);
            switch (op.kind()) {
                case CONST -> emitConst(op);
                case UNARY -> emitUnary(op);
                case BINARY -> emitBinary(op);
                case STRING_CONCAT -> emitConcat(op);
                case INTRINSIC_CALL -> emitIntrinsicCall(op);
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
                case RECURSIVE_GROUP_INIT -> emitRecursiveGroupInit(op);
                case CLOSURE_NEW -> emitClosureNew(op);
                case FUNCTION_ADAPT -> emitFunctionAdapt(op);
                case ASSIGN -> emitAssign(op);
                case DELETE -> emitDelete(op);
                case CALL -> emitCall(op);
                case BRANCH -> emitBranch(op);
                case LOOP -> emitLoop(op);
                case FOR_EACH -> emitForEach(op);
                case TRY_CATCH -> emitTryCatch(op);
                case THROW -> emitThrow(op);
                case RETURN -> emitReturn(op);
                case BREAK -> emitBreakContinue(op, true);
                case CONTINUE -> emitBreakContinue(op, false);
                case DISCARD -> emitDiscard(op);
                case MODULE_IMPORT -> emitModuleImport(op);
                case EXPORT_READ -> emitExportRead(op);
                case EXPORT_PUBLISH -> emitExportPublish(op);
                case ENTRY_INVOKE -> emitEntryInvoke(unit, op);
                case MODULE_INIT -> emit("// The artifact's registered record"
                    + " and init body carry this record.");
                case EXTERNAL_ENTRY -> emit("// EXTERNAL_ENTRY "
                    + op.opId().id() + ": the cross-module entry record"
                    + " executes under the later call-realization unit.");
                case CLASS_NEW -> emitBuiltinErrorConstruction(op);
                case STDLIB_CALL, ASYNC_START, AWAIT, CLASS_DEFAULT,
                     CLASS_FACTORY, FIELD_READ, FIELD_WRITE, FIELD_DELETE,
                     JSON_FROM_CLASS, JSON_TO_CLASS, CALLBACK_INVOKE ->
                    throw unsupported(op, "the op kind " + op.kind()
                        + " belongs to a realized later decomposition unit of"
                        + " the JavaScript semantic-IR renderer; the"
                        + " foundation fails closed instead of emitting a"
                        + " partial result");
            }
        }

        // =====================================================================
        // Values
        // =====================================================================

        private void emitConst(SemanticOp op) {
            KindPayload.ConstPayload payload =
                (KindPayload.ConstPayload) op.payload();
            String literal = switch (payload.value()) {
                case ScalarValue.Null ignored -> "null";
                case ScalarValue.Boolean bool -> bool.value() ? "true" : "false";
                case ScalarValue.Int intValue -> Integer.toString(intValue.value());
                case ScalarValue.Number number -> numberLiteral(number.value());
                case ScalarValue.String string -> jsString(string.value());
            };
            emit(slotOf(op) + " = " + literal + ";");
        }

        private void emitUnary(SemanticOp op) {
            KindPayload.UnaryPayload payload =
                (KindPayload.UnaryPayload) op.payload();
            String operand = slot(op.operands().get(0));
            String expression = switch (payload.selector()) {
                case BOOL_NOT -> "!" + operand;
                case INT32_NEG -> "$rt.intNeg(" + operand + ", " + spanArgs(op)
                    + ")";
                case NUMBER_NEG -> "-" + operand;
            };
            emit(slotOf(op) + " = " + expression + ";");
        }

        private void emitBinary(SemanticOp op) {
            KindPayload.BinaryPayload payload =
                (KindPayload.BinaryPayload) op.payload();
            String left = slot(op.operands().get(0));
            String right = slot(op.operands().get(1));
            String expression = switch (payload.selector()) {
                case INT32_ADD -> "$rt.intAdd(" + left + ", " + right + ", "
                    + spanArgs(op) + ")";
                case INT32_SUB -> "$rt.intSub(" + left + ", " + right + ", "
                    + spanArgs(op) + ")";
                case INT32_MUL -> "$rt.intMul(" + left + ", " + right + ", "
                    + spanArgs(op) + ")";
                case INT32_DIV_TRUNC -> "$rt.intDiv(" + left + ", " + right
                    + ", " + spanArgs(op) + ")";
                case INT32_MOD_TRUNC -> "$rt.intMod(" + left + ", " + right
                    + ", " + spanArgs(op) + ")";
                case NUMBER_ADD -> "(" + left + " + " + right + ")";
                case NUMBER_SUB -> "(" + left + " - " + right + ")";
                case NUMBER_MUL -> "(" + left + " * " + right + ")";
                case NUMBER_DIV_IEEE -> "(" + left + " / " + right + ")";
                case INT32_EQ, NUMBER_EQ, BOOLEAN_EQ, REFERENCE_EQ, STRING_EQ,
                     BYTES_EQ -> "(" + left + " === " + right + ")";
                case INT32_NE, NUMBER_NE, BOOLEAN_NE, REFERENCE_NE, STRING_NE,
                     BYTES_NE -> "(" + left + " !== " + right + ")";
                case INT32_LT, NUMBER_LT -> "(" + left + " < " + right + ")";
                case INT32_LE, NUMBER_LE -> "(" + left + " <= " + right + ")";
                case INT32_GT, NUMBER_GT -> "(" + left + " > " + right + ")";
                case INT32_GE, NUMBER_GE -> "(" + left + " >= " + right + ")";
                case STRING_LT -> "($rt.strCompare(" + left + ", " + right
                    + ") < 0)";
                case STRING_LE -> "($rt.strCompare(" + left + ", " + right
                    + ") <= 0)";
                case STRING_GT -> "($rt.strCompare(" + left + ", " + right
                    + ") > 0)";
                case STRING_GE -> "($rt.strCompare(" + left + ", " + right
                    + ") >= 0)";
                case NULL_EQ -> "(" + left + " === null)";
                case NULL_NE -> "(" + left + " !== null)";
                // Nullable-vs-nullable equality: nil-equivalent pairs
                // compare equal (the shared ComparisonExecutor rule and
                // the reference nil-aware form).
                case NULLABLE_EQ -> "((" + left + " === " + right
                    + ") || ($rt.isNilEquivalent(" + left
                    + ") && $rt.isNilEquivalent(" + right + ")))";
                case NULLABLE_NE -> "!((" + left + " === " + right
                    + ") || ($rt.isNilEquivalent(" + left
                    + ") && $rt.isNilEquivalent(" + right + ")))";
                case NULLABLE_NULL_EQ -> "$rt.isNilEquivalent("
                    + nullableSideExpr(payload.side(), left, right) + ")";
                case NULLABLE_NULL_NE -> "!$rt.isNilEquivalent("
                    + nullableSideExpr(payload.side(), left, right) + ")";
            };
            emit(slotOf(op) + " = " + expression + ";");
        }

        private static String nullableSideExpr(NullableSide side, String left,
                                               String right) {
            if (side == NullableSide.LEFT) {
                return left;
            }
            if (side == NullableSide.RIGHT) {
                return right;
            }
            throw new IllegalStateException("a nullable-null comparison"
                + " carries side LEFT or RIGHT, got " + side
                + " (producer defect)");
        }

        private void emitConcat(SemanticOp op) {
            KindPayload.StringConcatPayload payload =
                (KindPayload.StringConcatPayload) op.payload();
            StringBuilder expression = new StringBuilder();
            for (ValueId fragment : payload.fragments()) {
                if (expression.length() > 0) {
                    expression.append(" + ");
                }
                expression.append(slot(fragment));
            }
            emit(slotOf(op) + " = "
                + (expression.length() == 0 ? "\"\"" : expression) + ";");
        }

        private void emitIntrinsicCall(SemanticOp op) {
            KindPayload.IntrinsicCallPayload payload =
                (KindPayload.IntrinsicCallPayload) op.payload();
            emit(slotOf(op) + " = " + intrinsicInvocation(payload.kind(),
                "(" + slot(payload.input()) + ")", spanArgs(op)) + ";");
        }

        /** The one conversion ladder of an intrinsic invocation. */
        private static String intrinsicInvocation(IntrinsicKind kind,
                                                  String argument,
                                                  String location) {
            return switch (kind) {
                case INT_CONVERT -> "$rt.intConvert(" + argument + ", "
                    + location + ")";
                case NUMBER_CONVERT -> "$rt.numberConvert(" + argument + ", "
                    + location + ")";
                case BYTES_NEW -> "$rt.bytes(" + argument + ", " + location
                    + ")";
            };
        }

        // =====================================================================
        // Containers
        // =====================================================================

        private void emitArrayNew(SemanticOp op) {
            KindPayload.ArrayNewPayload payload =
                (KindPayload.ArrayNewPayload) op.payload();
            for (int i = 0; i < payload.values().size(); i++) {
                ValueId value = payload.values().get(i);
                SemanticOp boundary = opsById.get(
                    payload.elementBoundaryOpIds().get(i));
                emitBoundaryCheck(boundary, slot(value),
                    payload.elementDescriptor(),
                    payload.elementBoundaryOpIds().get(i));
            }
            StringBuilder expression = new StringBuilder("[");
            for (int i = 0; i < payload.values().size(); i++) {
                if (i > 0) {
                    expression.append(", ");
                }
                expression.append(slot(payload.values().get(i)));
            }
            expression.append("]");
            emit(slotOf(op) + " = " + expression + ";");
        }

        private void emitTableNew(SemanticOp op) {
            KindPayload.TableNewPayload payload =
                (KindPayload.TableNewPayload) op.payload();
            StringBuilder expression = new StringBuilder("$rt.makeTable({");
            for (int i = 0; i < payload.entries().size(); i++) {
                KindPayload.TableEntry entry = payload.entries().get(i);
                if (i > 0) {
                    expression.append(", ");
                }
                expression.append("[").append(jsString(entry.key()))
                    .append("]: ").append(slot(entry.value()));
            }
            expression.append("})");
            emit(slotOf(op) + " = " + expression + ";");
        }

        private void emitArrayLength(SemanticOp op) {
            KindPayload.ArrayLengthPayload payload =
                (KindPayload.ArrayLengthPayload) op.payload();
            String array = slot(payload.arrayValue());
            String expression = isBytesValue(payload.arrayValue())
                ? "$rt.bytesLength(" + array + ", " + spanArgs(op) + ")"
                : array + ".length";
            emit(slotOf(op) + " = " + expression + ";");
        }

        /** Whether one value's producing op publishes a bytes value. */
        private boolean isBytesValue(ValueId value) {
            SemanticOp producer = producerByValue.get(value.id());
            return producer != null
                && producer.resultType() instanceof RuntimeDescriptor.Bytes;
        }

        private void emitMemberRead(SemanticOp op) {
            KindPayload.MemberReadPayload payload =
                (KindPayload.MemberReadPayload) op.payload();
            String target = slotOf(op);
            emit(target + " = " + slot(payload.table()) + ".get("
                + jsString(payload.key()) + ");");
            SemanticOp boundary = boundaryChildOf(op);
            if (boundary != null) {
                emitBoundaryCheck(boundary, target,
                    descriptorOf(boundary));
            }
        }

        private void emitMemberWrite(SemanticOp op) {
            KindPayload.MemberWritePayload payload =
                (KindPayload.MemberWritePayload) op.payload();
            emit(slot(payload.table()) + ".set(" + jsString(payload.key())
                + ", " + slot(payload.value()) + ");");
        }

        private void emitMemberDelete(SemanticOp op) {
            KindPayload.MemberDeletePayload payload =
                (KindPayload.MemberDeletePayload) op.payload();
            emit(slot(payload.table()) + ".delete(" + jsString(payload.key())
                + ");");
        }

        private void emitNormalize(SemanticOp op) {
            KindPayload.IndexNormalizePayload payload =
                (KindPayload.IndexNormalizePayload) op.payload();
            emit(slotOf(op) + " = " + slot(payload.rawKey()) + ";");
            emit(lengthSlot(op.opId()) + " = "
                + slot(payload.currentLength()) + ";");
        }

        private void emitIndexRead(SemanticOp op) {
            KindPayload.IndexReadPayload payload =
                (KindPayload.IndexReadPayload) op.payload();
            SemanticOp boundary = opsById.get(payload.elementBoundaryOpId());
            String container = slot(payload.container());
            String index = slot(payload.slot());
            String target = slotOf(op);
            IndexMode mode = normalizeMode(payload.slot());
            if (mode == IndexMode.BYTES_READ) {
                emit(target + " = $rt.bytesGet(" + container + ", " + index
                    + ", " + spanArgs(op) + ");");
            } else if (mode == IndexMode.TABLE_READ) {
                emit(target + " = " + container + ".get(" + index + ");");
            } else {
                String length = normalizeLengthSlot(payload.slot());
                emit("if (" + index + " < 0 || " + index + " >= " + length
                    + ") { $rt.fail(\"E8002\", \"array index out of bounds\","
                    + " " + spanArgs(boundary) + "); }");
                emit(target + " = " + container + "[" + index + "];");
            }
            emitBoundaryCheck(boundary, target, descriptorOf(boundary),
                payload.elementBoundaryOpId());
        }

        private void emitIndexWrite(SemanticOp op) {
            KindPayload.IndexWritePayload payload =
                (KindPayload.IndexWritePayload) op.payload();
            String container = slot(payload.container());
            String index = slot(payload.slot());
            String value = slot(payload.value());
            IndexMode mode = normalizeMode(payload.slot());
            if (mode == IndexMode.BYTES_WRITE) {
                emit("$rt.bytesSet(" + container + ", " + index + ", " + value
                    + ", " + spanArgs(op) + ");");
            } else if (mode == IndexMode.TABLE_WRITE) {
                emit(container + ".set(" + index + ", " + value + ");");
            } else {
                emit(container + "[" + index + "] = " + value + ";");
            }
        }

        private void emitIndexDelete(SemanticOp op) {
            KindPayload.IndexDeletePayload payload =
                (KindPayload.IndexDeletePayload) op.payload();
            String container = slot(payload.container());
            String index = slot(payload.slot());
            if (normalizeMode(payload.slot()) == IndexMode.TABLE_WRITE) {
                emit(container + ".delete(" + index + ");");
            } else {
                String length = normalizeLengthSlot(payload.slot());
                emit("if (" + index + " < " + length + ") { " + container + "["
                    + index + "] = $rt.undefined; }");
            }
        }

        /**
         * The recorded normalize mode of one index slot: the closed
         * {@code ARRAY_*}/{@code BYTES_*}/{@code TABLE_*} selector of the
         * slot's INDEX_NORMALIZE producer — the recorded fact that selects
         * the carrier's read/write/delete arm (never a value shape or a
         * generated name).
         */
        private IndexMode normalizeMode(ValueId slotValue) {
            SemanticOp producer = producerByValue.get(slotValue.id());
            if (producer == null
                    || !(producer.payload() instanceof KindPayload
                        .IndexNormalizePayload payload)) {
                throw new IllegalStateException("the index slot " + slotValue
                    + " has no INDEX_NORMALIZE producer (producer defect)");
            }
            return payload.mode();
        }

        /** The recorded length slot paired with one normalized index slot. */
        private String normalizeLengthSlot(ValueId slotValue) {
            SemanticOp producer = producerByValue.get(slotValue.id());
            if (producer == null
                    || !(producer.payload() instanceof KindPayload
                        .IndexNormalizePayload)) {
                throw new IllegalStateException("the index slot " + slotValue
                    + " has no INDEX_NORMALIZE producer (producer defect)");
            }
            return lengthSlot(producer.opId());
        }

        private void emitOptionalRead(SemanticOp op) {
            KindPayload.OptionalReadPayload payload =
                (KindPayload.OptionalReadPayload) op.payload();
            String target = slotOf(op);
            if (payload.value() == null) {
                emit(target + " = null;");
            } else {
                emit(target + " = $rt.optRead(" + slot(payload.value()) + ");");
            }
            SemanticOp boundary = boundaryChildOf(op);
            if (boundary != null) {
                emitBoundaryCheck(boundary, target, descriptorOf(boundary));
            }
        }

        private void emitHasField(SemanticOp op) {
            KindPayload.HasFieldPayload payload =
                (KindPayload.HasFieldPayload) op.payload();
            emit(slotOf(op) + " = $rt.has(" + slot(payload.receiver()) + ", "
                + jsString(payload.key()) + ");");
        }

        // =====================================================================
        // Boundaries
        // =====================================================================

        private void emitFreeBoundary(SemanticOp op) {
            KindPayload.BoundaryPayload payload =
                (KindPayload.BoundaryPayload) op.payload();
            emitBoundaryCheck(op, slot(payload.input()), payload.descriptor());
        }

        /**
         * The admission of one committed boundary cell: the recorded
         * descriptor check at the boundary's own origin, replacing the input
         * slot with the admitted value (the int cell's {@code -0}
         * normalization included).
         */
        private void emitBoundaryCheck(SemanticOp boundary, String inputSlot,
                                       RuntimeDescriptor descriptor) {
            emitBoundaryCheck(boundary, inputSlot, descriptor, null);
        }

        private void emitBoundaryCheck(SemanticOp boundary, String inputSlot,
                                       RuntimeDescriptor descriptor,
                                       OpId ownedByElement) {
            if (boundary == null) {
                throw new IllegalStateException("an element or field boundary"
                    + " child is missing (producer defect)");
            }
            switch (((KindPayload.BoundaryPayload) boundary.payload()).kind()) {
                case ARRAY_ELEMENT_READ, ARRAY_ELEMENT_ASSIGNMENT,
                     ARRAY_ELEMENT_DELETE, ARRAY_LITERAL_ELEMENT,
                     BYTE_ELEMENT_READ, BYTE_ELEMENT_ASSIGNMENT -> {
                    if (ownedByElement == null
                            || !ownedByElement.equals(boundary.opId())) {
                        throw unsupported(boundary, "the boundary kind "
                            + ((KindPayload.BoundaryPayload) boundary.payload())
                                .kind()
                            + " executes only under its owning read, write or"
                            + " literal arm (producer defect)");
                    }
                }
                default -> {
                }
            }
            emit(inputSlot + " = $rt.checkType("
                + jsString(descriptor.canonicalSpecText()) + ", " + inputSlot
                + ", " + spanArgs(boundary) + ");");
        }

        private RuntimeDescriptor descriptorOf(SemanticOp boundary) {
            return ((KindPayload.BoundaryPayload) boundary.payload()).descriptor();
        }

        // =====================================================================
        // Bindings and functions
        // =====================================================================

        private void emitBindingAlloc(SemanticOp op) {
            // The cell was declared at its scope block's top (or is the
            // closure's JS parameter); the allocation itself is the cell's
            // fresh definition.
            KindPayload.BindingAllocPayload payload =
                (KindPayload.BindingAllocPayload) op.payload();
            if (parametersOfOwningUnit(op).contains(payload.binding())
                    || isCatchBinding(payload.binding())) {
                // A parameter binding is the closure's JS parameter and the
                // catch binding is its catch clause's own binding; neither
                // allocation resets a live cell.
                return;
            }
            emit(cell(payload.binding(), payload.generation())
                + " = $rt.undefined;");
        }

        private Set<BindingId> parametersOfOwningUnit(SemanticOp op) {
            return units.get(opModule.get(op.opId())).classBindingFacts()
                .parameters();
        }

        private void emitBindingInit(SemanticOp op) {
            KindPayload.BindingInitPayload payload =
                (KindPayload.BindingInitPayload) op.payload();
            emit(cellSource(payload.binding(), payload.generation()) + " = "
                + bindingValueExpr(payload.value()) + ";");
        }

        /**
         * The carrier expression of one binding-initialized value: a produced
         * value's slot, or the seeded intrinsic wrapper of a producer-less
         * seeded identity ({@code int}/{@code number}/{@code bytes}).
         */
        private String bindingValueExpr(ValueId valueId) {
            if (producerByValue.containsKey(valueId.id())) {
                return slot(valueId);
            }
            IntrinsicKind kind = intrinsicKinds.get(valueId.id());
            if (kind == null) {
                throw new IllegalStateException("the producer-less value "
                    + valueId + " names no registered intrinsic identity (a"
                    + " producer defect)");
            }
            return intrinsicWrapper(kind);
        }

        private static String intrinsicWrapper(IntrinsicKind kind) {
            return switch (kind) {
                case INT_CONVERT -> "$rt.function(\"(number)->int\", (v, $f, $l,"
                    + " $c) => $rt.intConvert(v, $f, $l, $c))";
                case NUMBER_CONVERT -> "$rt.function(\"(int)->number\", (v, $f,"
                    + " $l, $c) => $rt.numberConvert(v, $f, $l, $c))";
                case BYTES_NEW -> "$rt.function(\"(int)->bytes\", (v, $f, $l,"
                    + " $c) => $rt.bytes(v, $f, $l, $c))";
            };
        }

        private void emitBindingLoad(SemanticOp op) {
            KindPayload.BindingLoadPayload payload =
                (KindPayload.BindingLoadPayload) op.payload();
            emit(slotOf(op) + " = "
                + cellSource(payload.binding(), payload.generation()) + ";");
        }

        private void emitBindingStore(SemanticOp op) {
            KindPayload.BindingStorePayload payload =
                (KindPayload.BindingStorePayload) op.payload();
            emit(cellSource(payload.binding(), payload.generation()) + " = "
                + slot(payload.value()) + ";");
        }

        private void emitClosureNew(SemanticOp op) {
            KindPayload.ClosureNewPayload payload =
                (KindPayload.ClosureNewPayload) op.payload();
            emitClosureExpression(slotOf(op) + " = ", payload.function(), ");");
        }

        /**
         * One lowered function as a fresh DEAL function value: the
         * {@code $kind/$sig/$f} wrapper over a native closure. The body is
         * emitted inline, so captures are the surrounding lexical bindings
         * (live) and a repeated creation site produces a fresh closure per
         * execution — the recorded creation-site incarnation.
         */
        private void emitClosureExpression(String prefix,
                                           FunctionId functionId,
                                           String suffix) {
            LoweredFunction function = functions.get(functionId.id());
            if (function == null) {
                throw new IllegalStateException("the closure function "
                    + functionId + " has no LoweredFunction record (producer"
                    + " defect)");
            }
            LoweredModuleUnit owner = ownerOfFunction(functionId);
            List<BindingId> parameters = functionParameters.getOrDefault(
                functionId.id(), List.of());
            StringBuilder params = new StringBuilder();
            for (int i = 0; i < parameters.size(); i++) {
                if (i > 0) {
                    params.append(", ");
                }
                params.append(cell(parameters.get(i), 0));
            }
            emit(prefix + "$rt.function("
                + jsString(function.descriptor().canonicalSpecText()) + ", ("
                + params + ") => {");
            indent++;
            emitFunctionBody(owner, function.body());
            indent--;
            emit("}" + suffix);
        }

        private void emitRecursiveGroupInit(SemanticOp op) {
            KindPayload.RecursiveGroupInitPayload payload =
                (KindPayload.RecursiveGroupInitPayload) op.payload();
            // Phase 1: every member identity is created before the first
            // publication; no member body runs at group execution.
            List<String> temporaries = new ArrayList<>();
            for (int i = 0; i < payload.functions().size(); i++) {
                String temporary = "$t" + op.opId().id() + "_" + i;
                emitClosureExpression("const " + temporary + " = ",
                    payload.functions().get(i), ");");
                temporaries.add(temporary);
            }
            // Phase 2: the group's cells publish the identities in
            // declaration order.
            for (int i = 0; i < payload.bindings().size(); i++) {
                emit(cell(payload.bindings().get(i), 0) + " = "
                    + temporaries.get(i) + ";");
            }
        }

        private void emitFunctionAdapt(SemanticOp op) {
            KindPayload.FunctionAdaptPayload payload =
                (KindPayload.FunctionAdaptPayload) op.payload();
            String sourceExpr;
            switch (payload.source()) {
                case AdaptSourceRef.Value value -> {
                    // The already-materialized operand is retained for the
                    // adapter's lifetime.
                    String local = "$av" + op.opId().id();
                    emit("const " + local + " = " + adaptValueExpr(value) + ";");
                    sourceExpr = local;
                }
                case AdaptSourceRef.SharedCell cell ->
                    // The generation cell is read per invocation, so a later
                    // reassignment retargets the adapter.
                    sourceExpr = cellSource(cell.binding(), cell.generation());
                case AdaptSourceRef.Thunk thunk ->
                    throw unsupported(op, "the REEVALUATE_THUNK adaptation"
                        + " source of " + op.opId() + " belongs to the"
                        + " adapter-realization unit");
            }
            int arity = payload.sourceSignature().paramTypes().size();
            emit(slotOf(op) + " = $rt.function("
                + jsString(payload.targetSignature().canonicalSpecText())
                + ", (...$a) => {");
            indent++;
            emit("const $src = " + sourceExpr + ";");
            emit("$rt.checkFunctionSig("
                + jsString(payload.sourceSignature().canonicalSpecText())
                + ", $src.$sig, $src, " + spanArgs(op) + ");");
            emit("return $src.$f(...$a.slice(0, " + arity + "));");
            indent--;
            emit("});");
        }

        private String adaptValueExpr(AdaptSourceRef.Value value) {
            if (producerByValue.containsKey(value.value().id())) {
                return slot(value.value());
            }
            IntrinsicKind kind = intrinsicKinds.get(value.value().id());
            if (kind != null) {
                return intrinsicWrapper(kind);
            }
            throw new IllegalStateException("the adaptation source value "
                + value.value() + " has no producer and no registered"
                + " intrinsic identity (producer defect)");
        }

        // =====================================================================
        // Chains
        // =====================================================================

        private void emitAssign(SemanticOp op) {
            KindPayload.AssignPayload payload =
                (KindPayload.AssignPayload) op.payload();
            emitChainChildren(op, payload.childOps());
        }

        private void emitDelete(SemanticOp op) {
            KindPayload.DeletePayload payload =
                (KindPayload.DeletePayload) op.payload();
            emitChainChildren(op, payload.childOps());
        }

        /**
         * One address chain: each child's transitive operand closure completes
         * at the child's own position, boundary children run their recorded
         * bounds/descriptor cells, and the commit child performs the single
         * write or delete.
         */
        private void emitChainChildren(SemanticOp chain, List<OpId> childIds) {
            LoweredModuleUnit unit = units.get(opModule.get(chain.opId()));
            for (OpId childId : childIds) {
                SemanticOp child = opsById.get(childId);
                if (child == null) {
                    throw new IllegalStateException("the chain " + chain.opId()
                        + " names the unknown child " + childId);
                }
                for (SemanticOp producer
                        : ChainOperandCompletion.operandProducersOf(child, unit,
                            structuralOwned)) {
                    emitOp(unit, producer);
                }
                if (child.kind() == SemanticOpKind.BOUNDARY) {
                    emitChainBoundary(chain, child);
                } else {
                    emitOp(unit, child);
                }
            }
        }

        private void emitChainBoundary(SemanticOp chain, SemanticOp boundary) {
            KindPayload.BoundaryPayload payload =
                (KindPayload.BoundaryPayload) boundary.payload();
            String input = slot(payload.input());
            switch (payload.kind()) {
                case ARRAY_ELEMENT_ASSIGNMENT -> {
                    String index = chainIndexSlot(chain);
                    String length = chainLengthSlot(chain);
                    emit("if (" + index + " < 0 || " + index + " > " + length
                        + ") { $rt.fail(\"E8002\", \"array index out of"
                        + " bounds\", " + spanArgs(boundary) + "); }");
                    emit(input + " = $rt.checkType("
                        + jsString(payload.descriptor().canonicalSpecText()) + ", "
                        + input + ", " + spanArgs(boundary) + ");");
                }
                case BYTE_ELEMENT_ASSIGNMENT -> {
                    String index = chainIndexSlot(chain);
                    String length = chainLengthSlot(chain);
                    emit("if (" + index + " < 0 || " + index + " >= " + length
                        + ") { $rt.fail(\"E8012\", \"bytes index out of"
                        + " bounds\", " + spanArgs(boundary) + "); }");
                    emit(input + " = $rt.checkType("
                        + jsString(payload.descriptor().canonicalSpecText()) + ", "
                        + input + ", " + spanArgs(boundary) + ");");
                }
                case ARRAY_ELEMENT_DELETE -> {
                    String index = chainIndexSlot(chain);
                    String length = chainLengthSlot(chain);
                    emit("if (" + index + " < 0 || " + index + " > " + length
                        + ") { $rt.fail(\"E8002\", \"array index out of"
                        + " bounds\", " + spanArgs(boundary) + "); }");
                }
                default -> emit(input + " = $rt.checkType("
                    + jsString(payload.descriptor().canonicalSpecText()) + ", "
                    + input + ", " + spanArgs(boundary) + ");");
            }
        }

        private String chainIndexSlot(SemanticOp chain) {
            for (OpId childId : chainChildIds(chain)) {
                SemanticOp child = opsById.get(childId);
                if (child.kind() == SemanticOpKind.INDEX_NORMALIZE
                        && child.result() instanceof ValueId valueId) {
                    return slot(valueId);
                }
            }
            throw new IllegalStateException("the chain " + chain.opId()
                + " carries no INDEX_NORMALIZE child (producer defect)");
        }

        private String chainLengthSlot(SemanticOp chain) {
            for (OpId childId : chainChildIds(chain)) {
                SemanticOp child = opsById.get(childId);
                if (child.kind() == SemanticOpKind.INDEX_NORMALIZE
                        && child.result() instanceof ValueId valueId) {
                    return normalizeLengthSlot(valueId);
                }
            }
            throw new IllegalStateException("the chain " + chain.opId()
                + " carries no INDEX_NORMALIZE child (producer defect)");
        }

        private List<OpId> chainChildIds(SemanticOp chain) {
            return switch (chain.payload()) {
                case KindPayload.AssignPayload assign -> assign.childOps();
                case KindPayload.DeletePayload delete -> delete.childOps();
                default -> List.of();
            };
        }

        // =====================================================================
        // Calls
        // =====================================================================

        private void emitCall(SemanticOp op) {
            KindPayload.CallPayload payload =
                (KindPayload.CallPayload) op.payload();
            FunctionExecutionBinding binding = resolvedBinding(op, payload);
            if (binding instanceof FunctionExecutionBinding.HostFunction
                    || binding instanceof FunctionExecutionBinding.HostFunctionValue) {
                throw unsupported(op, "the CALL " + op.opId() + " resolves the "
                    + binding.getClass().getSimpleName() + " binding: the host"
                    + " call slice belongs to a realized later decomposition"
                    + " unit");
            }
            if (binding instanceof FunctionExecutionBinding.DynamicFunctionValue
                    dynamic) {
                throw unsupported(op, "the CALL " + op.opId() + " resolves the"
                    + " dynamic function value materialized by "
                    + dynamic.materializingOpId() + ": the runtime"
                    + " execution-class dispatch belongs to a realized later"
                    + " decomposition unit");
            }
            if (binding instanceof FunctionExecutionBinding.ExternalFunction
                    external) {
                if (external.executionOwner()
                        != ExternalExecutionOwner.SHARED_BODY) {
                    throw unsupported(op, "the CALL " + op.opId()
                        + " resolves the RETAINED_ABI external '"
                        + external.moduleId().path() + "#"
                        + external.exportName() + "': the retained-ABI call"
                        + " slice belongs to a realized later decomposition"
                        + " unit");
                }
                List<String> externalArguments = new ArrayList<>();
                for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                    SemanticOp boundary = opsById.get(boundaryId);
                    KindPayload.BoundaryPayload boundaryPayload =
                        (KindPayload.BoundaryPayload) boundary.payload();
                    emitBoundaryCheck(boundary, slot(boundaryPayload.input()),
                        boundaryPayload.descriptor());
                    externalArguments.add(slot(boundaryPayload.input()));
                }
                emit(slotOf(op) + " = $rt.callExport("
                    + jsString(external.moduleId().path()) + ", "
                    + jsString(external.exportName()) + ", ["
                    + String.join(", ", externalArguments) + "]);");
                return;
            }
            if (payload.callee() instanceof KindPayload.CallCallee.Dynamic) {
                throw unsupported(op, "the CALL " + op.opId() + " carries a"
                    + " dynamic callee: the runtime execution-class dispatch"
                    + " belongs to a realized later decomposition unit");
            }
            List<String> arguments = new ArrayList<>();
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                SemanticOp boundary = opsById.get(boundaryId);
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                emitBoundaryCheck(boundary, slot(boundaryPayload.input()),
                    boundaryPayload.descriptor());
                arguments.add(slot(boundaryPayload.input()));
            }
            if (binding instanceof FunctionExecutionBinding.IntrinsicFunction
                    intrinsic) {
                if (arguments.size() != 1) {
                    throw unsupported(op, "the conversion intrinsic call "
                        + op.opId() + " records " + arguments.size()
                        + " parameter cell(s) for one declared parameter"
                        + " (producer defect)");
                }
                emit(slotOf(op) + " = " + intrinsicInvocation(intrinsic.kind(),
                    arguments.get(0), spanArgs(op)) + ";");
                SemanticOp returnBoundary = payload.returnBoundaryOpId() == null
                    ? null : opsById.get(payload.returnBoundaryOpId());
                if (returnBoundary != null) {
                    emitBoundaryCheck(returnBoundary, slotOf(op),
                        descriptorOf(returnBoundary));
                }
                return;
            }
            emit(slotOf(op) + " = " + calleeExpr(op, payload) + ".$f("
                + String.join(", ", arguments) + ");");
        }

        /**
         * The callee expression of one call: the callee value's slot (the
         * recorded function value), or the module binding that holds a static
         * body's created closure. Never a name rediscovery.
         */
        private String calleeExpr(SemanticOp op,
                                  KindPayload.CallPayload payload) {
            if (payload.callee() instanceof KindPayload.CallCallee.Static
                    staticCallee) {
                if (staticCallee.binding()
                        instanceof FunctionExecutionBinding.LoweredBody body) {
                    String binding = closureBindings.get(body.functionId().id());
                    if (binding == null) {
                        throw unsupported(op, "the static CALL " + op.opId()
                            + " names the lowered body " + body.functionId()
                            + " whose created closure has no recorded binding"
                            + " cell (producer defect)");
                    }
                    return binding;
                }
                if (staticCallee.binding()
                        instanceof FunctionExecutionBinding.AdapterBinding
                            adapter) {
                    SemanticOp adaptOp = opsById.get(adapter.adaptOpId());
                    if (adaptOp == null
                            || !(adaptOp.result() instanceof ValueId valueId)) {
                        throw unsupported(op, "the static CALL " + op.opId()
                            + " names the adapter " + adapter.adaptOpId()
                            + " whose creation op carries no result value"
                            + " (producer defect)");
                    }
                    return slot(valueId);
                }
            }
            if (payload.callee() instanceof KindPayload.CallCallee.Indirect
                    indirect) {
                return slot(indirect.callee());
            }
            throw unsupported(op, "the CALL " + op.opId() + " carries no"
                + " statically resolved callee expression");
        }

        private FunctionExecutionBinding resolvedBinding(SemanticOp op,
                KindPayload.CallPayload payload) {
            return switch (payload.callee()) {
                case KindPayload.CallCallee.Static staticCallee ->
                    staticCallee.binding();
                case KindPayload.CallCallee.Indirect indirect ->
                    bindingOfIdentity(indirect.callee());
                case KindPayload.CallCallee.Dynamic ignored -> null;
            };
        }

        private FunctionExecutionBinding bindingOfIdentity(ValueId identity) {
            for (LoweredModuleUnit unit : units.values()) {
                FunctionExecutionBinding found = unit.functionBindings().get(
                    new FunctionAllocationIdentity(identity.id()));
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        // =====================================================================
        // Control flow
        // =====================================================================

        private void emitBranch(SemanticOp op) {
            KindPayload.BranchPayload payload =
                (KindPayload.BranchPayload) op.payload();
            LoweredModuleUnit unit = units.get(opModule.get(op.opId()));
            String condition = slot(payload.condition());
            switch (payload.selector()) {
                case IF -> {
                    emit("if (" + condition + ") {");
                    indent++;
                    emitBlockContent(unit, payload.selectedBlock());
                    indent--;
                    if (payload.alternateBlock() != null) {
                        emit("} else {");
                        indent++;
                        emitBlockContent(unit, payload.alternateBlock());
                        indent--;
                    }
                    emit("}");
                }
                case LOGICAL_AND, LOGICAL_OR -> {
                    String target = slotOf(op);
                    emit(target + " = " + condition + ";");
                    emit(payload.selector() == ControlSelector.LOGICAL_AND
                        ? "if (" + condition + ") {"
                        : "if (!" + condition + ") {");
                    indent++;
                    emitBlockContent(unit, payload.selectedBlock());
                    indent--;
                    emit("}");
                }
                default -> throw unsupported(op, "the BRANCH selector "
                    + payload.selector() + " is not a control-flow shape");
            }
        }

        private void emitLoop(SemanticOp op) {
            KindPayload.LoopPayload payload =
                (KindPayload.LoopPayload) op.payload();
            LoweredModuleUnit unit = units.get(opModule.get(op.opId()));
            String label = loopLabel(op.opId());
            String condition = slot(payload.condition());
            switch (payload.selector()) {
                case WHILE -> {
                    // The condition is produced in the init block, which the
                    // loop re-runs every iteration, so a continue
                    // re-evaluates it exactly like the recorded traversal.
                    emit(label + ": while (true) {");
                    indent++;
                    emitScopedBlock(unit, payload.initBlock());
                    emit("if (!" + condition + ") { break " + label + "; }");
                    emitScopedBlock(unit, payload.bodyBlock());
                    indent--;
                    emit("}");
                }
                case FOR -> {
                    emit("{");
                    indent++;
                    declareBlockBindings(unit, payload.initBlock());
                    emitOps(unit, payload.initBlock());
                    emit(label + ": while (true) {");
                    indent++;
                    emit("if (!" + condition + ") { break " + label + "; }");
                    emitScopedBlock(unit, payload.bodyBlock());
                    if (payload.updateBlock() != null) {
                        emitScopedBlock(unit, payload.updateBlock());
                    }
                    indent--;
                    emit("}");
                    indent--;
                    emit("}");
                }
                default -> throw unsupported(op, "the LOOP selector "
                    + payload.selector() + " is not a loop shape");
            }
        }

        private void emitForEach(SemanticOp op) {
            KindPayload.ForEachPayload payload =
                (KindPayload.ForEachPayload) op.payload();
            LoweredModuleUnit unit = units.get(opModule.get(op.opId()));
            String iterable = "$i" + op.opId().id();
            String cellName = cell(payload.binding(), payload.generation());
            String label = loopLabel(op.opId());
            emit("{");
            indent++;
            emit("const " + iterable + " = " + slot(payload.iterable()) + ";");
            if (payload.mode() == IterationMode.ARRAY_VALUES) {
                String index = "$x" + op.opId().id();
                String element = "$e" + op.opId().id();
                // The iterable is read once; the increasing index reads the
                // live position through the runtime's carrier split, and a
                // missing or undefined position ends the iteration.
                emit(label + ": for (let " + index + " = 0; ; " + index
                    + "++) {");
                indent++;
                emit("const " + element + " = $rt.arrayAt(" + iterable + ", "
                    + index + ");");
                emit("if (" + element + " === $rt.undefined) { break " + label
                    + "; }");
                emit("const " + cellName + " = " + element + ";");
                emitBlockContent(unit, payload.body());
                indent--;
                emit("}");
            } else {
                emit(label + ": for (const " + cellName + " of $rt.scalars("
                    + iterable + ")) {");
                indent++;
                emitBlockContent(unit, payload.body());
                indent--;
                emit("}");
            }
            indent--;
            emit("}");
        }

        private void emitTryCatch(SemanticOp op) {
            KindPayload.TryCatchPayload payload =
                (KindPayload.TryCatchPayload) op.payload();
            LoweredModuleUnit unit = units.get(opModule.get(op.opId()));
            String caught = "$c" + op.opId().id();
            emit("try {");
            indent++;
            emitScopedBlock(unit, payload.tryBlock());
            indent--;
            emit("} catch (" + caught + ") {");
            indent++;
            // The catch binding's own cell: the total reification of the
            // thrown value, declared by the catch clause (its recorded
            // BINDING_ALLOC never resets it).
            emit("let " + cell(payload.catchBinding(), 0)
                + " = $rt.reifyError(" + caught + ");");
            emitScopedBlock(unit, payload.catchBlock());
            indent--;
            emit("}");
        }

        private void emitThrow(SemanticOp op) {
            KindPayload.ThrowPayload payload =
                (KindPayload.ThrowPayload) op.payload();
            // The recorded THROW transport: a constructed Error takes the
            // throw site's own origin; an already-transported value (a caught
            // error, or one that crossed a boundary) is rethrown unchanged.
            emit("throw $rt.throwValue(" + slot(payload.errorValue()) + ", "
                + spanArgs(op) + ");");
        }

        private void emitReturn(SemanticOp op) {
            KindPayload.ReturnPayload payload =
                (KindPayload.ReturnPayload) op.payload();
            String value = payload.value() == null ? null : slot(payload.value());
            if (value != null) {
                SemanticOp boundary = opsById.get(payload.returnBoundaryOpId());
                if (boundary != null) {
                    emitBoundaryCheck(boundary, value, descriptorOf(boundary));
                }
            }
            emit("return " + (value == null ? "null" : value) + ";");
        }

        private void emitBreakContinue(SemanticOp op, boolean isBreak) {
            OpId loopId = isBreak
                ? ((KindPayload.BreakPayload) op.payload()).loopId()
                : ((KindPayload.ContinuePayload) op.payload()).loopId();
            if (!isBreak) {
                SemanticOp loop = opsById.get(loopId);
                if (loop != null
                        && loop.payload() instanceof KindPayload.LoopPayload
                            loopPayload
                        && loopPayload.selector() == ControlSelector.FOR
                        && loopPayload.updateBlock() != null) {
                    // A continue of a for-loop runs the recorded update
                    // before the condition re-check.
                    emitScopedBlock(units.get(opModule.get(loopId)),
                        loopPayload.updateBlock());
                }
            }
            emit((isBreak ? "break " : "continue ") + loopLabel(loopId) + ";");
        }

        private void emitDiscard(SemanticOp op) {
            KindPayload.DiscardPayload payload =
                (KindPayload.DiscardPayload) op.payload();
            emit("// discarded: " + slot(payload.value()));
        }

        private static String loopLabel(OpId loopId) {
            return "$L" + loopId.id();
        }

        // =====================================================================
        // Modules
        // =====================================================================

        private void emitModuleImport(SemanticOp op) {
            KindPayload.ModuleImportPayload payload =
                (KindPayload.ModuleImportPayload) op.payload();
            switch (payload.kind()) {
                case COMPILED -> {
                    String specifier = relativeSpecifier(
                        opModule.get(op.opId()).path(),
                        payload.resolvedModule().path());
                    emit("const $d" + op.opId().id() + " = $require("
                        + jsString(specifier) + ");");
                    for (BindingId alias : payload.aliasCells()) {
                        emit(cell(alias, 0) + " = $rt.moduleSurface("
                            + jsString(payload.resolvedModule().path()) + ");");
                    }
                }
                case STDLIB, HOST -> throw unsupported(op, "the MODULE_IMPORT "
                    + op.opId() + " resolves the " + payload.kind()
                    + " module '" + payload.resolvedModule().path() + "': the"
                    + " host/stdlib import slice belongs to a realized later"
                    + " decomposition unit");
            }
        }

        private void emitExportRead(SemanticOp op) {
            KindPayload.ExportReadPayload payload =
                (KindPayload.ExportReadPayload) op.payload();
            ModuleImportKind kind = importKindOf(payload.module());
            if (kind != null && kind != ModuleImportKind.COMPILED) {
                throw unsupported(op, "the EXPORT_READ " + op.opId() + " of '"
                    + payload.module().path() + "#" + payload.name()
                    + "' resolves a " + kind + " surface: the host/stdlib read"
                    + " slice belongs to a realized later decomposition unit");
            }
            emit(slotOf(op) + " = $rt.readModule("
                + jsString(payload.module().path()) + ", "
                + jsString(payload.name()) + ");");
        }

        private void emitExportPublish(SemanticOp op) {
            KindPayload.ExportPublishPayload payload =
                (KindPayload.ExportPublishPayload) op.payload();
            SemanticOp boundary = boundaryChildOf(op);
            if (boundary != null) {
                emitBoundaryCheck(boundary, slot(payload.value()),
                    payload.descriptor());
            }
            emit("$rt.publishModule(" + jsString(payload.module().path()) + ", "
                + jsString(payload.name()) + ", " + slot(payload.value()) + ");");
        }

        private void emitEntryInvoke(LoweredModuleUnit unit, SemanticOp op) {
            KindPayload.EntryInvokePayload payload =
                (KindPayload.EntryInvokePayload) op.payload();
            if (!unit.moduleId().equals(payload.module())) {
                throw unsupported(op, "the ENTRY_INVOKE " + op.opId()
                    + " names module " + payload.module() + " but belongs to "
                    + unit.moduleId() + " (producer defect)");
            }
            for (SemanticOp candidate : unit.ops()) {
                if (candidate.kind() == SemanticOpKind.CALL
                        && op.opId().equals(candidate.origin().parentOpId())) {
                    if (unit.moduleId().equals(input.project().entryModule())) {
                        emit("if ($require.main === $module) {");
                        indent++;
                        emit("try {");
                        indent++;
                        emitCall(candidate);
                        indent--;
                        emit("} catch ($entryError) {");
                        indent++;
                        emit("const $err = $rt.reifyError($entryError);");
                        emit("$rt.reportUncaught($err.file !== $rt.undefined"
                            + " ? $rt.errorValue($err.code, $err.message + \""
                            + " at \" + $err.file + \":\" + $err.line + \":\" +"
                            + " $err.column, $err.file, $err.line,"
                            + " $err.column) : $err);");
                        indent--;
                        emit("}");
                        indent--;
                        emit("}");
                    }
                    return;
                }
            }
            throw unsupported(op, "the ENTRY_INVOKE " + op.opId()
                + " carries no delegated CALL child (producer defect)");
        }

        /**
         * The builtin {@code Error} construction only: {@code throw}
         * statements, rethrow transport and the entry shim need the tagged
         * Error carrier, whose layout is a compiler constant. Every declared
         * class belongs to the class-realization decomposition unit and fails
         * closed here.
         */
        private void emitBuiltinErrorConstruction(SemanticOp op) {
            KindPayload.ClassNewPayload payload =
                (KindPayload.ClassNewPayload) op.payload();
            if (!payload.classId().equals(deal.semantic.ir.ClassId.ERROR)) {
                throw unsupported(op, "the CLASS_NEW " + op.opId()
                    + " constructs the declared class " + payload.classId()
                    + ": declared class construction belongs to the"
                    + " class-realization decomposition unit");
            }
            for (KindPayload.FieldBoundary fieldBoundary
                    : payload.fieldBoundaries()) {
                SemanticOp boundary = opsById.get(fieldBoundary.boundaryOpId());
                ValueId value = providedValue(payload, fieldBoundary.field());
                if (value == null || boundary == null) {
                    continue;
                }
                emitBoundaryCheck(boundary, slot(value),
                    descriptorOf(boundary));
            }
            StringBuilder provided = new StringBuilder("{");
            for (int i = 0; i < payload.providedFields().size(); i++) {
                KindPayload.ProvidedField field = payload.providedFields().get(i);
                if (i > 0) {
                    provided.append(", ");
                }
                provided.append("[").append(jsString(field.name()))
                    .append("]: ").append(slot(field.valueOpId()));
            }
            provided.append("}");
            StringBuilder defaults = new StringBuilder("() => ({");
            List<ClassLayout.FieldLayout> fields = payload.layout().fields();
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) {
                    defaults.append(", ");
                }
                defaults.append("[").append(jsString(fields.get(i).name()))
                    .append("]: \"\"");
            }
            defaults.append("})");
            emit(slotOf(op) + " = $rt.makeClass("
                + jsString(payload.classId().name()) + ", "
                + jsString(payload.classId().text()) + ", " + defaults + ", "
                + provided + ", " + spanArgs(op) + ");");
        }

        private ValueId providedValue(KindPayload.ClassNewPayload payload,
                                      String field) {
            for (KindPayload.ProvidedField provided : payload.providedFields()) {
                if (provided.name().equals(field)) {
                    return provided.valueOpId();
                }
            }
            return null;
        }

        // =====================================================================
        // Helpers
        // =====================================================================

        private ModuleImportKind importKindOf(ModuleId module) {
            for (LoweredModuleUnit unit : units.values()) {
                for (SemanticOp op : unit.ops()) {
                    if (op.kind() == SemanticOpKind.MODULE_IMPORT
                            && op.payload() instanceof KindPayload
                                .ModuleImportPayload payload
                            && payload.resolvedModule().equals(module)) {
                        return payload.kind();
                    }
                }
            }
            return null;
        }

        /**
         * The single BOUNDARY child parented to one op, or null. When more
         * than one boundary names the op as its parent (the producer
         * usually records exactly one), the lowest op id wins, so repeated
         * emissions are byte-identical regardless of map iteration order.
         */
        private SemanticOp boundaryChildOf(SemanticOp op) {
            SemanticOp found = null;
            for (SemanticOp candidate : opsById.values()) {
                if (candidate.kind() != SemanticOpKind.BOUNDARY
                        || !op.opId().equals(candidate.origin().parentOpId())) {
                    continue;
                }
                if (found == null
                        || candidate.opId().id() < found.opId().id()) {
                    found = candidate;
                }
            }
            return found;
        }

        private LoweredModuleUnit ownerOfFunction(FunctionId functionId) {
            for (LoweredModuleUnit unit : units.values()) {
                if (unit.functions().containsKey(functionId)) {
                    return unit;
                }
            }
            throw new IllegalStateException("the function " + functionId
                + " is outside the session's closure (producer defect)");
        }

        private String slotOf(SemanticOp op) {
            return slot((ValueId) op.result());
        }

        private static String slot(ValueId id) {
            return "$s" + id.id();
        }

        private static String cell(BindingId id, long generation) {
            return generation == 0 ? "$g" + id.id()
                : "$g" + id.id() + "_" + generation;
        }

        /**
         * The cell expression of one binding reference at the current emission
         * context. Native lexical scope carries every capture: a closure body
         * emitted at its creation site reads the surrounding binding directly,
         * and a per-iteration {@code let} gives each iteration its own live
         * cell.
         */
        private static String cellSource(BindingId binding, long generation) {
            return cell(binding, generation);
        }

        private static String lengthSlot(OpId opId) {
            return "$n" + opId.id();
        }

        private static String numberLiteral(double value) {
            if (Double.isNaN(value)) {
                return "NaN";
            }
            if (value == Double.POSITIVE_INFINITY) {
                return "Infinity";
            }
            if (value == Double.NEGATIVE_INFINITY) {
                return "-Infinity";
            }
            String text = Double.toString(value);
            if (!text.contains(".") && !text.contains("e")
                    && !text.contains("E")) {
                text = text + ".0";
            }
            return text;
        }

        private static String artifactRelativePath(ModuleId module) {
            return module.path().replace('.', '/') + ".js";
        }

        private static String runtimeSpecifier(ModuleId module) {
            return relativeSpecifier(module.path(), "deal/runtime");
        }

        /**
         * The relative CommonJS require specifier from one artifact's
         * directory to a target artifact (the retained backend rule): the
         * dotted target path carries the artifact path with {@code '/'} for
         * {@code '.'}, the extension omitted, and a target outside the
         * emitting directory takes {@code ../} segments.
         */
        private static String relativeSpecifier(String fromModulePath,
                                                String targetPath) {
            String moduleArtifact = (fromModulePath == null ? ""
                : fromModulePath).replace('.', '/');
            String targetArtifact = targetPath.replace('.', '/');
            int moduleLast = moduleArtifact.lastIndexOf('/');
            String moduleDir = moduleLast < 0 ? ""
                : moduleArtifact.substring(0, moduleLast);
            int targetLast = targetArtifact.lastIndexOf('/');
            String targetDir = targetLast < 0 ? ""
                : targetArtifact.substring(0, targetLast);
            String targetName = targetLast < 0 ? targetArtifact
                : targetArtifact.substring(targetLast + 1);
            List<String> moduleSegs = moduleDir.isEmpty() ? List.of()
                : List.of(moduleDir.split("/", -1));
            List<String> targetSegs = targetDir.isEmpty() ? List.of()
                : List.of(targetDir.split("/", -1));
            int common = 0;
            while (common < moduleSegs.size() && common < targetSegs.size()
                    && moduleSegs.get(common).equals(targetSegs.get(common))) {
                common++;
            }
            StringBuilder specifier = new StringBuilder();
            for (int i = common; i < moduleSegs.size(); i++) {
                if (specifier.length() > 0) {
                    specifier.append('/');
                }
                specifier.append("..");
            }
            for (int i = common; i < targetSegs.size(); i++) {
                if (specifier.length() > 0) {
                    specifier.append('/');
                }
                specifier.append(targetSegs.get(i));
            }
            if (specifier.length() > 0) {
                specifier.append('/');
            }
            specifier.append(targetName);
            String relative = specifier.toString();
            return relative.startsWith(".") ? relative : "./" + relative;
        }

        // =====================================================================
        // Output
        // =====================================================================

        private void emit(String text) {
            for (int i = 0; i < indent; i++) {
                out.append("  ");
            }
            out.append(text).append('\n');
            line++;
        }

        /**
         * Emits one op group's source comment and records its generated
         * position (the map_path_ownership and mapping_origin_split rules):
         * a local USER/SYNTHETIC group maps to its recorded local source
         * start and names this module's display file. A foreign-origin
         * child stays at its execution position and maps through its
         * recorded parent op when that parent is local — the caller's
         * initiating call/check group — and is omitted when no local
         * parent links it: a foreign origin never emits foreign coordinates
         * under the caller's filename, and no nearest statement is ever
         * selected.
         */
        private void beginMappedGroup(SemanticOp op) {
            if (mappings == null) {
                return;
            }
            SourceSpan span = localMappingSpan(op);
            if (span == null) {
                return;
            }
            JsSourceFile sourceFile = sourceFiles.get(currentModule);
            String displayName = sourceFile == null ? span.file()
                : sourceFile.displayName();
            int generatedLine = line;
            int generatedColumn = indent * 2 + 1;
            emit("// " + sourceCommentText(displayName) + ":"
                + span.startLine() + ":" + span.startColumn());
            mappings.add(new SourceMapGenerator.Mapping(generatedLine,
                generatedColumn, span.startLine(), span.startColumn()));
        }

        /**
         * The local mapping span of one op: its own span when the origin
         * belongs to this module's authoritative source id, otherwise the
         * recorded parent op's span when that parent is local, otherwise
         * none.
         */
        private SourceSpan localMappingSpan(SemanticOp op) {
            if (op.origin() == null || op.origin().span() == null) {
                return null;
            }
            if (isLocalOrigin(op)) {
                return op.origin().span();
            }
            OpId parentId = op.origin().parentOpId();
            if (parentId == null) {
                return null;
            }
            SemanticOp parent = opsById.get(parentId);
            if (parent == null || parent.origin() == null
                    || !isLocalOrigin(parent)) {
                return null;
            }
            return parent.origin().span();
        }

        /** Whether one op's recorded origin source is this module's own. */
        private boolean isLocalOrigin(SemanticOp op) {
            JsSourceFile sourceFile = sourceFiles.get(currentModule);
            if (sourceFile == null || op.origin() == null) {
                return false;
            }
            return sourceFile.sourceId().equals(op.origin().sourceId());
        }

        private static String sourceCommentText(String text) {
            StringBuilder escaped = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '\\' -> escaped.append("\\\\");
                    case '\n' -> escaped.append("\\n");
                    case '\r' -> escaped.append("\\r");
                    default -> escaped.append(c);
                }
            }
            return escaped.toString();
        }

        /**
         * The location arguments of one runtime call: the op's recorded
         * source span, or the runtime's absent location when the origin is
         * genuinely unlocated.
         */
        private static String spanArgs(SemanticOp op) {
            SourceSpan span = op.origin() == null ? null : op.origin().span();
            if (span == null) {
                return "$rt.undefined, $rt.undefined, $rt.undefined";
            }
            return jsString(span.file()) + ", " + span.startLine() + ", "
                + span.startColumn();
        }

        private SharedEmissionFailure unsupported(SemanticOp op, String message) {
            DiagnosticRange range = op.origin() == null
                    || op.origin().span() == null ? null
                : op.origin().span().range();
            return new SharedEmissionFailure(message, range);
        }

        private static String jsString(String text) {
            StringBuilder sb = new StringBuilder(text.length() + 2);
            sb.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    case '\u2028' -> sb.append("\\u2028");
                    case '\u2029' -> sb.append("\\u2029");
                    default -> {
                        if (c < 0x20) {
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
    }
}
