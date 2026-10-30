package deal.semantic.ir;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The canonical raw-to-typed materialization of the complete
 * {@code deal.semantic-ir/2} unit: the exact inverse of
 * {@link RawUnit#fromTyped(LoweredModuleUnit)} over the facts the composed
 * admission consumes.
 *
 * <p><b>Role.</b> The typed record surface feeds the composed admission
 * through {@link RawUnit#fromTyped(LoweredModuleUnit)}; the canonical text
 * surface feeds the same admission through
 * {@link ContractSnapshotCanonicalizer#parseUnit(CanonicalJson.Obj)}. The
 * binding-production and class-construction owners consume typed records,
 * so both surfaces materialize one typed unit from the shared raw facts
 * here and run exactly the same owner code over exactly the same content.
 * No fresh identity, no inferred capture, no repaired map and no invented
 * provider: every materialized fact is copied from the transported
 * content.</p>
 *
 * <p><b>Fidelity.</b> Every recognized shape is decoded into its typed
 * record and the decoded payload is re-rendered through
 * {@link ContractSnapshotCanonicalizer#payloadJson(KindPayload)} and
 * compared with the transported payload object; a disagreement (an absent
 * mandatory position, an unknown member, an extra member, a mistyped
 * value) is a {@link Defect}, never a silent repair. The materialized unit
 * therefore describes exactly the transported content, including
 * registry-key/record disagreements and every companion.</p>
 *
 * <p><b>Authority.</b> Materialization grants no executable authority: it
 * is pure, deterministic, invocation-local and runs no default, import,
 * host getter, library or user function. Callers admit the materialized
 * records through the existing owners.</p>
 */
public final class RawUnitMaterializer {

    private RawUnitMaterializer() {
        // Static surface; no instances.
    }

    /**
     * One undecodable transported fact: the subject (the unit, a binding
     * registry key, a function registry key or an operation) plus the
     * detail naming the exact position. The admission surface projects
     * this into its existing E6005 failure contract; materialization
     * itself never throws a decode exception, because it runs after the
     * transport framing and the closed rule engine.
     */
    public static final class Defect extends RuntimeException {

        private final String subject;
        private final String detail;

        public Defect(String subject, String detail) {
            super(subject + ": " + detail);
            this.subject = subject;
            this.detail = detail;
        }

        /** The transported fact that could not be decoded. */
        public String subject() {
            return subject;
        }

        /** The exact undecodable position. */
        public String detail() {
            return detail;
        }
    }

    // =========================================================================
    // Public surface
    // =========================================================================

    /**
     * Materializes the complete typed unit of the transported raw facts.
     *
     * @throws Defect when any transported fact is not a complete
     *         recognized shape; the caller projects the defect into its
     *         existing failure contract
     */
    public static LoweredModuleUnit materialize(RawUnit unit) {
        Objects.requireNonNull(unit, "unit must not be null");
        try {
            return materializeUnit(unit);
        } catch (Defect defect) {
            throw defect;
        } catch (RuntimeException e) {
            throw new Defect("unit " + unit.modulePath(),
                "undecodable transported content: " + e);
        }
    }

    private static LoweredModuleUnit materializeUnit(RawUnit unit) {
        ModuleId moduleId = moduleId(unit.modulePath(), "moduleId");
        SemanticProfile profile = enumOf(SemanticProfile.class, unit.semanticProfile(),
            "semanticProfile");

        Set<SemanticCapability> capabilities =
            EnumSet.noneOf(SemanticCapability.class);
        for (String capability : unit.requiredCapabilities()) {
            capabilities.add(enumOf(SemanticCapability.class, capability,
                "requiredCapabilities entry"));
        }

        Map<ConstructKind, List<SemanticOpKind>> coverage = new EnumMap<>(ConstructKind.class);
        for (RawCoverage row : unit.coverage()) {
            ConstructKind construct = enumOf(ConstructKind.class, row.construct(),
                "constructCoverage construct");
            List<SemanticOpKind> kinds = new ArrayList<>();
            for (String kind : row.opKinds()) {
                kinds.add(enumOf(SemanticOpKind.class, kind, "constructCoverage opKinds entry"));
            }
            if (coverage.put(construct, kinds) != null) {
                throw new Defect("unit " + unit.modulePath(),
                    "duplicate constructCoverage construct " + construct);
            }
        }

        Map<ClassId, ClassLayout> classLayouts = new LinkedHashMap<>();
        for (RawUnit.RawClassLayout entry : unit.classLayouts()) {
            String where = "classLayouts entry " + entry.key();
            if (classLayouts.put(entry.key(), layout(entry.layout(), where)) != null) {
                throw new Defect(where, "duplicate class-layout registry key");
            }
        }

        Map<FunctionId, LoweredFunction> functions = new LinkedHashMap<>();
        for (RawUnit.RawFunction entry : unit.functions()) {
            String where = "functions entry " + entry.key();
            if (functions.put(entry.key(), function(entry, where)) != null) {
                throw new Defect(where, "duplicate function registry key");
            }
        }

        ExportPlan exportPlan = exportPlan(unit.exportPlan());

        Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings =
            new LinkedHashMap<>();
        for (RawBinding binding : unit.bindings()) {
            FunctionAllocationIdentity key =
                new FunctionAllocationIdentity(binding.allocationId());
            if (bindings.put(key, executionBinding(binding)) != null) {
                throw new Defect("functionBindings entry " + binding.allocationId(),
                    "duplicate allocation identity");
            }
        }

        List<SemanticOp> ops = new ArrayList<>();
        for (RawOp op : unit.ops()) {
            ops.add(operation(op));
        }

        return new LoweredModuleUnit(LoweredModuleUnit.FORMAT_VERSION, profile, moduleId,
            unit.interfaceHash(), unit.loweringContextHash(), capabilities, coverage,
            classLayouts, functions, unit.moduleInit(), exportPlan, bindings, ops,
            unit.bodyTable(), unit.classFactories(), unit.classBindingFacts());
    }

    // =========================================================================
    // Unit companions
    // =========================================================================

    private static ClassLayout layout(RawUnit.RawLayout layout, String where) {
        List<ClassLayout.FieldLayout> fields = new ArrayList<>();
        for (RawUnit.RawFieldLayout field : layout.fields()) {
            fields.add(new ClassLayout.FieldLayout(field.name(),
                descriptor(field.descriptor(), where + " field '" + field.name() + "'"),
                field.required(),
                enumOf(ConstructionProvider.class, field.constructionProvider(),
                    where + " field '" + field.name() + "' constructionProvider")));
        }
        return new ClassLayout(layout.classId(), fields);
    }

    private static LoweredFunction function(RawUnit.RawFunction entry, String where) {
        RuntimeDescriptor parsed = descriptor(entry.descriptor(), where + " descriptor");
        if (!(parsed instanceof RuntimeDescriptor.Func signature)) {
            throw new Defect(where, "the function descriptor " + entry.descriptor()
                + " is not a function signature");
        }
        return new LoweredFunction(entry.functionId(), signature, entry.captures(),
            entry.body(), entry.sourceName());
    }

    private static ExportPlan exportPlan(List<RawUnit.RawExportEntry> entries) {
        List<ExportPlan.ExportPlanEntry> plan = new ArrayList<>();
        for (RawUnit.RawExportEntry entry : entries) {
            plan.add(new ExportPlan.ExportPlanEntry(entry.name(),
                descriptor(entry.descriptor(), "exportPlan entry '" + entry.name() + "'"),
                entry.publishOpId()));
        }
        return new ExportPlan(plan);
    }

    private static FunctionExecutionBinding executionBinding(RawBinding binding) {
        String where = "functionBindings entry " + binding.allocationId();
        return switch (binding.shape()) {
            case "loweredBody" -> {
                if (binding.functionId() == null || binding.blockId() == null) {
                    throw new Defect(where, "a loweredBody registration requires its "
                        + "functionId and blockId");
                }
                yield new FunctionExecutionBinding.LoweredBody(binding.functionId(),
                    binding.blockId());
            }
            case "adapter" -> {
                if (binding.adaptOpId() == null) {
                    throw new Defect(where, "an adapter registration requires its adaptOpId");
                }
                yield new FunctionExecutionBinding.AdapterBinding(binding.adaptOpId(),
                    enumOf(CaptureMode.class, binding.captureMode(), where + " captureMode"),
                    sourceRef(binding.sourceRef(), where),
                    funcSignature(binding.sourceSignature(), where + " sourceSignature"),
                    funcSignature(binding.targetSignature(), where + " targetSignature"));
            }
            case "hostFunction" -> new FunctionExecutionBinding.HostFunction(
                moduleId(binding.modulePath(), where + " hostModuleId"),
                requireNonNull(binding.exportName(), where + " exportName"),
                funcSignature(binding.descriptor(), where + " descriptor"),
                binding.importCell());
            case "hostFunctionValue" -> new FunctionExecutionBinding.HostFunctionValue(
                moduleId(binding.modulePath(), where + " hostModuleId"),
                requireNonNull(binding.materializingBoundaryOpId(),
                    where + " materializingBoundaryOpId"),
                funcSignature(binding.descriptor(), where + " descriptor"));
            case "externalFunction" -> new FunctionExecutionBinding.ExternalFunction(
                moduleId(binding.modulePath(), where + " moduleId"),
                requireNonNull(binding.exportName(), where + " exportName"),
                funcSignature(binding.descriptor(), where + " descriptor"),
                enumOf(ExternalExecutionOwner.class, binding.executionOwner(),
                    where + " executionOwner"));

            case "dynamicFunctionValue" -> new FunctionExecutionBinding.DynamicFunctionValue(
                requireNonNull(binding.materializingBoundaryOpId(),
                    where + " materializingBoundaryOpId (the preserved wire name)"),
                funcSignature(binding.descriptor(), where + " descriptor"));
            default -> throw new Defect(where, "unknown binding shape \""
                + binding.shape() + "\"");
        };
    }

    private static AdaptSourceRef sourceRef(RawSourceRef sourceRef, String where) {
        if (sourceRef == null) {
            throw new Defect(where, "the adapter registration carries no sourceRef");
        }
        return switch (sourceRef) {
            case RawSourceRef.Value value -> new AdaptSourceRef.Value(value.value());
            case RawSourceRef.Unknown unknown -> throw new Defect(where,
                "unknown adapter sourceRef discriminator \"" + unknown.type() + "\"");
        };
    }

    // =========================================================================
    // Operations
    // =========================================================================

    private static SemanticOp operation(RawOp op) {
        String where = "op " + op.opId();
        SemanticOpKind kind = enumOf(SemanticOpKind.class, op.kind(), where + " kind");
        FailurePolicyId failurePolicy = enumOf(FailurePolicyId.class, op.failurePolicy(),
            where + " failurePolicy");

        SemanticValue result = op.resultValue() != null ? op.resultValue() : op.resultToken();
        OpResultType resultType = resultType(op.resultType(), where + " resultType");
        List<RuntimeDescriptor> operandTypes = new ArrayList<>();
        for (String text : op.operandTypes()) {
            operandTypes.add(descriptor(text, where + " operandType"));
        }
        if (op.operands().size() != operandTypes.size()) {
            throw new Defect(where, "the operands (" + op.operands().size()
                + ") and operandTypes (" + operandTypes.size() + ") positions differ in length");
        }

        KindPayload payload = payload(kind, op.payload(), where);
        CanonicalJson.Value rerendered = ContractSnapshotCanonicalizer.payloadJson(payload);
        if (!rerendered.equals(op.payload())) {
            throw new Defect(where, "the " + kind + " payload is not a complete recognized "
                + "shape (re-rendering the decoded payload differs from the transported "
                + "payload)");
        }

        ClosedSelector selector = payload instanceof KindPayload.SelectorCarrying carrying
            ? carrying.selector() : null;
        List<SemanticId> referenced = referencedIds(op, where);
        OperationContractSnapshot draft = new OperationContractSnapshot(
            OperationContractSnapshot.VERSION, kind, resultType, operandTypes, selector,
            payload, failurePolicy, referenced, "materialization");
        OperationContractSnapshot contract = new OperationContractSnapshot(
            OperationContractSnapshot.VERSION, kind, resultType, operandTypes, selector,
            payload, failurePolicy, referenced,
            ContractSnapshotCanonicalizer.digest(draft));

        return new SemanticOp(op.opId(), kind, origin(op, where), result, resultType,
            op.operands(), operandTypes, payload, failurePolicy, contract);
    }

    private static List<SemanticId> referencedIds(RawOp op, String where) {
        CanonicalJson.Value value = ContractSnapshotCanonicalizer.payloadValue(op.snapshot(),
            "referencedSemanticIds");
        if (!(value instanceof CanonicalJson.Arr array)) {
            throw new Defect(where, "the contract snapshot carries no referencedSemanticIds "
                + "array");
        }
        List<SemanticId> ids = new ArrayList<>();
        for (CanonicalJson.Value item : array.items()) {
            ids.add(semanticId(item, where + " referencedSemanticIds entry"));
        }
        return ids;
    }

    private static SourceOrigin origin(RawOp op, String where) {
        RawOrigin origin = op.origin();
        if (origin == null) {
            throw new Defect(where, "the operation carries no origin metadata");
        }
        return new SourceOrigin(sourceId(origin), span(origin),
            originKind(quietEnumOf(SourceOriginKind.class, origin.kind())),
            new AnchorId(origin.anchorId()), op.parentOpId());
    }

    /**
     * The typed span of the retained origin metadata. The raw origin is
     * diagnostic-only data (never a semantic rejection), so an absent or
     * malformed coordinate position falls back to the canonical synthetic
     * span carrying the retained file — exactly the shape
     * {@link RawOrigin#diagnosticRange()} produces for unusable
     * coordinates — instead of rejecting the operation. The absent file
     * keeps the canonical empty-file synthetic span.
     */
    private static SourceSpan span(RawOrigin origin) {
        String file = origin.file() == null ? "" : origin.file();
        try {
            return new SourceSpan(file, origin.startLine(), origin.startColumn(),
                origin.endLine(), origin.endColumn(), origin.startScalarOffset(),
                origin.endScalarOffset());
        } catch (RuntimeException malformed) {
            return SourceSpan.synthetic(file);
        }
    }

    /** The retained source id, or the canonical empty id when absent. */
    private static String sourceId(RawOrigin origin) {
        return origin.sourceId() == null ? "" : origin.sourceId();
    }

    /** The retained origin kind, or the neutral synthetic kind when absent or open. */
    private static SourceOriginKind originKind(SourceOriginKind kind) {
        return kind == null ? SourceOriginKind.SYNTHETIC : kind;
    }

    /** One closed enum value by name, or {@code null} for an absent/open name. */
    private static <E extends Enum<E>> E quietEnumOf(Class<E> type, String name) {
        if (name == null) {
            return null;
        }
        for (E candidate : type.getEnumConstants()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        return null;
    }

    private static OpResultType resultType(String text, String where) {
        if (text == null) {
            return null;
        }
        for (InternalResultType internal : InternalResultType.values()) {
            if (internal.name().equals(text)) {
                return internal;
            }
        }
        return descriptor(text, where);
    }

    // =========================================================================
    // Payload decoding (the inverse of payloadJson)
    // =========================================================================

    private static KindPayload payload(SemanticOpKind kind, CanonicalJson.Obj p, String where) {
        return switch (kind) {
            case CONST -> new KindPayload.ConstPayload(scalar(p, "value", where));
            case UNARY -> new KindPayload.UnaryPayload(enumAt(p, "selector",
                UnarySelector.class, where), optionalDescriptor(p, "target", where));
            case BINARY -> new KindPayload.BinaryPayload(
                enumAt(p, "selector", BinarySelector.class, where),
                optionalDescriptor(p, "innerDescriptor", where),
                optionalEnumAt(p, "side", NullableSide.class, where));
            case STRING_CONCAT -> new KindPayload.StringConcatPayload(
                valueIds(p, "fragments", where));
            case ARRAY_NEW -> new KindPayload.ArrayNewPayload(
                descriptorAt(p, "elementDescriptor", where),
                valueIds(p, "values", where),
                opIds(p, "elementBoundaryOpIds", where));
            case TABLE_NEW -> new KindPayload.TableNewPayload(tableEntries(p, where));
            case ARRAY_LENGTH -> new KindPayload.ArrayLengthPayload(valueIdAt(p, "arrayValue", where));
            case MEMBER_READ -> new KindPayload.MemberReadPayload(
                valueIdAt(p, "table", where), stringAt(p, "key", where));
            case MEMBER_WRITE -> new KindPayload.MemberWritePayload(
                valueIdAt(p, "table", where), stringAt(p, "key", where),
                valueIdAt(p, "value", where));
            case MEMBER_DELETE -> new KindPayload.MemberDeletePayload(
                valueIdAt(p, "table", where), stringAt(p, "key", where));
            case INDEX_NORMALIZE -> new KindPayload.IndexNormalizePayload(
                enumAt(p, "mode", IndexMode.class, where),
                valueIdAt(p, "rawKey", where), valueIdAt(p, "currentLength", where));
            case INDEX_READ -> new KindPayload.IndexReadPayload(
                valueIdAt(p, "container", where), valueIdAt(p, "slot", where),
                opIdAt(p, "elementBoundaryOpId", where));
            case INDEX_WRITE -> new KindPayload.IndexWritePayload(
                valueIdAt(p, "container", where), valueIdAt(p, "slot", where),
                valueIdAt(p, "value", where));
            case INDEX_DELETE -> new KindPayload.IndexDeletePayload(
                valueIdAt(p, "container", where), valueIdAt(p, "slot", where));
            case OPTIONAL_READ -> new KindPayload.OptionalReadPayload(
                optionalValueId(p, "value", where), boolAt(p, "present", where),
                descriptorAt(p, "descriptor", where));
            case HAS_FIELD -> new KindPayload.HasFieldPayload(valueIdAt(p, "receiver", where),
                optionalString(p, "key"), optionalValueId(p, "dynamicKey", where));
            case BOUNDARY -> new KindPayload.BoundaryPayload(
                enumAt(p, "kind", BoundaryKind.class, where),
                descriptorAt(p, "descriptor", where), valueIdAt(p, "input", where),
                realization(p, "realization", where));
            case BINDING_ALLOC -> new KindPayload.BindingAllocPayload(
                bindingIdAt(p, "binding", where),
                blockIdAt(p, "scope", where),
                boolAt(p, "mutable", where),
                enumAt(p, "cellKind", BindingCellKind.class, where),
                generation(p, "generation", where));
            case BINDING_INIT -> new KindPayload.BindingInitPayload(
                bindingIdAt(p, "binding", where), generation(p, "generation", where),
                optionalValueId(p, "value", where));
            case BINDING_LOAD -> new KindPayload.BindingLoadPayload(
                bindingIdAt(p, "binding", where), generation(p, "generation", where));
            case BINDING_STORE -> new KindPayload.BindingStorePayload(
                bindingIdAt(p, "binding", where), generation(p, "generation", where),
                optionalValueId(p, "value", where));
            case RECURSIVE_GROUP_INIT -> new KindPayload.RecursiveGroupInitPayload(
                bindingIds(p, "bindings", where), functionIds(p, "functions", where));
            case CLOSURE_NEW -> new KindPayload.ClosureNewPayload(
                functionIdAt(p, "function", where),
                funcSignatureAt(p, "signature", where),
                bindingGenerations(p, "captures", where),
                loweredBody(p, "binding", where));
            case FUNCTION_ADAPT -> new KindPayload.FunctionAdaptPayload(
                funcSignatureAt(p, "sourceSignature", where),
                funcSignatureAt(p, "targetSignature", where),
                enumAt(p, "mode", CaptureMode.class, where),
                sourceRefAt(p, "source", where),
                optionalImmutabilityProof(p, "proof", where));
            case ASSIGN -> new KindPayload.AssignPayload(
                enumAt(p, "targetKind", AssignTargetKind.class, where),
                opIds(p, "childOps", where));
            case DELETE -> new KindPayload.DeletePayload(
                enumAt(p, "targetKind", DeleteTargetKind.class, where),
                opIds(p, "childOps", where));
            case CALL -> new KindPayload.CallPayload(
                enumAt(p, "mode", CallMode.class, where),
                callee(p, "callee", where),
                funcSignatureAt(p, "signature", where),
                opIds(p, "parameterBoundaryOpIds", where),
                optionalOpId(p, "returnBoundaryOpId", where),
                optionalDynamicReturnBoundary(p, "dynamicReturnBoundary", where),
                optionalBlockId(p, "bodyBlock", where),
                optionalOpId(p, "externalEntryRef", where));
            case EXTERNAL_ENTRY -> new KindPayload.ExternalEntryPayload(
                stringAt(p, "exportName", where), functionIdAt(p, "function", where),
                funcSignatureAt(p, "signature", where), boolAt(p, "async", where),
                opIdAt(p, "returnBoundaryOpId", where),
                optionalDescriptor(p, "completionDescriptor", where));
            case CALLBACK_INVOKE -> new KindPayload.CallbackInvokePayload(
                valueIdAt(p, "function", where), funcSignatureAt(p, "descriptor", where),
                opIds(p, "parameterBoundaryOpIds", where),
                opIdAt(p, "returnBoundaryOpId", where));

            case STDLIB_CALL -> new KindPayload.StdlibCallPayload(
                enumAt(p, "function", StdlibFunctionId.class, where),
                valueIds(p, "args", where),
                enumAt(p, "effectCapability", SemanticCapability.class, where));
            case ASYNC_START -> new KindPayload.AsyncStartPayload(
                callee(p, "callee", where),
                enumAt(p, "source", AsyncStartSource.class, where),
                enumAt(p, "parameterBoundaryMode", ParameterBoundaryMode.class, where),
                opIds(p, "parameterBoundaryOpIds", where),
                descriptorAt(p, "completionDescriptor", where),
                optionalOpId(p, "returnBoundaryOpId", where),
                optionalString(p, "hostOperationLabel"),
                optionalExternalAsyncLink(p, "externalAsyncLink", where));
            case AWAIT -> new KindPayload.AwaitPayload(
                tokenAt(p, "token", where), descriptorAt(p, "completionDescriptor", where),
                opIdAt(p, "completionBoundaryOpId", where));
            case BRANCH -> new KindPayload.BranchPayload(
                enumAt(p, "selector", ControlSelector.class, where),
                valueIdAt(p, "condition", where), blockIdAt(p, "selectedBlock", where),
                optionalBlockId(p, "alternateBlock", where));
            case LOOP -> new KindPayload.LoopPayload(
                enumAt(p, "selector", ControlSelector.class, where),
                optionalBlockId(p, "initBlock", where), valueIdAt(p, "condition", where),
                blockIdAt(p, "bodyBlock", where), optionalBlockId(p, "updateBlock", where));
            case FOR_EACH -> new KindPayload.ForEachPayload(
                enumAt(p, "mode", IterationMode.class, where),
                valueIdAt(p, "iterable", where), bindingIdAt(p, "binding", where),
                generation(p, "generation", where), blockIdAt(p, "body", where));
            case TRY_CATCH -> new KindPayload.TryCatchPayload(
                blockIdAt(p, "tryBlock", where), bindingIdAt(p, "catchBinding", where),
                blockIdAt(p, "catchBlock", where));
            case THROW -> new KindPayload.ThrowPayload(valueIdAt(p, "errorValue", where));
            case RETURN -> new KindPayload.ReturnPayload(
                optionalValueId(p, "value", where), functionIdAt(p, "function", where),
                opIdAt(p, "enclosingInvocationOpId", where),
                opIdAt(p, "returnBoundaryOpId", where));
            case BREAK -> new KindPayload.BreakPayload(opIdAt(p, "loopId", where));
            case CONTINUE -> new KindPayload.ContinuePayload(opIdAt(p, "loopId", where));
            case DISCARD -> new KindPayload.DiscardPayload(valueIdAt(p, "value", where));

            case CLASS_NEW -> new KindPayload.ClassNewPayload(
                classIdAt(p, "classId", where), layoutAt(p, "layout", where),
                providedFields(p, "providedFields", where),
                enumAt(p, "constructionProvider", ConstructionProvider.class, where),
                optionalClassFactoryId(p, "classFactoryRef", where),
                fieldBoundaries(p, "fieldBoundaries", where));
            case CLASS_FACTORY -> new KindPayload.ClassFactoryPayload(
                classIdAt(p, "classId", where), opIdAt(p, "callerOpRef", where));
            case FIELD_READ -> new KindPayload.FieldReadPayload(
                valueIdAt(p, "classValue", where), classIdAt(p, "classId", where),
                stringAt(p, "field", where),
                optionalClassFactoryId(p, "classFactoryRef", where));
            case FIELD_WRITE -> new KindPayload.FieldWritePayload(
                valueIdAt(p, "classValue", where), classIdAt(p, "classId", where),
                stringAt(p, "field", where), valueIdAt(p, "value", where),
                optionalClassFactoryId(p, "classFactoryRef", where));
            case FIELD_DELETE -> new KindPayload.FieldDeletePayload(
                valueIdAt(p, "classValue", where), classIdAt(p, "classId", where),
                stringAt(p, "field", where),
                optionalClassFactoryId(p, "classFactoryRef", where));
            case JSON_FROM_CLASS -> new KindPayload.JsonFromClassPayload(
                layoutAt(p, "layout", where), valueIdAt(p, "jsonString", where));
            case JSON_TO_CLASS -> new KindPayload.JsonToClassPayload(
                valueIdAt(p, "classValue", where), layoutAt(p, "layout", where));
            case MODULE_INIT -> new KindPayload.ModuleInitPayload(
                moduleIdAt(p, "module", where), moduleIds(p, "imports", where),
                blockIdAt(p, "initBlock", where));
            case MODULE_IMPORT -> new KindPayload.ModuleImportPayload(
                stringAt(p, "rawSpecifier", where), moduleIdAt(p, "resolvedModule", where),
                enumAt(p, "kind", ModuleImportKind.class, where),
                bindingIds(p, "aliasCells", where));
            case EXPORT_READ -> new KindPayload.ExportReadPayload(
                moduleIdAt(p, "module", where), stringAt(p, "name", where),
                descriptorAt(p, "descriptor", where), valueIdAt(p, "value", where));
            case EXPORT_PUBLISH -> new KindPayload.ExportPublishPayload(
                moduleIdAt(p, "module", where), stringAt(p, "name", where),
                descriptorAt(p, "descriptor", where), valueIdAt(p, "value", where));
            case ENTRY_INVOKE -> new KindPayload.EntryInvokePayload(
                moduleIdAt(p, "module", where), functionIdAt(p, "mainFunction", where));
        };
    }

    // =========================================================================
    // Scalar / id / list helpers
    // =========================================================================

    private static ScalarValue scalar(CanonicalJson.Obj payload, String key, String where) {
        CanonicalJson.Value value = at(payload, key, where);
        if (value instanceof CanonicalJson.Null) {
            return ScalarValue.Null.INSTANCE;
        }
        if (value.equals(CanonicalJson.obj(CanonicalJson.e("undefined", CanonicalJson.bool(true))))) {
            return ScalarValue.Undefined.INSTANCE;
        }
        if (value instanceof CanonicalJson.Bool b) {
            return new ScalarValue.Boolean(b.value());
        }
        if (value instanceof CanonicalJson.Num n) {
            return new ScalarValue.Number(n.value());
        }
        if (value instanceof CanonicalJson.Str s) {
            return new ScalarValue.String(s.value());
        }
        throw new Defect(where, "\"" + key + "\" is not a canonical scalar");
    }

    private static BoundaryRealization realization(CanonicalJson.Obj payload, String key,
                                                   String where) {
        CanonicalJson.Obj obj = objectAt(payload, key, where);
        String type = stringAt(obj, "type", where + " " + key);
        return switch (type) {
            case "runtimeValidation" -> new BoundaryRealization.RuntimeValidation(
                stringAt(obj, "checkId", where));
            case "representationProof" -> new BoundaryRealization.RepresentationProof(
                stringAt(obj, "proofKind", where));
            default -> throw new Defect(where, "unknown boundary realization type \"" + type
                + "\"");
        };
    }

    private static KindPayload.CallCallee callee(CanonicalJson.Obj payload, String key,
                                                 String where) {
        CanonicalJson.Obj obj = objectAt(payload, key, where);
        String type = stringAt(obj, "type", where + " " + key);
        return switch (type) {
            case "static" -> new KindPayload.CallCallee.Static(
                executionBindingAt(obj, "binding", where));
            case "indirect" -> new KindPayload.CallCallee.Indirect(valueIdAt(obj, "callee", where));
            case "dynamic" -> new KindPayload.CallCallee.Dynamic(valueIdAt(obj, "callee", where));
            default -> throw new Defect(where, "unknown call callee type \"" + type + "\"");
        };
    }

    /**
     * One {@code bindingJson} discriminated entry: the complete closed
     * binding variant set at an inline registry position (the static call
     * callee's recorded binding).
     */
    private static FunctionExecutionBinding executionBindingAt(CanonicalJson.Obj obj,
                                                               String key, String where) {
        CanonicalJson.Obj binding = objectAt(obj, key, where);
        String type = stringAt(binding, "type", where + " " + key);
        return switch (type) {
            case "loweredBody" -> new FunctionExecutionBinding.LoweredBody(
                functionIdAt(binding, "functionId", where),
                blockIdAt(binding, "blockId", where));
            case "adapter" -> new FunctionExecutionBinding.AdapterBinding(
                opIdAt(binding, "adaptOpId", where),
                enumAt(binding, "captureMode", CaptureMode.class, where),
                sourceRefAt(binding, "sourceRef", where),
                funcSignatureAt(binding, "sourceSignature", where),
                funcSignatureAt(binding, "targetSignature", where));
            case "hostFunction" -> new FunctionExecutionBinding.HostFunction(
                moduleIdAt(binding, "hostModuleId", where),
                stringAt(binding, "exportName", where),
                funcSignatureAt(binding, "descriptor", where),
                optionalBindingIdAt(binding, "importCellId", where));
            case "hostFunctionValue" -> new FunctionExecutionBinding.HostFunctionValue(
                moduleIdAt(binding, "hostModuleId", where),
                opIdAt(binding, "materializingBoundaryOpId", where),
                funcSignatureAt(binding, "descriptor", where));
            case "externalFunction" -> new FunctionExecutionBinding.ExternalFunction(
                moduleIdAt(binding, "moduleId", where),
                stringAt(binding, "exportName", where),
                funcSignatureAt(binding, "descriptor", where),
                enumAt(binding, "executionOwner", ExternalExecutionOwner.class, where));

            case "dynamicFunctionValue" -> new FunctionExecutionBinding.DynamicFunctionValue(
                opIdAt(binding, "materializingBoundaryOpId", where),
                funcSignatureAt(binding, "descriptor", where));
            default -> throw new Defect(where, "unknown binding shape \"" + type + "\"");
        };
    }

    private static ExternalAsyncLink optionalExternalAsyncLink(CanonicalJson.Obj payload,
                                                               String key, String where) {
        CanonicalJson.Obj obj = optionalObjectAt(payload, key, where);
        if (obj == null) {
            return null;
        }
        return new ExternalAsyncLink(moduleIdAt(obj, "calleeModuleId", where),
            stringAt(obj, "exportName", where), tokenAt(obj, "calleeTokenId", where));
    }

    private static KindPayload.DynamicReturnBoundary optionalDynamicReturnBoundary(
            CanonicalJson.Obj payload, String key, String where) {
        CanonicalJson.Obj obj = optionalObjectAt(payload, key, where);
        if (obj == null) {
            return null;
        }
        return new KindPayload.DynamicReturnBoundary(
            opIdAt(obj, "dealBodyBoundaryOpId", where),
            opIdAt(obj, "hostBoundaryOpId", where),
            opIdAt(obj, "externalBoundaryOpId", where));
    }

    private static BindingImmutabilityProof optionalImmutabilityProof(CanonicalJson.Obj payload,
                                                                      String key, String where) {
        CanonicalJson.Obj obj = optionalObjectAt(payload, key, where);
        if (obj == null) {
            return null;
        }
        return new BindingImmutabilityProof(bindingIdAt(obj, "binding", where),
            generation(obj, "generation", where));
    }

    private static FunctionAllocationIdentity functionAllocation(CanonicalJson.Obj obj,
                                                                 String where) {
        return new FunctionAllocationIdentity(requireInt(obj, "id", where));
    }

    private static BindingGeneration bindingGeneration(CanonicalJson.Obj obj, String where) {
        return new BindingGeneration(bindingIdAt(obj, "binding", where),
            generation(obj, "generation", where));
    }

    private static List<BindingGeneration> bindingGenerations(CanonicalJson.Obj payload,
                                                              String key, String where) {
        List<BindingGeneration> generations = new ArrayList<>();
        for (CanonicalJson.Value item : arrayAt(payload, key, where).items()) {
            generations.add(bindingGeneration(objectValue(item, key, where), where));
        }
        return generations;
    }

    private static KindPayload.TableEntry tableEntry(CanonicalJson.Obj obj, String where) {
        return new KindPayload.TableEntry(stringAt(obj, "key", where),
            valueIdAt(obj, "value", where));
    }

    private static List<KindPayload.TableEntry> tableEntries(CanonicalJson.Obj payload,
                                                             String where) {
        List<KindPayload.TableEntry> entries = new ArrayList<>();
        for (CanonicalJson.Value item : arrayAt(payload, "entries", where).items()) {
            entries.add(tableEntry(objectValue(item, "entries", where), where));
        }
        return entries;
    }

    private static KindPayload.ProvidedField providedField(CanonicalJson.Obj obj, String where) {
        return new KindPayload.ProvidedField(stringAt(obj, "name", where),
            valueIdAt(obj, "valueOpId", where));
    }

    private static List<KindPayload.ProvidedField> providedFields(CanonicalJson.Obj payload,
                                                                  String key, String where) {
        List<KindPayload.ProvidedField> fields = new ArrayList<>();
        for (CanonicalJson.Value item : arrayAt(payload, key, where).items()) {
            fields.add(providedField(objectValue(item, key, where), where));
        }
        return fields;
    }

    private static KindPayload.FieldBoundary fieldBoundary(CanonicalJson.Obj obj, String where) {
        return new KindPayload.FieldBoundary(stringAt(obj, "field", where),
            enumAt(obj, "kind", BoundaryKind.class, where),
            opIdAt(obj, "boundaryOpId", where));
    }

    private static List<KindPayload.FieldBoundary> fieldBoundaries(CanonicalJson.Obj payload,
                                                                   String key, String where) {
        List<KindPayload.FieldBoundary> boundaries = new ArrayList<>();
        for (CanonicalJson.Value item : arrayAt(payload, key, where).items()) {
            boundaries.add(fieldBoundary(objectValue(item, key, where), where));
        }
        return boundaries;
    }

    private static ClassLayout layoutAt(CanonicalJson.Obj payload, String key, String where) {
        CanonicalJson.Obj obj = objectAt(payload, key, where);
        ClassId classId = classIdAt(obj, "classId", where);
        List<ClassLayout.FieldLayout> fields = new ArrayList<>();
        for (CanonicalJson.Value item : arrayAt(obj, "fields", where).items()) {
            CanonicalJson.Obj field = objectValue(item, "fields", where);
            fields.add(new ClassLayout.FieldLayout(stringAt(field, "name", where),
                descriptorAt(field, "descriptor", where), boolAt(field, "required", where),
                enumAt(field, "constructionProvider", ConstructionProvider.class, where)));
        }
        return new ClassLayout(classId, fields);
    }

    private static AdaptSourceRef sourceRefAt(CanonicalJson.Obj payload, String key, String where) {
        CanonicalJson.Obj obj = objectAt(payload, key, where);
        String type = stringAt(obj, "type", where + " " + key);
        return switch (type) {
            case "value" -> new AdaptSourceRef.Value(valueIdAt(obj, "value", where));
            default -> throw new Defect(where, "unknown adapter sourceRef discriminator \""
                + type + "\"");
        };
    }

    private static FunctionExecutionBinding.LoweredBody loweredBody(CanonicalJson.Obj obj,
                                                                    String key, String where) {
        CanonicalJson.Obj body = objectAt(obj, key, where);
        String type = stringAt(body, "type", where + " " + key);
        if (!"loweredBody".equals(type)) {
            throw new Defect(where, "the " + key + " position requires a loweredBody binding, "
                + "got type \"" + type + "\"");
        }
        return new FunctionExecutionBinding.LoweredBody(
            functionIdAt(body, "functionId", where), blockIdAt(body, "blockId", where));
    }

    // =========================================================================
    // Semantic ids and primitive positions
    // =========================================================================

    private static SemanticId semanticId(CanonicalJson.Value value, String where) {
        CanonicalJson.Obj obj = objectValue(value, "referencedSemanticIds", where);
        String type = stringAt(obj, "type", where);
        return switch (type) {
            case "module" -> ContractSnapshotCanonicalizer.parseModuleId(obj);
            case "class" -> ContractSnapshotCanonicalizer.parseClassId(obj);
            case "function" -> ContractSnapshotCanonicalizer.parseFunctionId(obj);
            case "binding" -> ContractSnapshotCanonicalizer.parseBindingId(obj);
            case "value" -> ContractSnapshotCanonicalizer.parseValueId(obj);
            case "block" -> ContractSnapshotCanonicalizer.parseBlockId(obj);
            case "op" -> ContractSnapshotCanonicalizer.parseOpId(obj);
            case "classFactory" -> ContractSnapshotCanonicalizer.parseClassFactoryId(obj);
            case "functionAllocation" -> functionAllocation(obj, where);
            case "anchor" -> new AnchorId(requireInt(obj, "id", where));
            case "tokenCanonical", "tokenAlias" -> ContractSnapshotCanonicalizer.parseToken(obj);
            default -> throw new Defect(where, "unknown semantic-id type \"" + type + "\"");
        };
    }

    private static ModuleId moduleIdAt(CanonicalJson.Obj payload, String key, String where) {
        return ContractSnapshotCanonicalizer.parseModuleId(objectAt(payload, key, where));
    }

    private static List<ModuleId> moduleIds(CanonicalJson.Obj payload, String key, String where) {
        List<ModuleId> ids = new ArrayList<>();
        for (CanonicalJson.Value item : arrayAt(payload, key, where).items()) {
            ids.add(ContractSnapshotCanonicalizer.parseModuleId(
                objectValue(item, key, where)));
        }
        return ids;
    }

    private static ClassId classIdAt(CanonicalJson.Obj payload, String key, String where) {
        return ContractSnapshotCanonicalizer.parseClassId(objectAt(payload, key, where));
    }

    private static ClassFactoryId optionalClassFactoryId(CanonicalJson.Obj payload, String key,
                                                         String where) {
        CanonicalJson.Obj obj = optionalObjectAt(payload, key, where);
        return obj == null ? null : ContractSnapshotCanonicalizer.parseClassFactoryId(obj);
    }

    private static FunctionId functionIdAt(CanonicalJson.Obj payload, String key, String where) {
        return ContractSnapshotCanonicalizer.parseFunctionId(objectAt(payload, key, where));
    }

    private static List<FunctionId> functionIds(CanonicalJson.Obj payload, String key,
                                                String where) {
        List<FunctionId> ids = new ArrayList<>();
        for (CanonicalJson.Value item : arrayAt(payload, key, where).items()) {
            ids.add(ContractSnapshotCanonicalizer.parseFunctionId(objectValue(item, key, where)));
        }
        return ids;
    }

    private static BindingId bindingIdAt(CanonicalJson.Obj payload, String key, String where) {
        return ContractSnapshotCanonicalizer.parseBindingId(objectAt(payload, key, where));
    }

    /** The nullable binding-id position (the host function's import-occurrence cell). */
    private static BindingId optionalBindingIdAt(CanonicalJson.Obj payload, String key,
                                                 String where) {
        CanonicalJson.Obj obj = optionalObjectAt(payload, key, where);
        return obj == null ? null : ContractSnapshotCanonicalizer.parseBindingId(obj);
    }

    private static List<BindingId> bindingIds(CanonicalJson.Obj payload, String key,
                                              String where) {
        List<BindingId> ids = new ArrayList<>();
        for (CanonicalJson.Value item : arrayAt(payload, key, where).items()) {
            ids.add(ContractSnapshotCanonicalizer.parseBindingId(objectValue(item, key, where)));
        }
        return ids;
    }

    private static BlockId blockIdAt(CanonicalJson.Obj payload, String key, String where) {
        return ContractSnapshotCanonicalizer.parseBlockId(objectAt(payload, key, where));
    }

    private static BlockId optionalBlockId(CanonicalJson.Obj payload, String key, String where) {
        CanonicalJson.Obj obj = optionalObjectAt(payload, key, where);
        return obj == null ? null : ContractSnapshotCanonicalizer.parseBlockId(obj);
    }

    private static OpId opIdAt(CanonicalJson.Obj payload, String key, String where) {
        return ContractSnapshotCanonicalizer.parseOpId(objectAt(payload, key, where));
    }

    private static OpId optionalOpId(CanonicalJson.Obj payload, String key, String where) {
        CanonicalJson.Obj obj = optionalObjectAt(payload, key, where);
        return obj == null ? null : ContractSnapshotCanonicalizer.parseOpId(obj);
    }

    private static List<OpId> opIds(CanonicalJson.Obj payload, String key, String where) {
        List<OpId> ids = new ArrayList<>();
        for (CanonicalJson.Value item : arrayAt(payload, key, where).items()) {
            ids.add(ContractSnapshotCanonicalizer.parseOpId(objectValue(item, key, where)));
        }
        return ids;
    }

    private static ValueId valueIdAt(CanonicalJson.Obj payload, String key, String where) {
        return ContractSnapshotCanonicalizer.parseValueId(objectAt(payload, key, where));
    }

    private static ValueId optionalValueId(CanonicalJson.Obj payload, String key, String where) {
        CanonicalJson.Obj obj = optionalObjectAt(payload, key, where);
        return obj == null ? null : ContractSnapshotCanonicalizer.parseValueId(obj);
    }

    private static List<ValueId> valueIds(CanonicalJson.Obj payload, String key, String where) {
        List<ValueId> ids = new ArrayList<>();
        for (CanonicalJson.Value item : arrayAt(payload, key, where).items()) {
            ids.add(ContractSnapshotCanonicalizer.parseValueId(objectValue(item, key, where)));
        }
        return ids;
    }

    private static AsyncTokenId tokenAt(CanonicalJson.Obj payload, String key, String where) {
        return ContractSnapshotCanonicalizer.parseToken(objectAt(payload, key, where));
    }

    private static RuntimeDescriptor descriptorAt(CanonicalJson.Obj payload, String key,
                                                  String where) {
        return descriptor(stringAt(payload, key, where), where + " " + key);
    }

    private static RuntimeDescriptor optionalDescriptor(CanonicalJson.Obj payload, String key,
                                                        String where) {
        String text = optionalString(payload, key);
        return text == null ? null : descriptor(text, where + " " + key);
    }

    private static RuntimeDescriptor.Func funcSignatureAt(CanonicalJson.Obj payload, String key,
                                                          String where) {
        return funcSignature(stringAt(payload, key, where), where + " " + key);
    }

    private static RuntimeDescriptor descriptor(String text, String where) {
        if (text == null) {
            throw new Defect(where, "the descriptor position is absent");
        }
        return RuntimeDescriptor.parseCanonicalText(text);
    }

    private static RuntimeDescriptor.Func funcSignature(String text, String where) {
        RuntimeDescriptor parsed = descriptor(text, where);
        if (!(parsed instanceof RuntimeDescriptor.Func signature)) {
            throw new Defect(where, "the descriptor \"" + text
                + "\" is not a function signature");
        }
        return signature;
    }

    private static ModuleId moduleId(String path, String where) {
        if (path == null) {
            throw new Defect(where, "the module path position is absent");
        }
        return new ModuleId(path);
    }

    private static long generation(CanonicalJson.Obj payload, String key, String where) {
        return requireInt(payload, key, where + " " + key);
    }

    private static String stringAt(CanonicalJson.Obj payload, String key, String where) {
        CanonicalJson.Value value = at(payload, key, where);
        if (value instanceof CanonicalJson.Str str) {
            return str.value();
        }
        throw new Defect(where, "\"" + key + "\" must be a string");
    }

    private static boolean boolAt(CanonicalJson.Obj payload, String key, String where) {
        CanonicalJson.Value value = at(payload, key, where);
        if (value instanceof CanonicalJson.Bool bool) {
            return bool.value();
        }
        throw new Defect(where, "\"" + key + "\" must be a boolean");
    }

    private static int requireInt(CanonicalJson.Obj payload, String key, String where) {
        CanonicalJson.Value value = at(payload, key, where);
        if (value instanceof CanonicalJson.Int number) {
            return number.value();
        }
        throw new Defect(where, "\"" + key + "\" must be a signed32 integer");
    }

    private static <E extends Enum<E>> E enumAt(CanonicalJson.Obj payload, String key,
                                                Class<E> type, String where) {
        return enumOf(type, stringAt(payload, key, where), where + " " + key);
    }

    private static <E extends Enum<E>> E optionalEnumAt(CanonicalJson.Obj payload, String key,
                                                        Class<E> type, String where) {
        String name = optionalString(payload, key);
        return name == null ? null : enumOf(type, name, where + " " + key);
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String name, String where) {
        if (name == null) {
            throw new Defect(where, "the closed enum position is absent");
        }
        for (E candidate : type.getEnumConstants()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        throw new Defect(where, "unknown closed value \"" + name + "\"");
    }

    private static CanonicalJson.Value at(CanonicalJson.Obj payload, String key, String where) {
        CanonicalJson.Value value = ContractSnapshotCanonicalizer.payloadValue(payload, key);
        if (value == null) {
            throw new Defect(where, "the payload position \"" + key + "\" is absent");
        }
        return value;
    }

    private static CanonicalJson.Obj objectAt(CanonicalJson.Obj payload, String key,
                                              String where) {
        return objectValue(at(payload, key, where), key, where);
    }

    private static CanonicalJson.Obj optionalObjectAt(CanonicalJson.Obj payload, String key,
                                                      String where) {
        CanonicalJson.Value value = at(payload, key, where);
        if (value instanceof CanonicalJson.Null) {
            return null;
        }
        return objectValue(value, key, where);
    }

    private static CanonicalJson.Obj objectValue(CanonicalJson.Value value, String key,
                                                 String where) {
        if (value instanceof CanonicalJson.Obj obj) {
            return obj;
        }
        throw new Defect(where, "\"" + key + "\" must be an object");
    }

    private static CanonicalJson.Arr arrayAt(CanonicalJson.Obj payload, String key,
                                             String where) {
        CanonicalJson.Value value = at(payload, key, where);
        if (value instanceof CanonicalJson.Arr array) {
            return array;
        }
        throw new Defect(where, "\"" + key + "\" must be an array");
    }

    private static String optionalString(CanonicalJson.Obj obj, String key) {
        return ContractSnapshotCanonicalizer.optionalString(obj, key);
    }

    private static <T> T requireNonNull(T value, String where) {
        if (value == null) {
            throw new Defect(where, "the required position is absent");
        }
        return value;
    }
}
