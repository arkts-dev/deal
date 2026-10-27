package deal.codegen.llvm;

import deal.semantic.ir.AssignTargetKind;
import deal.semantic.ir.BinarySelector;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.CallMode;
import deal.semantic.ir.ControlSelector;
import deal.semantic.ir.DeleteTargetKind;
import deal.semantic.ir.FailureArmId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleImportKind;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticValue;
import deal.semantic.ir.StdlibFunctionId;
import deal.semantic.ir.UnarySelector;
import deal.semantic.ir.ValueId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static deal.codegen.llvm.LlvmStageClassifier.StageSupport.REJECTED;
import static deal.codegen.llvm.LlvmStageClassifier.StageSupport.STAGED;
import static deal.codegen.llvm.LlvmStageClassifier.StageSupport.STATIC_ONLY;

/**
 * The closed-set stage classifier of the native backend
 * ({@code llvm-native-codegen-cfg-ssa} C1/C9, task "Stage classifier").
 *
 * <p>Every merged semantic fact — operation kind, payload variant, binary and
 * unary selector, boundary kind, stdlib operation, failure arm, control
 * selector, call mode, assignment/delete target kind and the module-import
 * kind (the host-import fact) — is classified once, here, into exactly one of
 * three answers:</p>
 *
 * <ul>
 *   <li>{@link StageSupport#STAGED} — the native skeleton renders the fact;</li>
 *   <li>{@link StageSupport#STATIC_ONLY} — the fact is rendered only when its
 *   static operand facts already prove it (a typed-boundary check of a value
 *   whose lowered static type equals the declared descriptor, a
 *   short-circuit selector over a staged boolean); a fact whose static
 *   operand does not prove it is rejected;</li>
 *   <li>{@link StageSupport#REJECTED} — the fact needs a runtime service
 *   outside this stage's permitted references (an allocation, barrier,
 *   descriptor, error-materialization, container, text, stdlib, host or async
 *   entry, or a managed value) and raises one origin-bearing E6005.</li>
 * </ul>
 *
 * <p>The classifier is exhaustive by construction: the switches below are
 * `switch` expressions over the closed enums and the closed {@link KindPayload}
 * sealed interface, so a new merged member cannot compile without a decision.
 * {@link #facts()} enumerates every closed fact for the coverage test, which
 * asserts that no member is unclassified and that the staged set is exactly
 * the set the skeleton renders.</p>
 */
public final class LlvmStageClassifier {

    private LlvmStageClassifier() {
        // Static classifier only; no instances.
    }

    /** The three answers of the stage classification. */
    public enum StageSupport {
        /** The skeleton renders the fact. */
        STAGED,
        /** The skeleton renders the fact only when static operand facts prove it. */
        STATIC_ONLY,
        /** The fact needs a runtime service or managed value outside the stage. */
        REJECTED
    }

    /** One classified fact: its family, its member name and the answer. */
    public record Fact(String family, String member, StageSupport support) {
        public Fact {
            Objects.requireNonNull(family, "family must not be null");
            Objects.requireNonNull(member, "member must not be null");
            Objects.requireNonNull(support, "support must not be null");
        }
    }

    /** The rejection reason of one fact outside the staged subset. */
    public static final String REJECTION_LEAD =
        "the LLVM emission stage supports only the scalar native skeleton";

    // =========================================================================
    // The closed decisions
    // =========================================================================

    /** The decision of one operation kind (a payload-independent answer). */
    public static StageSupport opKind(SemanticOpKind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        return switch (kind) {
            case CONST, UNARY, BINARY, BINDING_ALLOC, BINDING_INIT, BINDING_LOAD,
                 BINDING_STORE, ASSIGN, CALL, CLOSURE_NEW, BRANCH, LOOP, RETURN,
                 BREAK, CONTINUE, DISCARD, MODULE_INIT, MODULE_IMPORT,
                 EXPORT_READ, EXPORT_PUBLISH, ENTRY_INVOKE -> STAGED;
            case BOUNDARY -> STATIC_ONLY;
            case STRING_CONCAT, ARRAY_NEW, TABLE_NEW, ARRAY_LENGTH, MEMBER_READ,
                 MEMBER_WRITE, MEMBER_DELETE, INDEX_NORMALIZE, INDEX_READ,
                 INDEX_WRITE, INDEX_DELETE, OPTIONAL_READ, HAS_FIELD,
                 RECURSIVE_GROUP_INIT, FUNCTION_ADAPT, DELETE, EXTERNAL_ENTRY,
                 CALLBACK_INVOKE, STDLIB_CALL, ASYNC_START, AWAIT, FOR_EACH,
                 TRY_CATCH, THROW, CLASS_NEW, CLASS_FACTORY, FIELD_READ,
                 FIELD_WRITE, FIELD_DELETE, JSON_FROM_CLASS, JSON_TO_CLASS -> REJECTED;
        };
    }

