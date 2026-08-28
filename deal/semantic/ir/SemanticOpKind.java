package deal.semantic.ir;

/**
 * The closed operation-kind set of {@code deal.semantic-ir/1} (parent
 * "Closed operation kinds and execution contracts"; schema S4).
 *
 * <p>Closed set — exactly the 55 values below in the pinned order; no open
 * or unknown fallback member and no external extension point exist, so a
 * consumer-private operation kind cannot be expressed through typed
 * construction, and an unknown kind in the versioned text protocol fails
 * validation (R-ENUM/R-PRIVATE-STEP). Every kind's mandatory payload shape
 * is exactly the corresponding {@link KindPayload} record
 * ({@link #payloadClass()}); {@link SemanticOp} rejects a kind/payload
 * mismatch at construction. Execution contracts are the construct epics'
 * (ISSUE-0234..0239); the validator accepts only these shapes.</p>
 */
public enum SemanticOpKind {

    CONST,
    UNARY,
    BINARY,
    STRING_CONCAT,
    ARRAY_NEW,
    TABLE_NEW,
    ARRAY_LENGTH,
    MEMBER_READ,
    MEMBER_WRITE,
    MEMBER_DELETE,
    INDEX_NORMALIZE,
    INDEX_READ,
    INDEX_WRITE,
    INDEX_DELETE,
    OPTIONAL_READ,
    HAS_FIELD,
    BOUNDARY,
    BINDING_ALLOC,
    BINDING_INIT,
    BINDING_LOAD,
    BINDING_STORE,
    RECURSIVE_GROUP_INIT,
    CLOSURE_NEW,
    FUNCTION_ADAPT,
    ASSIGN,
    DELETE,
    CALL,
    EXTERNAL_ENTRY,
    CALLBACK_INVOKE,
    INTRINSIC_CALL,
    STDLIB_CALL,
    ASYNC_START,
    AWAIT,
    BRANCH,
    LOOP,
    FOR_EACH,
    TRY_CATCH,
    THROW,
    RETURN,
    BREAK,
    CONTINUE,
    DISCARD,
    CLASS_DEFAULT,
    CLASS_NEW,
    CLASS_FACTORY,
    FIELD_READ,
    FIELD_WRITE,
    FIELD_DELETE,
    JSON_FROM_CLASS,
    JSON_TO_CLASS,
    MODULE_INIT,
    MODULE_IMPORT,
    EXPORT_READ,
    EXPORT_PUBLISH,
    ENTRY_INVOKE;

    /**
     * The exact mandatory {@link KindPayload} shape of this kind. The
     * typed construction surface enforces the match: {@link SemanticOp}
     * rejects an op whose payload is not an instance of this class, so a
     * kind/payload mismatch cannot be expressed through typed
     * construction.
     */
    public Class<? extends KindPayload> payloadClass() {
        return switch (this) {
            case CONST -> KindPayload.ConstPayload.class;
            case UNARY -> KindPayload.UnaryPayload.class;
            case BINARY -> KindPayload.BinaryPayload.class;
            case STRING_CONCAT -> KindPayload.StringConcatPayload.class;
            case ARRAY_NEW -> KindPayload.ArrayNewPayload.class;
            case TABLE_NEW -> KindPayload.TableNewPayload.class;
            case ARRAY_LENGTH -> KindPayload.ArrayLengthPayload.class;
            case MEMBER_READ -> KindPayload.MemberReadPayload.class;
            case MEMBER_WRITE -> KindPayload.MemberWritePayload.class;
            case MEMBER_DELETE -> KindPayload.MemberDeletePayload.class;
            case INDEX_NORMALIZE -> KindPayload.IndexNormalizePayload.class;
            case INDEX_READ -> KindPayload.IndexReadPayload.class;
            case INDEX_WRITE -> KindPayload.IndexWritePayload.class;
            case INDEX_DELETE -> KindPayload.IndexDeletePayload.class;
            case OPTIONAL_READ -> KindPayload.OptionalReadPayload.class;
            case HAS_FIELD -> KindPayload.HasFieldPayload.class;
            case BOUNDARY -> KindPayload.BoundaryPayload.class;
            case BINDING_ALLOC -> KindPayload.BindingAllocPayload.class;
            case BINDING_INIT -> KindPayload.BindingInitPayload.class;
            case BINDING_LOAD -> KindPayload.BindingLoadPayload.class;
            case BINDING_STORE -> KindPayload.BindingStorePayload.class;
            case RECURSIVE_GROUP_INIT -> KindPayload.RecursiveGroupInitPayload.class;
            case CLOSURE_NEW -> KindPayload.ClosureNewPayload.class;
            case FUNCTION_ADAPT -> KindPayload.FunctionAdaptPayload.class;
            case ASSIGN -> KindPayload.AssignPayload.class;
            case DELETE -> KindPayload.DeletePayload.class;
            case CALL -> KindPayload.CallPayload.class;
            case EXTERNAL_ENTRY -> KindPayload.ExternalEntryPayload.class;
            case CALLBACK_INVOKE -> KindPayload.CallbackInvokePayload.class;
            case INTRINSIC_CALL -> KindPayload.IntrinsicCallPayload.class;
            case STDLIB_CALL -> KindPayload.StdlibCallPayload.class;
            case ASYNC_START -> KindPayload.AsyncStartPayload.class;
            case AWAIT -> KindPayload.AwaitPayload.class;
            case BRANCH -> KindPayload.BranchPayload.class;
            case LOOP -> KindPayload.LoopPayload.class;
            case FOR_EACH -> KindPayload.ForEachPayload.class;
            case TRY_CATCH -> KindPayload.TryCatchPayload.class;
            case THROW -> KindPayload.ThrowPayload.class;
            case RETURN -> KindPayload.ReturnPayload.class;
            case BREAK -> KindPayload.BreakPayload.class;
            case CONTINUE -> KindPayload.ContinuePayload.class;
            case DISCARD -> KindPayload.DiscardPayload.class;
            case CLASS_DEFAULT -> KindPayload.ClassDefaultPayload.class;
            case CLASS_NEW -> KindPayload.ClassNewPayload.class;
            case CLASS_FACTORY -> KindPayload.ClassFactoryPayload.class;
            case FIELD_READ -> KindPayload.FieldReadPayload.class;
            case FIELD_WRITE -> KindPayload.FieldWritePayload.class;
            case FIELD_DELETE -> KindPayload.FieldDeletePayload.class;
            case JSON_FROM_CLASS -> KindPayload.JsonFromClassPayload.class;
            case JSON_TO_CLASS -> KindPayload.JsonToClassPayload.class;
            case MODULE_INIT -> KindPayload.ModuleInitPayload.class;
            case MODULE_IMPORT -> KindPayload.ModuleImportPayload.class;
            case EXPORT_READ -> KindPayload.ExportReadPayload.class;
            case EXPORT_PUBLISH -> KindPayload.ExportPublishPayload.class;
            case ENTRY_INVOKE -> KindPayload.EntryInvokePayload.class;
        };
    }
}
