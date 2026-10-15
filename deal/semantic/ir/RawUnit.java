package deal.semantic.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The raw unit of the validator's intermediate model (schema S6) under the
 * complete {@code deal.semantic-ir/2} envelope: the validator's pinned
 * input over both surfaces. The typed surface produces it through
 * {@link #fromTyped(LoweredModuleUnit)} (enum names and payload JSON
 * extracted through the canonicalizer); the text surface produces it
 * through the single canonical JSON parser via
 * {@link ContractSnapshotCanonicalizer#parseUnit(CanonicalJson.Obj)}
 * with every closed enum position carried as a raw string. The closed
 * rule engine consumes exactly this model on both surfaces.
 *
 * <p><b>Complete content.</b> Beyond the header and the produced
 * operations, the raw unit owns every complete /2 companion:</p>
 *
 * <ul>
 *   <li>{@link #classLayouts()} — one {@link RawClassLayout} per registry
 *       entry, keeping the registry {@code key} and the contained
 *       {@code layout.classId} as independent facts, plus the ordered
 *       layout field rows (name, descriptor text, presence, raw default
 *       owner).</li>
 *   <li>{@link #functions()} — one {@link RawFunction} per registry
 *       entry, keeping the registry {@code key} and the contained
 *       {@code functionId} as independent facts, plus the descriptor
 *       text, the ordered generation-pinned captures, the body block and
 *       the source name (explicit {@code null} means anonymous).</li>
 *   <li>{@link #moduleInit()} — the ordered resolved imports and the
 *       init block; repeated import declarations remain repeated
 *       entries.</li>
 *   <li>{@link #exportPlan()} — the array-form export entries (name,
 *       declared descriptor text, publishing op); an empty plan stays
 *       present and explicit.</li>
 *   <li>{@link #bindings()} — the complete allocation/binding registry
 *       with every binding variant member.</li>
 *   <li>{@link #ops()} — the produced operations in source order.</li>
 *   <li>{@link #bodyTable()} — both membership directions, transported
 *       independently and never rebuilt from one another.</li>
 *   <li>{@link #classFactories()}, {@link #jsonDefaults()} and
 *       {@link #classBindingFacts()} — the complete unit companions
 *       under their existing names.</li>
 * </ul>
 *
 * <p>Every mandatory companion is a real fact of every unit: an absent
 * companion is a decode failure, never an empty collection. Registry
 * keys and contained identities remain separate facts; admission rejects
 * a disagreement instead of repairing it.</p>
 *
 */
public record RawUnit(
    String modulePath,
    String semanticProfile,
    String interfaceHash,
    String loweringContextHash,
    List<String> requiredCapabilities,
    List<RawCoverage> coverage,
    List<RawClassLayout> classLayouts,
    List<RawFunction> functions,
    ModuleInitPlan moduleInit,
    List<RawExportEntry> exportPlan,
    List<RawBinding> bindings,
    List<RawOp> ops,
    StructuredBodyTable bodyTable,
    ClassFactoryRegistry classFactories,
    JsonDefaultChildTable jsonDefaults,
    ClassBindingFacts classBindingFacts
) {

    public RawUnit {
        Objects.requireNonNull(modulePath, "modulePath must not be null");
        Objects.requireNonNull(semanticProfile, "semanticProfile must not be null");
        Objects.requireNonNull(interfaceHash, "interfaceHash must not be null");
        Objects.requireNonNull(loweringContextHash, "loweringContextHash must not be null");
        requiredCapabilities =
            List.copyOf(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities must not be null"));
        coverage = List.copyOf(Objects.requireNonNull(coverage, "coverage must not be null"));
        classLayouts = List.copyOf(Objects.requireNonNull(classLayouts, "classLayouts must not be null"));
        functions = List.copyOf(Objects.requireNonNull(functions, "functions must not be null"));
        Objects.requireNonNull(moduleInit, "moduleInit must not be null");
        exportPlan = List.copyOf(Objects.requireNonNull(exportPlan, "exportPlan must not be null"));
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings must not be null"));
        ops = List.copyOf(Objects.requireNonNull(ops, "ops must not be null"));
        Objects.requireNonNull(bodyTable, "bodyTable must not be null");
        Objects.requireNonNull(classFactories, "classFactories must not be null");
        Objects.requireNonNull(jsonDefaults, "jsonDefaults must not be null");
        Objects.requireNonNull(classBindingFacts, "classBindingFacts must not be null");
    }

    /**
     * The typed-surface converter: maps a closed
     * {@link LoweredModuleUnit} onto the raw model — enum values render as
     * their names, descriptors as canonical spec texts, payloads through
     * {@link ContractSnapshotCanonicalizer#payloadJson}, and contracts
     * through {@link ContractSnapshotCanonicalizer#toJson}. The typed
     * enums are closed, so every raw name is a valid closed value here;
     * the invalid-value classes are injectable only through the text
     * surface. Every registry key is copied beside its record's own
     * identity, so a key/record disagreement survives conversion
     * unchanged.
     *
     */
    public static RawUnit fromTyped(LoweredModuleUnit unit) {
        Objects.requireNonNull(unit, "unit must not be null");
        List<String> capabilities = new ArrayList<>();
        for (SemanticCapability capability : unit.requiredCapabilities()) {
            capabilities.add(capability.name());
        }
        List<RawCoverage> coverage = new ArrayList<>();
        for (Map.Entry<ConstructKind, List<SemanticOpKind>> entry
                : unit.constructCoverage().entrySet()) {
            List<String> kinds = new ArrayList<>();
            for (SemanticOpKind kind : entry.getValue()) {
                kinds.add(kind.name());
            }
            coverage.add(new RawCoverage(entry.getKey().name(), kinds));
        }
        List<RawClassLayout> classLayouts = new ArrayList<>();
        for (Map.Entry<ClassId, ClassLayout> entry : unit.classLayouts().entrySet()) {
            ClassLayout layout = entry.getValue();
            List<RawFieldLayout> fields = new ArrayList<>();
            for (ClassLayout.FieldLayout field : layout.fields()) {
                fields.add(new RawFieldLayout(field.name(),
                    field.descriptor().canonicalSpecText(), field.required(),
                    field.defaultOwner().name()));
            }
            classLayouts.add(new RawClassLayout(entry.getKey(),
                new RawLayout(layout.classId(), fields)));
        }
        List<RawFunction> functions = new ArrayList<>();
        for (Map.Entry<FunctionId, LoweredFunction> entry : unit.functions().entrySet()) {
            LoweredFunction function = entry.getValue();
            functions.add(new RawFunction(entry.getKey(), function.functionId(),
                function.descriptor().canonicalSpecText(), function.captures(),
                function.body(), function.sourceName()));
        }
        List<RawExportEntry> exportPlan = new ArrayList<>();
        for (ExportPlan.ExportPlanEntry entry : unit.exportPlan().entries()) {
            exportPlan.add(new RawExportEntry(entry.name(),
                entry.descriptor().canonicalSpecText(), entry.publishOpId()));
        }
        List<RawBinding> bindings = new ArrayList<>();
        for (Map.Entry<FunctionAllocationIdentity, FunctionExecutionBinding> entry
                : unit.functionBindings().entrySet()) {
            bindings.add(bindingFromTyped(entry.getKey().id(), entry.getValue()));
        }
        List<RawOp> ops = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            ops.add(opFromTyped(op));
        }
        return new RawUnit(unit.moduleId().path(), unit.semanticProfile().name(),
            unit.interfaceHash(), unit.loweringContextHash(), capabilities, coverage,
            classLayouts, functions, unit.moduleInit(), exportPlan, bindings, ops,
            unit.bodyTable(), unit.classFactories(), unit.jsonDefaults(),
            unit.classBindingFacts());
    }

    private static RawBinding bindingFromTyped(long allocationId, FunctionExecutionBinding binding) {
        return switch (binding) {
            case FunctionExecutionBinding.LoweredBody body ->
                new RawBinding(allocationId, "loweredBody", null, null, null, null, null,
                    null, null, null, null, body.functionId(), body.blockId(), null, null);
            case FunctionExecutionBinding.AdapterBinding adapter ->
                new RawBinding(allocationId, "adapter", null, null, null,
                    adapter.captureMode().name(), null,
                    adapter.sourceSignature().canonicalSpecText(),
                    adapter.targetSignature().canonicalSpecText(), null, null, null, null,
                    adapter.adaptOpId(),
                    ContractSnapshotCanonicalizer.rawSourceRefOf(adapter.sourceRef()));
            case FunctionExecutionBinding.HostFunction host ->
                new RawBinding(allocationId, "hostFunction", host.hostModuleId().path(),
                    host.exportName(), null, null, host.descriptor().canonicalSpecText(), null,
                    null, null, null, null, null, null, null);
            case FunctionExecutionBinding.HostFunctionValue hostValue ->
                new RawBinding(allocationId, "hostFunctionValue", hostValue.hostModuleId().path(),
                    null, null, null, hostValue.descriptor().canonicalSpecText(), null, null,
                    hostValue.materializingBoundaryOpId(), null, null, null, null, null);
            case FunctionExecutionBinding.ExternalFunction external ->
                new RawBinding(allocationId, "externalFunction", external.moduleId().path(),
                    external.exportName(), external.executionOwner().name(), null,
                    external.descriptor().canonicalSpecText(), null, null, null, null, null,
                    null, null, null);
            case FunctionExecutionBinding.IntrinsicFunction intrinsic ->
                new RawBinding(allocationId, "intrinsicFunction", null, null, null, null,
                    intrinsic.descriptor().canonicalSpecText(), null, null, null,
                    intrinsic.kind().name(), null, null, null, null);
            case FunctionExecutionBinding.DynamicFunctionValue dynamic ->
                new RawBinding(allocationId, "dynamicFunctionValue", null, null, null, null,
                    dynamic.descriptor().canonicalSpecText(), null, null,
                    dynamic.materializingOpId(), null, null, null, null, null);
        };
    }

    private static RawOp opFromTyped(SemanticOp op) {
        CanonicalJson.Value payloadValue = ContractSnapshotCanonicalizer.payloadJson(op.payload());
        CanonicalJson.Obj payload = (CanonicalJson.Obj) payloadValue;
        OperationContractSnapshot contract = op.contract();
        String selector = contract.selector() == null
            ? null
            : ContractSnapshotCanonicalizer.selectorName(contract.selector());
        ValueId resultValue = null;
        AsyncTokenId resultToken = null;
        if (op.result() instanceof ValueId value) {
            resultValue = value;
        } else if (op.result() instanceof AsyncTokenId token) {
            resultToken = token;
        }
        String resultType = null;
        if (op.resultType() instanceof RuntimeDescriptor descriptor) {
            resultType = descriptor.canonicalSpecText();
        } else if (op.resultType() instanceof InternalResultType internal) {
            resultType = internal.name();
        }
        List<String> operandTypes = new ArrayList<>();
        for (RuntimeDescriptor descriptor : op.operandTypes()) {
            operandTypes.add(descriptor.canonicalSpecText());
        }
        return new RawOp(op.opId(), op.kind().name(), op.failurePolicy().name(),
            op.origin().parentOpId(), resultValue, resultToken, resultType,
            op.operands(), operandTypes, payload, selector, contract.canonicalDigest(),
            (CanonicalJson.Obj) ContractSnapshotCanonicalizer.toJson(contract),
            RawOrigin.of(op.origin()));
    }

    /**
     * One raw class-layout registry entry of the complete /2 mapping: the
     * registry {@code key} and the contained layout data are independent
     * facts, so a disagreement between them survives conversion. The
     * layout data uses the existing layout mapping: the contained class
     * identity and the ordered field rows.
     */
    public record RawClassLayout(ClassId key, RawLayout layout) {

        public RawClassLayout {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(layout, "layout must not be null");
        }
    }

    /** The contained layout data: the record's own class identity and its ordered fields. */
    public record RawLayout(ClassId classId, List<RawFieldLayout> fields) {

        public RawLayout {
            Objects.requireNonNull(classId, "classId must not be null");
            fields = List.copyOf(Objects.requireNonNull(fields, "fields must not be null"));
        }
    }

    /**
     * One class-layout field row: the declared name, the raw descriptor
     * text, presence, and the raw default-owner name (a closed enum
     * position carried as text so an open value reaches the validator's
     * rule check).
     */
    public record RawFieldLayout(
        String name,
        String descriptor,
        boolean required,
        String defaultOwner
    ) {

        public RawFieldLayout {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(descriptor, "descriptor must not be null");
            Objects.requireNonNull(defaultOwner, "defaultOwner must not be null");
        }
    }

    /**
     * One raw function registry entry of the complete /2 mapping: the
     * registry {@code key} and the contained {@code functionId} are
     * independent facts, so a disagreement between them survives
     * conversion; the descriptor text, ordered generation-pinned
     * captures, body block and source name complete the record.
     */
    public record RawFunction(
        FunctionId key,
        FunctionId functionId,
        String descriptor,
        List<BindingGeneration> captures,
        BlockId body,
        String sourceName
    ) {

        public RawFunction {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(functionId, "functionId must not be null");
            Objects.requireNonNull(descriptor, "descriptor must not be null");
            captures = List.copyOf(Objects.requireNonNull(captures, "captures must not be null"));
            Objects.requireNonNull(body, "body must not be null");
        }
    }

    /**
     * One export-plan entry of the array-form plan: the export name, the
     * raw declared descriptor text, and the publishing operation.
     */
    public record RawExportEntry(String name, String descriptor, OpId publishOpId) {

        public RawExportEntry {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(descriptor, "descriptor must not be null");
            Objects.requireNonNull(publishOpId, "publishOpId must not be null");
        }
    }
}
