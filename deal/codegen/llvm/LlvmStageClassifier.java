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
import deal.semantic.ir.NullableSide;
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
                 BREAK, CONTINUE, DISCARD, TRY_CATCH, THROW, MODULE_INIT,
                 MODULE_IMPORT, EXPORT_READ, EXPORT_PUBLISH, ENTRY_INVOKE,
                 STRING_CONCAT, ARRAY_NEW, TABLE_NEW, ARRAY_LENGTH, MEMBER_READ,
                 MEMBER_WRITE, MEMBER_DELETE, INDEX_NORMALIZE, INDEX_READ,
                 INDEX_WRITE, INDEX_DELETE, HAS_FIELD, FOR_EACH, CLASS_NEW,
                 FIELD_READ, FIELD_WRITE, FIELD_DELETE, JSON_FROM_CLASS,
                 JSON_TO_CLASS -> STAGED;
            case BOUNDARY, DELETE -> STATIC_ONLY;
            case OPTIONAL_READ, RECURSIVE_GROUP_INIT, FUNCTION_ADAPT,
                 EXTERNAL_ENTRY, CALLBACK_INVOKE, STDLIB_CALL, ASYNC_START,
                 AWAIT, CLASS_FACTORY -> REJECTED;
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
        map.put(KindPayload.AssignPayload.class, STAGED);
        map.put(KindPayload.CallPayload.class, STATIC_ONLY);
        map.put(KindPayload.ClosureNewPayload.class, STATIC_ONLY);
        map.put(KindPayload.BranchPayload.class, STAGED);
        map.put(KindPayload.LoopPayload.class, STAGED);
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
        map.put(KindPayload.StringConcatPayload.class, STAGED);
        map.put(KindPayload.ArrayNewPayload.class, STAGED);
        map.put(KindPayload.TableNewPayload.class, STAGED);
        map.put(KindPayload.ArrayLengthPayload.class, STAGED);
        map.put(KindPayload.MemberReadPayload.class, STAGED);
        map.put(KindPayload.MemberWritePayload.class, STAGED);
        map.put(KindPayload.MemberDeletePayload.class, STAGED);
        map.put(KindPayload.IndexNormalizePayload.class, STAGED);
        map.put(KindPayload.IndexReadPayload.class, STATIC_ONLY);
        map.put(KindPayload.IndexWritePayload.class, STATIC_ONLY);
        map.put(KindPayload.IndexDeletePayload.class, STAGED);
        map.put(KindPayload.OptionalReadPayload.class, REJECTED);
        map.put(KindPayload.HasFieldPayload.class, STAGED);
        map.put(KindPayload.RecursiveGroupInitPayload.class, REJECTED);
        map.put(KindPayload.FunctionAdaptPayload.class, REJECTED);
        map.put(KindPayload.DeletePayload.class, STATIC_ONLY);
        map.put(KindPayload.ExternalEntryPayload.class, REJECTED);
        map.put(KindPayload.CallbackInvokePayload.class, REJECTED);
        map.put(KindPayload.StdlibCallPayload.class, REJECTED);
        map.put(KindPayload.AsyncStartPayload.class, REJECTED);
        map.put(KindPayload.AwaitPayload.class, REJECTED);
        map.put(KindPayload.ForEachPayload.class, STAGED);
        map.put(KindPayload.TryCatchPayload.class, STAGED);
        map.put(KindPayload.ThrowPayload.class, STAGED);
        map.put(KindPayload.ClassNewPayload.class, STATIC_ONLY);
        map.put(KindPayload.ClassFactoryPayload.class, REJECTED);
        map.put(KindPayload.FieldReadPayload.class, STAGED);
        map.put(KindPayload.FieldWritePayload.class, STAGED);
        map.put(KindPayload.FieldDeletePayload.class, STATIC_ONLY);
        map.put(KindPayload.JsonFromClassPayload.class, STAGED);
        map.put(KindPayload.JsonToClassPayload.class, STAGED);
        return Map.copyOf(map);
    }

    /**
     * The decision of one binary selector: every member of the merged v1.5
     * family is rendered by exactly one arm of
     * {@link LlvmSelectorArms#binary(BinarySelector)} — the binary64
     * arithmetic forms, the IEEE predicates, the one string-comparison
     * authority, the boolean bytes, the distinct nullish singleton/tag tests,
     * the closed data relation and managed reference identity (C6). The
     * selector's own operand representations are checked per operation.
     */
    public static StageSupport binarySelector(BinarySelector selector) {
        Objects.requireNonNull(selector, "selector must not be null");
        LlvmSelectorArms.binary(selector);
        return STAGED;
    }

    /**
     * The decision of one unary selector (C6): {@code BOOL_NOT},
     * {@code NUMBER_NEG}, {@code TYPEOF} and the nominal {@code INSTANCEOF}
     * tag test each have exactly one arm. The {@code INSTANCEOF} target is
     * judged per operation: the builtin {@code Error} identity is the one
     * class atom the persistent datum carries without an emitted class
     * layout, so every other target belongs to the data/class stage.
     */
    public static StageSupport unarySelector(UnarySelector selector) {
        Objects.requireNonNull(selector, "selector must not be null");
        LlvmSelectorArms.unary(selector);
        return STAGED;
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
                 NARROWED_READ, CONTEXTUAL_TABLE_READ, OPTIONAL_FIELD_READ,
                 CLASS_FIELD_ASSIGNMENT, ARRAY_ELEMENT_ASSIGNMENT,
                 ARRAY_ELEMENT_READ, ARRAY_ELEMENT_DELETE,
                 ARRAY_LITERAL_ELEMENT, CLASS_LITERAL_FIELD,
                 UNTYPED_CLASS_INPUT, JSON_FROM_FIELD, JSON_TO_FIELD -> STATIC_ONLY;
            case ASYNC_COMPLETION, HOST_TO_DEAL, DEAL_TO_HOST,
                 STDLIB_PARAMETER, STDLIB_RETURN -> REJECTED;
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

    /**
     * The decision of one delete target kind: a record member (and an optional
     * class field) is the presence-metadata delete of R2; an array element has
     * no delete at all (spec v1.5 rejects it in the frontend).
     */
    public static StageSupport deleteTargetKind(DeleteTargetKind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        return switch (kind) {
            case TABLE_SLOT, CLASS_FIELD -> STAGED;
            case ARRAY_SLOT -> REJECTED;
        };
    }

    /** The decision of one control selector. */
    public static StageSupport controlSelector(ControlSelector selector) {
        Objects.requireNonNull(selector, "selector must not be null");
        return switch (selector) {
            case IF, WHILE -> STAGED;
            case LOGICAL_AND, LOGICAL_OR -> STATIC_ONLY;
            case TRY_CATCH -> STAGED;
        };
    }

    /** The decision of one call mode. */
    public static StageSupport callMode(CallMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        return switch (mode) {
            // An INDIRECT call is staged only when its callee identity resolves
            // to an uncaptured lowered body (the call's own decision), so the
            // value is that function's own code pointer (C5).
            case DIRECT, EXTERNAL, INDIRECT -> STATIC_ONLY;
            case HOST -> REJECTED;
        };
    }

    /** The decision of one assignment target kind (only a binding assignment). */
    public static StageSupport assignTarget(AssignTargetKind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        return switch (kind) {
            case VARIABLE, TABLE_SLOT, ARRAY_SLOT, CLASS_FIELD -> STAGED;
        };
    }

    /** The decision of one delete target kind (one authority: {@link #deleteTargetKind}). */
    public static StageSupport deleteTarget(DeleteTargetKind kind) {
        return deleteTargetKind(kind);
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
                unaryReason(op, unary);
            case KindPayload.BinaryPayload binary ->
                binaryReason(unit, op, binary);
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
                    ? Optional.empty()
                    : Optional.of(reject(op, "LOOP " + loop.selector()));
            case KindPayload.TryCatchPayload caught ->
                catchBindingReason(unit, caught.catchBinding());
            case KindPayload.ThrowPayload thrown ->
                thrownValueReason(unit, op, thrown);
            case KindPayload.BoundaryPayload boundary ->
                boundaryReason(unit, op, boundary);
            case KindPayload.ReturnPayload returned ->
                returnedValueReason(unit, op, returned.value());
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
            case KindPayload.OptionalReadPayload ignored ->
                Optional.of(reject(op, "the OPTIONAL_READ envelope of a"
                    + " missing-capable raw record read: a record read is"
                    + " `unknown` and needs an explicit assertion, so no valid"
                    + " v1.5 program reaches this envelope"));
            case KindPayload.StringConcatPayload concat ->
                stringConcatReason(unit, op, concat);
            case KindPayload.ArrayNewPayload array ->
                arrayReason(unit, op, array);
            case KindPayload.TableNewPayload ignored -> Optional.empty();
            case KindPayload.ArrayLengthPayload length ->
                requireStaticType(unit, op, length.arrayValue(),
                    RuntimeDescriptor.Array.class, "an ARRAY_LENGTH receiver");
            case KindPayload.MemberReadPayload read ->
                requireStaticType(unit, op, read.table(),
                    RuntimeDescriptor.Table.class, "a record read receiver");
            case KindPayload.MemberWritePayload write ->
                requireStaticType(unit, op, write.table(),
                    RuntimeDescriptor.Table.class, "a record write receiver");
            case KindPayload.MemberDeletePayload remove ->
                requireStaticType(unit, op, remove.table(),
                    RuntimeDescriptor.Table.class, "a record delete receiver");
            case KindPayload.IndexNormalizePayload normalize ->
                normalizeReason(unit, op, normalize);
            case KindPayload.IndexReadPayload read -> indexReason(unit, op,
                read.container(), read.slot(), "an INDEX_READ");
            case KindPayload.IndexWritePayload write -> indexReason(unit, op,
                write.container(), write.slot(), "an INDEX_WRITE");
            case KindPayload.IndexDeletePayload remove -> indexReason(unit, op,
                remove.container(), remove.slot(), "an INDEX_DELETE");
            case KindPayload.HasFieldPayload field ->
                hasFieldReason(unit, op, field);
            case KindPayload.DeletePayload remove ->
                deleteTargetKind(remove.targetKind()) == STAGED
                    ? Optional.empty()
                    : Optional.of(reject(op, "DELETE " + remove.targetKind()));
            case KindPayload.ForEachPayload each ->
                forEachReason(unit, op, each);
            case KindPayload.ClassNewPayload created ->
                classNewReason(unit, op, created);
            case KindPayload.FieldReadPayload read -> Optional.empty();
            case KindPayload.FieldWritePayload write -> Optional.empty();
            case KindPayload.FieldDeletePayload remove -> Optional.empty();
            case KindPayload.JsonFromClassPayload ignored -> Optional.empty();
            case KindPayload.JsonToClassPayload ignored -> Optional.empty();
            default -> Optional.of(reject(op, op.payload()
                .getClass().getSimpleName()));
        };
    }

    /** The recorded static type of one operand, or a rejection. */
    private static Optional<String> requireStaticType(LoweredModuleUnit unit,
                                                      SemanticOp op,
                                                      ValueId value,
                                                      Class<?> expected,
                                                      String role) {
        RuntimeDescriptor descriptor = loweredTypeOf(unit, value);
        if (descriptor == null) {
            return Optional.of(reject(op, role + " whose static type is unknown"));
        }
        if (!expected.isInstance(descriptor)) {
            return Optional.of(reject(op, role + " of the static type '"
                + descriptor.canonicalSpecText() + "'"));
        }
        return Optional.empty();
    }

    /** Every template fragment is a string (C3, R2 string templates). */
    private static Optional<String> stringConcatReason(LoweredModuleUnit unit,
                                                       SemanticOp op,
                                                       KindPayload.StringConcatPayload concat) {
        for (ValueId fragment : concat.fragments()) {
            RuntimeDescriptor descriptor = loweredTypeOf(unit, fragment);
            if (!(descriptor instanceof RuntimeDescriptor.String)) {
                return Optional.of(reject(op, "a string-template fragment of"
                    + " the static type '" + (descriptor == null ? "unknown"
                        : descriptor.canonicalSpecText()) + "'"));
            }
        }
        return Optional.empty();
    }

    /** Whether one element descriptor has emitted element storage (C3). */
    static boolean elementStorageSupported(RuntimeDescriptor element) {
        // A `null`/`undefined` position stores the distinct runtime singleton
        // (C3), so it has emitted element storage like every other data type.
        if (element instanceof RuntimeDescriptor.Number
                || element instanceof RuntimeDescriptor.Boolean
                || element instanceof RuntimeDescriptor.Unknown
                || element instanceof RuntimeDescriptor.Null
                || element instanceof RuntimeDescriptor.Undefined) {
            return true;
        }
        if (LlvmTypeMapper.isManagedPointer(element)) {
            return true;
        }
        return element instanceof RuntimeDescriptor.Nullable nullable
            && LlvmTypeMapper.representationOf(nullable).isPresent();
    }

    /** Every array-literal element is admitted by the declared element type. */
    private static Optional<String> arrayReason(LoweredModuleUnit unit,
                                                SemanticOp op,
                                                KindPayload.ArrayNewPayload array) {
        if (!elementStorageSupported(array.elementDescriptor())) {
            return Optional.of(reject(op, "an array literal of the element type '"
                + array.elementDescriptor().canonicalSpecText() + "'"));
        }
        if (array.values().size() != array.elementBoundaryOpIds().size()) {
            return Optional.of(reject(op, "an array literal without one element"
                + " boundary per element"));
        }
        for (ValueId value : array.values()) {
            RuntimeDescriptor descriptor = loweredTypeOf(unit, value);
            if (descriptor == null || !(descriptor.equals(array.elementDescriptor())
                    || LlvmTypeMapper.convertible(array.elementDescriptor(),
                        descriptor))) {
                return Optional.of(reject(op, "an array element of the static"
                    + " type '" + (descriptor == null ? "unknown"
                        : descriptor.canonicalSpecText())
                    + "' in the element position '"
                    + array.elementDescriptor().canonicalSpecText() + "'"));
            }
        }
        return Optional.empty();
    }

    /** The normalize mode's position type (R2: a number index or a string key). */
    private static Optional<String> normalizeReason(LoweredModuleUnit unit,
                                                    SemanticOp op,
                                                    KindPayload.IndexNormalizePayload normalize) {
        RuntimeDescriptor raw = loweredTypeOf(unit, normalize.rawKey());
        Class<?> expected = switch (normalize.mode()) {
            case ARRAY_READ, ARRAY_WRITE -> RuntimeDescriptor.Number.class;
            case TABLE_READ, TABLE_WRITE -> RuntimeDescriptor.String.class;
        };
        if (raw == null || !expected.isInstance(raw)) {
            return Optional.of(reject(op, "an INDEX_NORMALIZE " + normalize.mode()
                + " of the static type '" + (raw == null ? "unknown"
                    : raw.canonicalSpecText()) + "'"));
        }
        return Optional.empty();
    }

    /** One container access over its recorded receiver and slot (R2). */
    private static Optional<String> indexReason(LoweredModuleUnit unit,
                                                SemanticOp op,
                                                ValueId container,
                                                ValueId slot,
                                                String role) {
        RuntimeDescriptor receiver = loweredTypeOf(unit, container);
        RuntimeDescriptor position = loweredTypeOf(unit, slot);
        if (receiver instanceof RuntimeDescriptor.Array) {
            if (position instanceof RuntimeDescriptor.Number) {
                return Optional.empty();
            }
            return Optional.of(reject(op, role + " of an array with the position"
                + " type '" + (position == null ? "unknown"
                    : position.canonicalSpecText()) + "'"));
        }
        if (receiver instanceof RuntimeDescriptor.Table) {
            if (position instanceof RuntimeDescriptor.String) {
                return Optional.empty();
            }
            return Optional.of(reject(op, role + " of a record with the key type '"
                + (position == null ? "unknown" : position.canonicalSpecText())
                + "'"));
        }
        return Optional.of(reject(op, role + " of the receiver type '"
            + (receiver == null ? "unknown" : receiver.canonicalSpecText()) + "'"));
    }

    /** {@code Object.hasOwn} takes a record or a class instance (R2). */
    private static Optional<String> hasFieldReason(LoweredModuleUnit unit,
                                                   SemanticOp op,
                                                   KindPayload.HasFieldPayload field) {
        RuntimeDescriptor receiver = loweredTypeOf(unit, field.receiver());
        if (receiver instanceof RuntimeDescriptor.Table) {
            if (field.dynamicKey() == null) {
                return Optional.empty();
            }
            RuntimeDescriptor key = loweredTypeOf(unit, field.dynamicKey());
            return key instanceof RuntimeDescriptor.String
                ? Optional.empty()
                : Optional.of(reject(op, "an Object.hasOwn with the key type '"
                    + (key == null ? "unknown" : key.canonicalSpecText()) + "'"));
        }
        if (receiver instanceof RuntimeDescriptor.Class) {
            return field.dynamicKey() == null
                ? Optional.empty()
                : Optional.of(reject(op, "an Object.hasOwn of a class instance"
                    + " with a dynamic key (a class takes a literal declared"
                    + " field name)"));
        }
        return Optional.of(reject(op, "an Object.hasOwn receiver of the type '"
            + (receiver == null ? "unknown" : receiver.canonicalSpecText()) + "'"));
    }

    /** One for-of traversal over its recorded iteration mode (R2). */
    private static Optional<String> forEachReason(LoweredModuleUnit unit,
                                                  SemanticOp op,
                                                  KindPayload.ForEachPayload each) {
        RuntimeDescriptor iterable = loweredTypeOf(unit, each.iterable());
        if (each.mode() == deal.semantic.ir.IterationMode.STRING_SCALARS) {
            return iterable instanceof RuntimeDescriptor.String
                ? Optional.empty()
                : Optional.of(reject(op, "a for-of over the scalar positions of"
                    + " the type '" + (iterable == null ? "unknown"
                        : iterable.canonicalSpecText()) + "'"));
        }
        if (!(iterable instanceof RuntimeDescriptor.Array arrayType)) {
            return Optional.of(reject(op, "a for-of over the type '"
                + (iterable == null ? "unknown" : iterable.canonicalSpecText())
                + "'"));
        }
        return elementStorageSupported(arrayType.element())
            ? Optional.empty()
            : Optional.of(reject(op, "a for-of over the element type '"
                + arrayType.element().canonicalSpecText() + "'"));
    }

    /** One class construction through its recorded provider (R2). */
    private static Optional<String> classNewReason(LoweredModuleUnit unit,
                                                   SemanticOp op,
                                                   KindPayload.ClassNewPayload created) {
        switch (created.constructionProvider()) {
            case BUILTIN_ERROR -> {
                for (String field : List.of("code", "message")) {
                    boolean supplied = created.providedFields().stream()
                        .anyMatch(provided -> provided.name().equals(field));
                    if (!supplied) {
                        return Optional.of(reject(op, "a builtin Error"
                            + " construction without its '" + field + "' field"));
                    }
                }
                return Optional.empty();
            }
            case LOCAL -> {
                for (deal.semantic.ir.ClassLayout.FieldLayout field
                        : created.layout().fields()) {
                    boolean supplied = created.providedFields().stream()
                        .anyMatch(provided -> provided.name().equals(field.name()));
                    if (field.required() && !supplied) {
                        return Optional.of(reject(op, "a class construction"
                            + " without the required field '" + field.name() + "'"));
                    }
                    // Every declared slot of the emitted layout needs emitted
                    // storage: a supplied value to validate and store, or (for
                    // an omitted optional field) the declared typed slot the
                    // plan record publishes. The rejection names this op's own
                    // origin rather than a layout assembly site.
                    if (!elementStorageSupported(field.descriptor())
                            && !(field.descriptor() instanceof RuntimeDescriptor.Void)) {
                        return Optional.of(reject(op, "a class construction with"
                            + " the unsupported field type '"
                            + field.descriptor().canonicalSpecText() + "'"));
                    }
                }
                return Optional.empty();
            }
            default -> {
                return Optional.of(reject(op, "the construction provider "
                    + created.constructionProvider()
                    + ": a shared-factory, retained-ABI or host-declared class"
                    + " construction belongs to the module-export and host"
                    + " stages"));
            }
        }
    }

    private static Optional<String> scalarReason(SemanticOp op,
                                                 ScalarValue value) {
        return switch (value) {
            case ScalarValue.Number ignored -> Optional.empty();
            case ScalarValue.Boolean ignored -> Optional.empty();
            case ScalarValue.Null ignored -> Optional.empty();
            case ScalarValue.Undefined ignored -> Optional.empty();
            case ScalarValue.String ignored -> Optional.empty();
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
        if (call.callee() instanceof KindPayload.CallCallee.Indirect indirect) {
            return indirectCalleeReason(unit, op, indirect);
        }
        if (call.callee() instanceof KindPayload.CallCallee.Dynamic) {
            return Optional.of(reject(op, "a dynamic CALL whose callee is"
                + " resolved at execution through the managed function carrier"));
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

    /**
     * The decision of one statically registered indirect callee: the callee
     * value's allocation identity resolves to a lowered body whose closure
     * captures nothing, so the value is that function's own code pointer and a
     * direct call is the recorded dispatch (C5). A captured body needs the
     * managed {@code deal_fn*} environment of the closure stage.
     */
    private static Optional<String> indirectCalleeReason(LoweredModuleUnit unit,
                                                         SemanticOp op,
                                                         KindPayload.CallCallee.Indirect indirect) {
        deal.semantic.ir.FunctionExecutionBinding binding =
            bindingOfIdentity(unit, indirect.callee());
        if (!(binding instanceof deal.semantic.ir.FunctionExecutionBinding
                .LoweredBody body)) {
            return Optional.of(reject(op, "an indirect CALL whose callee"
                + " identity resolves no lowered body"));
        }
        for (SemanticOp candidate : unit.ops()) {
            if (candidate.payload() instanceof KindPayload.ClosureNewPayload closure
                    && closure.function().equals(body.functionId())) {
                // A capture of a module-scope immutable binding is that
                // binding's one static slot (the staged closure rule), so the
                // value is still the function's own code pointer; a
                // per-activation or reassigned capture needs the managed
                // environment of the closure stage.
                Optional<String> captureReason =
                    closureCaptureReason(unit, closure);
                if (captureReason.isPresent()) {
                    return Optional.of(reject(op, "an indirect CALL of "
                        + captureReason.get()));
                }
            }
        }
        return Optional.empty();
    }

    /** The registered binding of one identity anywhere in the emitting unit. */
    private static deal.semantic.ir.FunctionExecutionBinding bindingOfIdentity(
            LoweredModuleUnit unit, ValueId identity) {
        return unit.functionBindings().get(
            new deal.semantic.ir.FunctionAllocationIdentity(identity.id()));
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

    /**
     * The decision of one typed boundary. A boundary whose lowered static
     * operand type already proves the declared descriptor is rendered as the
     * identity of that value (with the C3 representation change of a nullish
     * or {@code unknown} position). An {@code as T} assertion is the one
     * boundary this stage renders as a runtime descriptor check (R4/R5): its
     * declared scalar or builtin-Error descriptor has the arm-driven
     * {@code deal_check_value}/{@code deal_check_*} form, so a mismatch fails
     * at the assertion's own origin with the shared E8001 arm. Every other
     * boundary whose static type does not prove its descriptor belongs to the
     * container, class, host or async stage that owns the checked
     * representation.
     */
    private static Optional<String> boundaryReason(LoweredModuleUnit unit,
                                                   SemanticOp op,
                                                   KindPayload.BoundaryPayload boundary) {
        if (boundaryKind(boundary.kind()) == REJECTED) {
            return Optional.of(reject(op, "BOUNDARY " + boundary.kind()));
        }
        RuntimeDescriptor declared = boundary.descriptor();
        if (LlvmTypeMapper.isStaticSingleton(declared)) {
            // A declared `null`/`undefined` position carries no value slot at
            // all (C4 rebuilds the distinct singleton at the use site), so the
            // boundary is the identity of that singleton and needs no check.
            return Optional.empty();
        }
        if (declared instanceof RuntimeDescriptor.Void) {
            // A result-only `void` position carries no data slot and no
            // check: its completion projects to undefined through the result
            // protocol (C4), never through a value descriptor.
            return Optional.empty();
        }
        RuntimeDescriptor actual = loweredTypeOf(unit, boundary.input());
        if (actual == null) {
            return Optional.of(reject(op, "BOUNDARY " + boundary.kind()
                + " whose static operand type is unknown"));
        }
        if (LlvmTypeMapper.convertible(declared, actual)) {
            if (!LlvmTypeMapper.isStagedRepresentation(declared)) {
                return Optional.of(reject(op, "BOUNDARY " + boundary.kind()
                    + " over " + LlvmTypeMapper.unsupported(declared)));
            }
            return Optional.empty();
        }
        if (boundary.kind() != BoundaryKind.ASSERTION) {
            return Optional.of(reject(op, "BOUNDARY " + boundary.kind()
                + " whose static operand type '" + actual.canonicalSpecText()
                + "' does not prove the declared '"
                + declared.canonicalSpecText() + "'"));
        }
        if (!(op.result() instanceof ValueId)) {
            return Optional.of(reject(op, "an assertion without its checked"
                + " result value"));
        }
        if (LlvmTypeMapper.isStaticSingleton(actual)) {
            // The datum of a singleton operand is buildable.
        } else if (!LlvmTypeMapper.representationOf(actual).isPresent()) {
            return Optional.of(reject(op, "an assertion of "
                + LlvmTypeMapper.unsupported(actual)));
        }
        if (!LlvmDataValues.hasDatumForm(actual)) {
            return Optional.of(reject(op, "an assertion of a value without a"
                + " persistent data form: " + actual.canonicalSpecText()));
        }
        return switch (declared) {
            case RuntimeDescriptor.Number _,
                 RuntimeDescriptor.Boolean _,
                 RuntimeDescriptor.String _,
                 RuntimeDescriptor.Table _,
                 RuntimeDescriptor.Class _,
                 RuntimeDescriptor.Array _ -> Optional.empty();
            default -> Optional.of(reject(op, "the assertion to '"
                + declared.canonicalSpecText() + "'"));
        };
    }

    /** The decision of one unary operation (C6). */
    private static Optional<String> unaryReason(SemanticOp op,
                                                KindPayload.UnaryPayload unary) {
        RuntimeDescriptor operand = op.operandTypes().get(0);
        return switch (unary.selector()) {
            case BOOL_NOT -> operand instanceof RuntimeDescriptor.Boolean
                ? Optional.empty()
                : Optional.of(reject(op, "UNARY BOOL_NOT over '"
                    + operand.canonicalSpecText() + "'"));
            case NUMBER_NEG -> operand instanceof RuntimeDescriptor.Number
                ? Optional.empty()
                : Optional.of(reject(op, "UNARY NUMBER_NEG over '"
                    + operand.canonicalSpecText() + "'"));
            case TYPEOF -> LlvmTypeMapper.isStaticSingleton(operand)
                || LlvmTypeMapper.representationOf(operand).isPresent()
                ? Optional.empty()
                : Optional.of(reject(op, "UNARY TYPEOF over "
                    + LlvmTypeMapper.unsupported(operand)));
            case INSTANCEOF -> instanceOfReason(op, unary, operand);
        };
    }

    /**
     * The decision of one {@code instanceof}. The builtin {@code Error}
     * identity is the one nominal tag the persistent {@code unknown} datum
     * carries without an emitted class layout; a statically class-typed
     * operand answers by nominal identity, and every other class target is
     * the data/class stage's (its instance layout is not part of this stage).
     */
    private static Optional<String> instanceOfReason(SemanticOp op,
                                                     KindPayload.UnaryPayload unary,
                                                     RuntimeDescriptor operand) {
        RuntimeDescriptor target = unary.target();
        if (!(target instanceof RuntimeDescriptor.Class)) {
            return Optional.of(reject(op, "INSTANCEOF without a class target"));
        }
        // A class-typed operand answers by nominal identity, and an `unknown`
        // datum by the class atom its instance carries (R2: a datum is never
        // retagged); any other data value is never an instance.
        if (operand instanceof RuntimeDescriptor.Class
                || operand instanceof RuntimeDescriptor.Unknown) {
            return Optional.empty();
        }
        if (LlvmTypeMapper.isStaticSingleton(operand)) {
            return Optional.empty();
        }
        return Optional.of(reject(op, "INSTANCEOF over '"
            + operand.canonicalSpecText() + "'"));
    }

    /** The decision of one binary operation over its recorded operands (C6). */
    private static Optional<String> binaryReason(LoweredModuleUnit unit,
                                                 SemanticOp op,
                                                 KindPayload.BinaryPayload binary) {
        RuntimeDescriptor left = op.operandTypes().get(0);
        RuntimeDescriptor right = op.operandTypes().get(1);
        for (RuntimeDescriptor operand : op.operandTypes()) {
            if (LlvmTypeMapper.isStaticSingleton(operand)) {
                continue;
            }
            if (!LlvmTypeMapper.representationOf(operand).isPresent()) {
                return Optional.of(reject(op, "BINARY " + binary.selector()
                    + " over " + LlvmTypeMapper.unsupported(operand)));
            }
        }
        return switch (binary.selector()) {
            case STRING_EQ, STRING_NE, STRING_LT, STRING_LE, STRING_GT, STRING_GE ->
                requireBoth(op, binary, left, right, RuntimeDescriptor.String.INSTANCE);
            case BOOLEAN_EQ, BOOLEAN_NE ->
                requireBoth(op, binary, left, right, RuntimeDescriptor.Boolean.INSTANCE);
            case NULL_EQ, NULL_NE ->
                requireBoth(op, binary, left, right, RuntimeDescriptor.Null.INSTANCE);
            case UNDEFINED_EQ, UNDEFINED_NE ->
                requireBoth(op, binary, left, right, RuntimeDescriptor.Undefined.INSTANCE);
            case UNKNOWN_EQ, UNKNOWN_NE ->
                requireBoth(op, binary, left, right, RuntimeDescriptor.Unknown.INSTANCE);
            case REFERENCE_EQ, REFERENCE_NE -> referenceReason(op, binary, left, right);
            case NULLABLE_EQ, NULLABLE_NE ->
                nullableEqualityReason(op, binary, left, right);
            case NULLABLE_NULL_EQ, NULLABLE_NULL_NE,
                 NULLABLE_UNDEFINED_EQ, NULLABLE_UNDEFINED_NE ->
                nullableTestReason(op, binary, left, right);
            case NUMBER_ADD, NUMBER_SUB, NUMBER_MUL, NUMBER_DIV_IEEE,
                 NUMBER_EQ, NUMBER_NE, NUMBER_LT, NUMBER_LE, NUMBER_GT,
                 NUMBER_GE -> numberReason(op, binary, left, right);
        };
    }

    /** The binary64 family: both operands are numbers (C6). */
    private static Optional<String> numberReason(SemanticOp op,
                                                 KindPayload.BinaryPayload binary,
                                                 RuntimeDescriptor left,
                                                 RuntimeDescriptor right) {
        if (left instanceof RuntimeDescriptor.Number
                && right instanceof RuntimeDescriptor.Number) {
            return Optional.empty();
        }
        return Optional.of(reject(op, "BINARY " + binary.selector() + " over '"
            + left.canonicalSpecText() + "' and '" + right.canonicalSpecText()
            + "'"));
    }

    private static Optional<String> requireBoth(SemanticOp op,
                                                KindPayload.BinaryPayload binary,
                                                RuntimeDescriptor left,
                                                RuntimeDescriptor right,
                                                RuntimeDescriptor expected) {
        if (left.equals(expected) && right.equals(expected)) {
            return Optional.empty();
        }
        return Optional.of(reject(op, "BINARY " + binary.selector() + " over '"
            + left.canonicalSpecText() + "' and '" + right.canonicalSpecText()
            + "'"));
    }

    /**
     * {@code REFERENCE_*} needs the managed identity of the checked reference
     * type. The static function identity is the one reference representation
     * of this stage; array, record and class identity arrive with their own
     * representations in the data/class stage.
     */
    private static Optional<String> referenceReason(SemanticOp op,
                                                    KindPayload.BinaryPayload binary,
                                                    RuntimeDescriptor left,
                                                    RuntimeDescriptor right) {
        if (isReferenceType(left) && left.equals(right)) {
            return Optional.empty();
        }
        return Optional.of(reject(op, "BINARY " + binary.selector()
            + " over the reference types '" + left.canonicalSpecText()
            + "' and '" + right.canonicalSpecText() + "'"));
    }

    /** Whether one descriptor is a checked reference type (R2/C6 identity). */
    private static boolean isReferenceType(RuntimeDescriptor descriptor) {
        return descriptor instanceof RuntimeDescriptor.Array
            || descriptor instanceof RuntimeDescriptor.Table
            || descriptor instanceof RuntimeDescriptor.Class
            || descriptor instanceof RuntimeDescriptor.Func;
    }

    private static Optional<String> nullableEqualityReason(
            SemanticOp op, KindPayload.BinaryPayload binary,
            RuntimeDescriptor left, RuntimeDescriptor right) {
        if (!(left instanceof RuntimeDescriptor.Nullable nullable)
                || !left.equals(right)) {
            return Optional.of(reject(op, "BINARY " + binary.selector()
                + " over '" + left.canonicalSpecText() + "' and '"
                + right.canonicalSpecText() + "'"));
        }
        return baseEqualityReason(op, binary, nullable.inner());
    }

    private static Optional<String> nullableTestReason(
            SemanticOp op, KindPayload.BinaryPayload binary,
            RuntimeDescriptor left, RuntimeDescriptor right) {
        if (binary.side() != NullableSide.LEFT && binary.side() != NullableSide.RIGHT) {
            return Optional.of(reject(op, "BINARY " + binary.selector()
                + " without the named nullable side"));
        }
        RuntimeDescriptor named = binary.side() == NullableSide.LEFT ? left : right;
        if (named instanceof RuntimeDescriptor.Unknown) {
            return Optional.empty();
        }
        if (!(named instanceof RuntimeDescriptor.Nullable nullable)) {
            return Optional.of(reject(op, "BINARY " + binary.selector()
                + " whose named side '" + named.canonicalSpecText()
                + "' is no nullish position"));
        }
        if (binary.innerDescriptor() == null) {
            return Optional.of(reject(op, "BINARY " + binary.selector()
                + " without its inner descriptor"));
        }
        return representationReason(op, nullable);
    }

    private static Optional<String> baseEqualityReason(
            SemanticOp op, KindPayload.BinaryPayload binary,
            RuntimeDescriptor inner) {
        if (binary.innerDescriptor() == null) {
            return Optional.of(reject(op, "BINARY " + binary.selector()
                + " without its inner descriptor"));
        }
        if (inner instanceof RuntimeDescriptor.Number
                || inner instanceof RuntimeDescriptor.Boolean
                || inner instanceof RuntimeDescriptor.String
                || isReferenceType(inner)) {
            return Optional.empty();
        }
        return Optional.of(reject(op, "BINARY " + binary.selector()
            + " over the nullish base '" + inner.canonicalSpecText() + "'"));
    }

    private static Optional<String> representationReason(SemanticOp op,
                                                         RuntimeDescriptor descriptor) {
        if (LlvmTypeMapper.isStaticSingleton(descriptor)
                || LlvmTypeMapper.representationOf(descriptor).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(reject(op, LlvmTypeMapper.unsupported(descriptor)));
    }

    /**
     * The decision of one catch binding: the handler joins with the boxed
     * {@code unknown} datum (C4), so its incarnation may be the per-activation
     * native slot but never an unallocated or captured shared cell of the
     * collector's cell service.
     */
    private static Optional<String> catchBindingReason(LoweredModuleUnit unit,
                                                       deal.semantic.ir.BindingId binding) {
        for (SemanticOp candidate : unit.ops()) {
            if (candidate.payload() instanceof KindPayload.BindingAllocPayload alloc
                    && alloc.binding().equals(binding)) {
                return stagedCellKind(unit, binding, alloc.cellKind())
                    ? Optional.empty()
                    : Optional.of(REJECTION_LEAD + ": a catch binding whose"
                        + " shared cell is captured by a closure");
            }
        }
        return Optional.of(REJECTION_LEAD
            + ": a TRY_CATCH without its catch binding allocation");
    }

    /** The decision of one returned value (C4's value slot). */
    private static Optional<String> returnedValueReason(LoweredModuleUnit unit,
                                                        SemanticOp op,
                                                        ValueId value) {
        RuntimeDescriptor descriptor = loweredTypeOf(unit, value);
        if (descriptor == null) {
            return Optional.of(reject(op, "a RETURN whose value carries no"
                + " descriptor"));
        }
        return representationReason(op, descriptor);
    }

    /** The decision of one thrown Error value ({@code THROW_TRANSFER}). */
    private static Optional<String> thrownValueReason(LoweredModuleUnit unit,
                                                      SemanticOp op,
                                                      KindPayload.ThrowPayload thrown) {
        RuntimeDescriptor descriptor = loweredTypeOf(unit, thrown.errorValue());
        if (descriptor instanceof RuntimeDescriptor.Class classType
                && classType.classId().equals(deal.semantic.ir.ClassId.ERROR)) {
            return Optional.empty();
        }
        return Optional.of(reject(op, "THROW of '"
            + (descriptor == null ? "an unknown type"
                : descriptor.canonicalSpecText())
            + "': the builtin Error instance is the one thrown identity"));
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
        // The record is published for every export of a lowered function; only
        // the zero-arity synchronous value shape is invokable through the
        // entry.h zero-arity invocation, so every other export record carries
        // no code pointer and is reached by a direct call inside the project.
        return Optional.empty();
    }

    /**
     * Whether one published export is invokable through the zero-arity entry
     * surface ({@code deal_export_invoke}, R9): a no-capture closure of a
     * zero-arity, non-async lowered function whose result is a static
     * singleton or {@code void}.
     */
    public static boolean invokableExport(LoweredModuleUnit unit,
                                          KindPayload.ExportPublishPayload published) {
        SemanticOp producer = producerOf(unit, published.value());
        if (producer == null
                || !(producer.payload() instanceof KindPayload.ClosureNewPayload closure)) {
            return false;
        }
        LoweredFunction function = unit.functions().get(closure.function());
        if (function == null) {
            return false;
        }
        RuntimeDescriptor.Func signature = function.descriptor();
        if (!signature.paramTypes().isEmpty() || signature.isAsync()) {
            return false;
        }
        return LlvmTypeMapper.isStaticSingleton(signature.returnType())
            || signature.returnType() instanceof RuntimeDescriptor.Void;
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
            if (!LlvmTypeMapper.representationOf(parameter).isPresent()) {
                return false;
            }
        }
        RuntimeDescriptor result = signature.returnType();
        return result instanceof RuntimeDescriptor.Void
            || LlvmTypeMapper.isStagedRepresentation(result);
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