    /**
     * The decision of one closed payload variant, keyed by its class: the
     * closed {@link KindPayload} family has no value enumeration, so the
     * table below is the authority and {@link #facts()} proves it covers
     * every permitted variant (an unlisted variant fails closed here).
     */
    public static StageSupport payload(Class<?> payloadClass) {
        Objects.requireNonNull(payloadClass, "payloadClass must not be null");
        StageSupport support = PAYLOAD_SUPPORT.get(payloadClass);
        if (support == null) {
            throw new IllegalStateException(
                "the LLVM stage classifier has no decision for the payload"
                    + " variant " + payloadClass.getName());
        }
        return support;
    }

    private static final Map<Class<?>, StageSupport> PAYLOAD_SUPPORT =
        payloadSupport();

    private static Map<Class<?>, StageSupport> payloadSupport() {
        Map<Class<?>, StageSupport> map = new LinkedHashMap<>();
        map.put(KindPayload.ConstPayload.class, STAGED);
        map.put(KindPayload.UnaryPayload.class, STAGED);
        map.put(KindPayload.BinaryPayload.class, STAGED);
        map.put(KindPayload.BindingAllocPayload.class, STAGED);
        map.put(KindPayload.BindingInitPayload.class, STAGED);
        map.put(KindPayload.BindingLoadPayload.class, STAGED);
        map.put(KindPayload.BindingStorePayload.class, STAGED);
        map.put(KindPayload.AssignPayload.class, STATIC_ONLY);
        map.put(KindPayload.CallPayload.class, STATIC_ONLY);
        map.put(KindPayload.ClosureNewPayload.class, STATIC_ONLY);
        map.put(KindPayload.BranchPayload.class, STAGED);
        map.put(KindPayload.LoopPayload.class, STATIC_ONLY);
        map.put(KindPayload.ReturnPayload.class, STAGED);
        map.put(KindPayload.BreakPayload.class, STAGED);
        map.put(KindPayload.ContinuePayload.class, STAGED);
        map.put(KindPayload.DiscardPayload.class, STAGED);
        map.put(KindPayload.BoundaryPayload.class, STATIC_ONLY);
        map.put(KindPayload.ModuleInitPayload.class, STAGED);
        map.put(KindPayload.ModuleImportPayload.class, STATIC_ONLY);
        map.put(KindPayload.ExportReadPayload.class, STATIC_ONLY);
        map.put(KindPayload.ExportPublishPayload.class, STATIC_ONLY);
        map.put(KindPayload.EntryInvokePayload.class, STAGED);
        map.put(KindPayload.StringConcatPayload.class, REJECTED);
        map.put(KindPayload.ArrayNewPayload.class, REJECTED);
        map.put(KindPayload.TableNewPayload.class, REJECTED);
        map.put(KindPayload.ArrayLengthPayload.class, REJECTED);
        map.put(KindPayload.MemberReadPayload.class, REJECTED);
        map.put(KindPayload.MemberWritePayload.class, REJECTED);
        map.put(KindPayload.MemberDeletePayload.class, REJECTED);
        map.put(KindPayload.IndexNormalizePayload.class, REJECTED);
        map.put(KindPayload.IndexReadPayload.class, REJECTED);
        map.put(KindPayload.IndexWritePayload.class, REJECTED);
        map.put(KindPayload.IndexDeletePayload.class, REJECTED);
        map.put(KindPayload.OptionalReadPayload.class, REJECTED);
        map.put(KindPayload.HasFieldPayload.class, REJECTED);
        map.put(KindPayload.RecursiveGroupInitPayload.class, REJECTED);
        map.put(KindPayload.FunctionAdaptPayload.class, REJECTED);
        map.put(KindPayload.DeletePayload.class, REJECTED);
        map.put(KindPayload.ExternalEntryPayload.class, REJECTED);
        map.put(KindPayload.CallbackInvokePayload.class, REJECTED);
        map.put(KindPayload.StdlibCallPayload.class, REJECTED);
        map.put(KindPayload.AsyncStartPayload.class, REJECTED);
        map.put(KindPayload.AwaitPayload.class, REJECTED);
        map.put(KindPayload.ForEachPayload.class, REJECTED);
        map.put(KindPayload.TryCatchPayload.class, REJECTED);
        map.put(KindPayload.ThrowPayload.class, REJECTED);
        map.put(KindPayload.ClassNewPayload.class, REJECTED);
        map.put(KindPayload.ClassFactoryPayload.class, REJECTED);
        map.put(KindPayload.FieldReadPayload.class, REJECTED);
        map.put(KindPayload.FieldWritePayload.class, REJECTED);
        map.put(KindPayload.FieldDeletePayload.class, REJECTED);
        map.put(KindPayload.JsonFromClassPayload.class, REJECTED);
        map.put(KindPayload.JsonToClassPayload.class, REJECTED);
        return Map.copyOf(map);
    }

    /**
     * The decision of one binary selector: the binary64 arithmetic and
     * comparison selectors and the boolean equality selectors are native;
     * every string, nullish and reference selector needs the {@code text}
     * group, the nullish singletons or the managed identity comparison
     * ({@code llvm-native-codegen-cfg-ssa} C6).
     */
    public static StageSupport binarySelector(BinarySelector selector) {
        Objects.requireNonNull(selector, "selector must not be null");
        return switch (selector) {
            case NUMBER_ADD, NUMBER_SUB, NUMBER_MUL, NUMBER_DIV_IEEE,
                 NUMBER_EQ, NUMBER_NE, NUMBER_LT, NUMBER_LE, NUMBER_GT,
                 NUMBER_GE, BOOLEAN_EQ, BOOLEAN_NE -> STAGED;
            case STRING_EQ, STRING_NE, STRING_LT, STRING_LE, STRING_GT,
                 STRING_GE, NULL_EQ, NULL_NE, UNKNOWN_EQ, UNKNOWN_NE,
                 UNDEFINED_EQ, UNDEFINED_NE, NULLABLE_UNDEFINED_EQ,
                 NULLABLE_UNDEFINED_NE, NULLABLE_EQ, NULLABLE_NE,
                 NULLABLE_NULL_EQ, NULLABLE_NULL_NE, REFERENCE_EQ,
                 REFERENCE_NE -> REJECTED;
        };
    }

    /** The decision of one unary selector (C6). */
    public static StageSupport unarySelector(UnarySelector selector) {
        Objects.requireNonNull(selector, "selector must not be null");
        return switch (selector) {
            case BOOL_NOT, NUMBER_NEG -> STAGED;
            case TYPEOF, INSTANCEOF -> REJECTED;
        };
    }

    /**
     * The decision of one boundary kind: every boundary is rendered only when
     * the input's lowered static type already equals the declared descriptor
     * (a native scalar, a static singleton, or a statically resolved function
     * identity). Every other boundary needs {@code deal_check_value} and the
     * arm-driven failure materialization, both outside this stage.
     */
    public static StageSupport boundaryKind(BoundaryKind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        return switch (kind) {
            case ASSERTION, VARIABLE_DECLARATION, VARIABLE_ASSIGNMENT,
                 FUNCTION_PARAMETER, FUNCTION_RETURN, MODULE_EXPORT,
                 EXTERNAL_PARAMETER, EXTERNAL_RETURN, IMPORTED_MEMBER_READ,
                 NARROWED_READ, CONTEXTUAL_TABLE_READ, OPTIONAL_FIELD_READ -> STATIC_ONLY;
            case CLASS_FIELD_ASSIGNMENT, ARRAY_ELEMENT_ASSIGNMENT,
                 ARRAY_ELEMENT_READ, ARRAY_ELEMENT_DELETE, ARRAY_LITERAL_ELEMENT,
                 ASYNC_COMPLETION, CLASS_LITERAL_FIELD, UNTYPED_CLASS_INPUT,
                 HOST_TO_DEAL, DEAL_TO_HOST, STDLIB_PARAMETER, STDLIB_RETURN,
                 JSON_FROM_FIELD, JSON_TO_FIELD -> REJECTED;
        };
    }

    /** The decision of one stdlib operation: the whole group is a runtime entry. */
    public static StageSupport stdlib(StdlibFunctionId function) {
        Objects.requireNonNull(function, "function must not be null");
        return switch (function) {
            case CONSOLE_LOG, CONSOLE_ERROR, STRING_LENGTH, STRING_SUBSTRING,
                 STRING_CONTAINS, STRING_STARTS_WITH, STRING_ENDS_WITH,
                 STRING_REPLACE, STRING_SPLIT, STRING_TRIM, TABLE_KEYS,
                 JSON_PARSE, JSON_STRINGIFY, MATH_FLOOR, MATH_CEIL, MATH_SQRT,
                 MATH_ABS_NUMBER, TIME_NOW_MILLIS -> REJECTED;
        };
    }

    /**
     * The decision of one failure arm: every arm is emitted as data in the
     * closed table and installed before user code (R5); the emitter authors
     * no text and rejects no arm.
     */
    public static StageSupport failureArm(FailureArmId arm) {
        Objects.requireNonNull(arm, "arm must not be null");
        return STAGED;
    }

    /** The decision of one module-import kind (the host-import fact, H1/H7). */
    public static StageSupport importKind(ModuleImportKind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        return switch (kind) {
            case COMPILED -> STAGED;
            case STDLIB, HOST -> REJECTED;
        };
    }

    /** The decision of one control selector. */
    public static StageSupport controlSelector(ControlSelector selector) {
        Objects.requireNonNull(selector, "selector must not be null");
        return switch (selector) {
            case IF, WHILE -> STAGED;
            case LOGICAL_AND, LOGICAL_OR -> STATIC_ONLY;
            case TRY_CATCH -> REJECTED;
        };
    }

    /** The decision of one call mode. */
    public static StageSupport callMode(CallMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        return switch (mode) {
            case DIRECT, EXTERNAL -> STATIC_ONLY;
            case INDIRECT, HOST -> REJECTED;
        };
    }

    /** The decision of one assignment target kind (only a binding assignment). */
    public static StageSupport assignTarget(AssignTargetKind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        return switch (kind) {
            case VARIABLE -> STAGED;
            case TABLE_SLOT, ARRAY_SLOT, CLASS_FIELD -> REJECTED;
        };
    }

    /** The decision of one delete target kind: no delete target is staged. */
    public static StageSupport deleteTarget(DeleteTargetKind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        return switch (kind) {
            case TABLE_SLOT, ARRAY_SLOT, CLASS_FIELD -> REJECTED;
        };
    }

    // =========================================================================
    // The per-operation decision over the real project facts
    // =========================================================================

    /**
     * The rejection reason of one operation, or empty when the native
     * skeleton renders it. The answer is the operation's own decision over
     * its payload variant, its selectors and — for the static-only facts —
     * the operand facts the lowered project already records.
     */
    public static Optional<String> rejectReason(LoweredModuleUnit unit,
                                                SemanticOp op) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(op, "op must not be null");
        if (opKind(op.kind()) == REJECTED) {
            return Optional.of(reject(op, op.kind().name()));
        }
        if (payload(op.payload().getClass()) == REJECTED) {
            return Optional.of(reject(op, op.payload().getClass().getSimpleName()));
        }
        return switch (op.payload()) {
            case KindPayload.ConstPayload constPayload ->
                scalarReason(op, constPayload.value());
            case KindPayload.UnaryPayload unary ->
                unarySelector(unary.selector()) == STAGED
                    ? Optional.empty()
                    : Optional.of(reject(op, "UNARY " + unary.selector()));
            case KindPayload.BinaryPayload binary ->
                binarySelector(binary.selector()) == STAGED
                    ? Optional.empty()
                    : Optional.of(reject(op, "BINARY " + binary.selector()));
            case KindPayload.BindingAllocPayload alloc ->
                stagedCellKind(unit, alloc.binding(), alloc.cellKind())
                    ? Optional.empty()
                    : Optional.of(reject(op, "BINDING_ALLOC with the shared"
                        + " cell of a captured or reassigned binding"));
            case KindPayload.BindingInitPayload init ->
                bindingReason(unit, init.binding(), init.generation());
            case KindPayload.BindingLoadPayload load ->
                bindingReason(unit, load.binding(), load.generation());
            case KindPayload.BindingStorePayload store ->
                bindingReason(unit, store.binding(), store.generation());
            case KindPayload.AssignPayload assign ->
                assignTarget(assign.targetKind()) == STAGED
                    ? Optional.empty()
                    : Optional.of(reject(op, "ASSIGN " + assign.targetKind()));
            case KindPayload.CallPayload call -> callReason(unit, op, call);
            case KindPayload.ClosureNewPayload closure ->
                closureReason(unit, op, closure);
            case KindPayload.BranchPayload branch ->
                controlSelector(branch.selector()) == REJECTED
                    ? Optional.of(reject(op, "BRANCH " + branch.selector()))
                    : Optional.empty();
            case KindPayload.LoopPayload loop ->
                loop.selector() == ControlSelector.WHILE
                    && loop.updateBlock() == null
                    ? Optional.empty()
                    : Optional.of(reject(op, "LOOP " + loop.selector()));
            case KindPayload.BoundaryPayload boundary ->
                boundaryReason(unit, op, boundary);
            case KindPayload.ReturnPayload returned ->
                representationReason(op, "the returned value", returned.value());
            case KindPayload.DiscardPayload ignored -> Optional.empty();
            case KindPayload.ModuleImportPayload imported ->
                importKind(imported.kind()) == STAGED
                    ? Optional.empty()
                    : Optional.of(reject(op, "MODULE_IMPORT "
                        + imported.kind() + " of " + imported.resolvedModule()));
            case KindPayload.ExportPublishPayload published ->
                exportPublishReason(unit, op, published);
            case KindPayload.ExportReadPayload read ->
                read.descriptor() instanceof RuntimeDescriptor.Func function
                    && staticSignature(function)
                    ? Optional.empty()
                    : Optional.of(reject(op, "EXPORT_READ of the managed value '"
                        + read.descriptor().canonicalSpecText() + "'"));
            case KindPayload.EntryInvokePayload ignored -> Optional.empty();
            case KindPayload.ModuleInitPayload ignored -> Optional.empty();
            case KindPayload.BreakPayload ignored -> Optional.empty();
            case KindPayload.ContinuePayload ignored -> Optional.empty();
            default -> Optional.of(reject(op, op.payload()
                .getClass().getSimpleName()));
        };
    }

    private static Optional<String> scalarReason(SemanticOp op,
                                                 ScalarValue value) {
        return switch (value) {
            case ScalarValue.Number ignored -> Optional.empty();
            case ScalarValue.Boolean ignored -> Optional.empty();
            case ScalarValue.Null ignored -> Optional.empty();
            case ScalarValue.Undefined ignored -> Optional.empty();
            case ScalarValue.String ignored -> Optional.of(reject(op,
                "a managed string constant"));
        };
    }

    /**
     * The decision of one binding incarnation: a {@code DIRECT} cell is the
     * native slot of this stage. A {@code SHARED_CELL} incarnation is the
     * closure cell of C5 and needs the managed collector — except for a
     * binding no created closure captures and no {@code BINDING_STORE}
     * reassigns, whose cell has exactly one owner and one write and is
     * therefore observationally the native slot it is emitted as (the
     * module-scope function declaration binding is that case: a created
     * closure passes no capture list entry for it).
     */
    public static boolean stagedCellKind(LoweredModuleUnit unit,
                                         deal.semantic.ir.BindingId binding,
                                         deal.semantic.ir.BindingCellKind kind) {
        if (kind == deal.semantic.ir.BindingCellKind.DIRECT) {
            return true;
        }
        if (reassigned(unit, binding)) {
            return false;
        }
        for (SemanticOp op : unit.ops()) {
            if (op.payload() instanceof KindPayload.ClosureNewPayload closure
                    && closure.captures().stream()
                        .anyMatch(capture -> capture.binding().equals(binding)
                            && (capture.generation() != 0
                                || !moduleScoped(unit, binding)))) {
                return false;
            }
        }
        return true;
    }

    private static Optional<String> bindingReason(LoweredModuleUnit unit,
                                                 deal.semantic.ir.BindingId binding,
                                                 long generation) {
        // The incarnation's own decision belongs to its BINDING_ALLOC (one
        // op per incarnation): a shared cell outside the staged rule is
        // rejected there, with that op's origin, before any read or write.
        return Optional.empty();
    }

    private static Optional<String> callReason(LoweredModuleUnit unit,
                                               SemanticOp op,
                                               KindPayload.CallPayload call) {
        if (callMode(call.mode()) == REJECTED) {
            return Optional.of(reject(op, "CALL mode " + call.mode()));
        }
        if (!staticSignature(call.signature())) {
            return Optional.of(reject(op, "CALL signature '"
                + call.signature().canonicalSpecText() + "'"));
        }
        if (call.callee() instanceof KindPayload.CallCallee.Indirect) {
            return Optional.of(reject(op, "an indirect CALL"));
        }
        if (call.callee() instanceof KindPayload.CallCallee.Dynamic) {
            return Optional.of(reject(op, "a dynamic CALL"));
        }
        if (call.callee() instanceof KindPayload.CallCallee.Static callee) {
            return switch (callee.binding()) {
                case deal.semantic.ir.FunctionExecutionBinding.LoweredBody ignored ->
                    Optional.empty();
                case deal.semantic.ir.FunctionExecutionBinding.ExternalFunction ignored ->
                    call.externalEntryRef() != null
                        ? Optional.empty()
                        : Optional.of(reject(op, "an EXTERNAL CALL without its"
                            + " external-entry record"));
                case deal.semantic.ir.FunctionExecutionBinding.AdapterBinding ignored ->
                    Optional.of(reject(op, "a CALL of an adapter"));
                case deal.semantic.ir.FunctionExecutionBinding.HostFunction ignored ->
                    Optional.of(reject(op, "a CALL of a host function"));
                case deal.semantic.ir.FunctionExecutionBinding.HostFunctionValue ignored ->
                    Optional.of(reject(op, "a CALL of a host function value"));
                case deal.semantic.ir.FunctionExecutionBinding.DynamicFunctionValue ignored ->
                    Optional.of(reject(op, "a CALL of a dynamic function value"));
            };
        }
        return Optional.of(reject(op, "the CALL callee shape"));
    }

    private static Optional<String> closureReason(LoweredModuleUnit unit,
                                                 SemanticOp op,
                                                 KindPayload.ClosureNewPayload closure) {
        Optional<String> captureReason = closureCaptureReason(unit, closure);
        if (captureReason.isPresent()) {
            return Optional.of(reject(op, captureReason.get()));
        }
        if (!staticSignature(closure.signature())) {
            return Optional.of(reject(op, "the closure signature '"
                + closure.signature().canonicalSpecText() + "'"));
        }
        LoweredFunction function = unit.functions().get(closure.function());
        if (function == null) {
            return Optional.of(reject(op, "a closure of a function outside the"
                + " emitting unit"));
        }
        return Optional.empty();
    }

    private static Optional<String> boundaryReason(LoweredModuleUnit unit,
                                                   SemanticOp op,
                                                   KindPayload.BoundaryPayload boundary) {
        if (boundaryKind(boundary.kind()) == REJECTED) {
            return Optional.of(reject(op, "BOUNDARY " + boundary.kind()));
        }
        if (!LlvmTypeMapper.isStagedRepresentation(boundary.descriptor())) {
            return Optional.of(reject(op, "BOUNDARY " + boundary.kind()
                + " over " + LlvmTypeMapper.unsupported(boundary.descriptor())));
        }
        RuntimeDescriptor actual = loweredTypeOf(unit, boundary.input());
        if (actual == null || !actual.equals(boundary.descriptor())) {
            return Optional.of(reject(op, "BOUNDARY " + boundary.kind()
                + " whose static operand type "
                + (actual == null ? "is unknown" : "is '"
                    + actual.canonicalSpecText() + "'")
                + " does not prove the declared '"
                + boundary.descriptor().canonicalSpecText() + "'"));
        }
        return Optional.empty();
    }

    /**
     * Whether one binding's allocation lives in the unit's module-init block:
     * its capture by a closure is the static module-level slot, so a closure
     * that only captures such immutable bindings needs no environment.
     */
    static boolean moduleScoped(LoweredModuleUnit unit,
                               deal.semantic.ir.BindingId binding) {
        deal.semantic.ir.BlockId initBlock = unit.moduleInit().initBlock();
        for (SemanticOp op : unit.ops()) {
            if (op.payload() instanceof KindPayload.BindingAllocPayload alloc
                    && alloc.binding().equals(binding)) {
                return alloc.scope().equals(initBlock);
            }
        }
        return false;
    }

    /** Whether one binding is target of a {@code BINDING_STORE}. */
    static boolean reassigned(LoweredModuleUnit unit,
                              deal.semantic.ir.BindingId binding) {
        for (SemanticOp op : unit.ops()) {
            if (op.payload() instanceof KindPayload.BindingStorePayload store
                    && store.binding().equals(binding)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The reason a closure's capture list leaves the staged subset: a
     * captured binding needs a closure environment unless it is the
     * module-scope, immutable binding whose one static slot is its cell (C5's
     * one-cell-per-generation identity is the same slot for every
     * observation).
     */
    static Optional<String> closureCaptureReason(LoweredModuleUnit unit,
                                                 KindPayload.ClosureNewPayload closure) {
        for (deal.semantic.ir.BindingGeneration capture : closure.captures()) {
            if (capture.generation() != 0
                    || !moduleScoped(unit, capture.binding())
                    || reassigned(unit, capture.binding())) {
                return Optional.of("a closure capturing the per-activation or"
                    + " reassigned binding " + capture.binding());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> exportPublishReason(LoweredModuleUnit unit,
                                                        SemanticOp op,
                                                        KindPayload.ExportPublishPayload published) {
        SemanticOp producer = producerOf(unit, published.value());
        if (producer == null
                || producer.kind() != SemanticOpKind.CLOSURE_NEW
                || !(producer.payload() instanceof KindPayload.ClosureNewPayload closure)) {
            return Optional.of(reject(op, "EXPORT_PUBLISH of a value that is no"
                + " closure of a lowered function"));
        }
        Optional<String> captureReason = closureCaptureReason(unit, closure);
        if (captureReason.isPresent()) {
            return Optional.of(reject(op, "EXPORT_PUBLISH of " + captureReason.get()));
        }
        LoweredFunction function = unit.functions().get(closure.function());
        if (function == null) {
            return Optional.of(reject(op, "EXPORT_PUBLISH of a function outside"
                + " the emitting unit"));
        }
        RuntimeDescriptor.Func signature = function.descriptor();
        if (!signature.paramTypes().isEmpty()) {
            return Optional.of(reject(op, "EXPORT_PUBLISH of the non-zero-arity"
                + " export '" + published.name() + "' ('"
                + signature.canonicalSpecText() + "'); the entry surface's"
                + " invocation is the zero-arity one"));
        }
        if (signature.isAsync()) {
            return Optional.of(reject(op, "EXPORT_PUBLISH of the async export '"
                + published.name() + "'"));
        }
        if (!(LlvmTypeMapper.isStaticSingleton(signature.returnType())
                || signature.returnType() instanceof RuntimeDescriptor.Void)) {
            return Optional.of(reject(op, "EXPORT_PUBLISH of the export '"
                + published.name() + "' whose result '"
                + signature.returnType().canonicalSpecText()
                + "' has no zero-arity outcome shape"));
        }
        return Optional.empty();
    }

    private static Optional<String> representationReason(SemanticOp op,
                                                         String role,
                                                         SemanticValue value) {
        if (!(value instanceof ValueId)) {
            return Optional.of(reject(op, role + " is no value"));
        }
        return Optional.empty();
    }

    /**
     * The lowered static type of one value inside one unit: its producing
     * op's result type, or the load's result type. Unknown values answer
     * {@code null} (the caller rejects).
     */
    public static RuntimeDescriptor loweredTypeOf(LoweredModuleUnit unit,
                                                  ValueId value) {
        for (SemanticOp candidate : unit.ops()) {
            if (candidate.result() instanceof ValueId result
                    && result.equals(value)) {
                return candidate.resultType() instanceof RuntimeDescriptor descriptor
                    ? descriptor : null;
            }
        }
        return null;
    }

    /** The producing op of one value inside one unit, or {@code null}. */
    public static SemanticOp producerOf(LoweredModuleUnit unit, ValueId value) {
        for (SemanticOp candidate : unit.ops()) {
            if (candidate.result() instanceof ValueId result
                    && result.equals(value)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Whether a signature is inside the staged native set: every parameter
     * and the result carry a staged representation (or the result is a static
     * singleton), and the call is synchronous.
     */
    public static boolean staticSignature(RuntimeDescriptor.Func signature) {
        Objects.requireNonNull(signature, "signature must not be null");
        if (signature.isAsync()) {
            return false;
        }
        for (RuntimeDescriptor parameter : signature.paramTypes()) {
            if (!(parameter instanceof RuntimeDescriptor.Number
                    || parameter instanceof RuntimeDescriptor.Boolean)) {
                return false;
            }
        }
        RuntimeDescriptor result = signature.returnType();
        return result instanceof RuntimeDescriptor.Number
            || result instanceof RuntimeDescriptor.Boolean
            || result instanceof RuntimeDescriptor.Void
            || LlvmTypeMapper.isStaticSingleton(result);
    }

    private static String reject(SemanticOp op, String subject) {
        return REJECTION_LEAD + ": " + subject;
    }

    // =========================================================================
    // The exhaustive fact enumeration
    // =========================================================================

    /**
     * Every closed fact of the merged semantic IR with its classification:
     * the operation kinds, the payload variants, both selector families, the
     * boundary kinds, the stdlib operations, the failure arms, the control
     * selectors, the call modes, the assignment/delete target kinds and the
     * module-import kinds (the host-import fact).
     */
    public static List<Fact> facts() {
        List<Fact> facts = new ArrayList<>();
        for (SemanticOpKind kind : SemanticOpKind.values()) {
            facts.add(new Fact("SemanticOpKind", kind.name(), opKind(kind)));
        }
        for (Class<?> variant : KindPayload.class.getPermittedSubclasses()) {
            facts.add(new Fact("KindPayload", variant.getSimpleName(),
                payload(variant)));
        }
        for (BinarySelector selector : BinarySelector.values()) {
            facts.add(new Fact("BinarySelector", selector.name(),
                binarySelector(selector)));
        }
        for (UnarySelector selector : UnarySelector.values()) {
            facts.add(new Fact("UnarySelector", selector.name(),
                unarySelector(selector)));
        }
        for (BoundaryKind kind : BoundaryKind.values()) {
            facts.add(new Fact("BoundaryKind", kind.name(), boundaryKind(kind)));
        }
        for (StdlibFunctionId function : StdlibFunctionId.values()) {
            facts.add(new Fact("StdlibFunctionId", function.name(),
                stdlib(function)));
        }
        for (FailureArmId arm : FailureArmId.values()) {
            facts.add(new Fact("FailureArmId", arm.name(), failureArm(arm)));
        }
        for (ControlSelector selector : ControlSelector.values()) {
            facts.add(new Fact("ControlSelector", selector.name(),
                controlSelector(selector)));
        }
        for (CallMode mode : CallMode.values()) {
            facts.add(new Fact("CallMode", mode.name(), callMode(mode)));
        }
        for (AssignTargetKind kind : AssignTargetKind.values()) {
            facts.add(new Fact("AssignTargetKind", kind.name(),
                assignTarget(kind)));
        }
        for (DeleteTargetKind kind : DeleteTargetKind.values()) {
            facts.add(new Fact("DeleteTargetKind", kind.name(),
                deleteTarget(kind)));
        }
        for (ModuleImportKind kind : ModuleImportKind.values()) {
            facts.add(new Fact("ModuleImportKind", kind.name(),
                importKind(kind)));
        }
        return List.copyOf(facts);
    }
}
