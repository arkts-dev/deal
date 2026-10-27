package deal.codegen.llvm;

import deal.codegen.EmitterSessionBase;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.FrameLabels;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.ValueId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The native rendering session ({@code llvm-native-codegen-cfg-ssa}
 * Architecture, {@code LlvmSession}): the one project-scoped fact set the
 * emitted module derives from.
 *
 * <p>It extends the shared {@link EmitterSessionBase} for the closure, op and
 * body bookkeeping every production backend shares — the unit/table/registry
 * registration, the block-membership lookups, the derived cell kinds and the
 * frame labels — and adds only what the native representation needs: the
 * lowered type of every value and binding, the staged native representation of
 * each, the per-function parameter bindings in declaration order, the
 * published export set and the static-data/debug owners.</p>
 *
 * <p>Two rules keep the mapping honest: a value's representation comes from
 * its recorded result type through {@link LlvmTypeMapper}, never from the
 * consuming op; and a binding's representation comes from its recorded role
 * (an import alias holds the emitted module record, a function parameter holds
 * its declared parameter type, any other binding holds the type of the value
 * its {@code BINDING_INIT}/{@code BINDING_STORE} supplies).</p>
 */
final class LlvmSession extends EmitterSessionBase {

    /** The one marker of a binding that holds an emitted module record. */
    static final String MODULE_RECORD_TYPE = "%struct.deal_module*";

    private final ExecutableLoweredProject project;
    private final LlvmEmissionInput input;
    private final Map<Long, LoweredFunction> functionsById = new LinkedHashMap<>();
    private final Map<Long, ModuleId> functionOwner = new LinkedHashMap<>();
    private final Map<Long, List<BindingId>> parameters = new LinkedHashMap<>();
    private final Map<Long, SemanticOp> producerByValue = new LinkedHashMap<>();
    private final Map<Long, ModuleId> valueOwner = new LinkedHashMap<>();
    private List<ModuleId> orderedModules = List.of();

    final LlvmData data = new LlvmData();
    final LlvmDebugInfo debug = new LlvmDebugInfo();

    LlvmSession(LlvmEmissionInput input) {
        super(input.project().modules().get(input.project().entryModule()),
            input.tables().get(input.project().entryModule()));
        this.input = input;
        this.project = input.project();
        index();
    }

    /** Builds the project indexes the emitter reads. */
    private void index() {
        // The emission order is the module-id path order: it derives from the
        // IR facts alone (C9), never from a map's iteration order, and every
        // unit's own init function initializes its imports first, so the
        // emitted project start still runs the units in dependency order.
        List<ModuleId> ordered = new ArrayList<>(project.modules().keySet());
        ordered.sort(java.util.Comparator.comparing(ModuleId::path));
        this.orderedModules = List.copyOf(ordered);
        for (ModuleId module : project.modules().keySet()) {
            LoweredModuleUnit unit = project.modules().get(module);
            registerUnit(unit, input.tables().get(module),
                input.registries().get(module));
            for (LoweredFunction function : unit.functions().values()) {
                functionsById.put(function.functionId().id(), function);
                functionOwner.put(function.functionId().id(), module);
            }
            for (SemanticOp op : unit.ops()) {
                if (op.result() instanceof ValueId value) {
                    producerByValue.putIfAbsent(value.id(), op);
                    valueOwner.putIfAbsent(value.id(), module);
                }
            }
            for (LoweredFunction function : unit.functions().values()) {
                List<BindingId> functionParameters = new ArrayList<>();
                for (SemanticOp op : unit.ops()) {
                    if (op.kind() == SemanticOpKind.BINDING_ALLOC
                            && op.payload() instanceof KindPayload
                                .BindingAllocPayload alloc
                            && unit.classBindingFacts().parameters()
                                .contains(alloc.binding())
                            && alloc.scope().equals(function.body())) {
                        functionParameters.add(alloc.binding());
                    }
                }
                parameters.put(function.functionId().id(),
                    List.copyOf(functionParameters));
            }
        }
        markSkippedEntryOps(project.modules().get(project.entryModule()));
        data.defineUnits(unitModules());
        for (ModuleId module : project.modules().keySet()) {
            debug.compileUnit(displayName(module));
        }
    }

    // =========================================================================
    // Project facts
    // =========================================================================

    /** The one lowered project. */
    ExecutableLoweredProject project() {
        return project;
    }

    /** The modules of the project closure, in deterministic emission order. */
    List<ModuleId> modules() {
        return orderedModules;
    }

    /** The unit of one module. */
    LoweredModuleUnit unitOf(ModuleId module) {
        LoweredModuleUnit unit = project.modules().get(module);
        if (unit == null) {
            throw new IllegalStateException(
                "the lowered project carries no unit for " + module);
        }
        return unit;
    }

    /** The project-relative display name of one constituent source. */
    String displayName(ModuleId module) {
        return input.displayNameOf(sourceIdOf(unitOf(module)));
    }

    /**
     * The compiled source id of one unit: the source id its recorded origins
     * carry (the unit record itself names no source file).
     */
    private static String sourceIdOf(LoweredModuleUnit unit) {
        for (SemanticOp op : unit.ops()) {
            if (op.origin().kind() == deal.semantic.ir.SourceOriginKind.USER) {
                return op.origin().sourceId();
            }
        }
        return unit.ops().isEmpty() ? "" : unit.ops().get(0).origin().sourceId();
    }

    /** The display name of one origin's source, for the origin records. */
    String displayNameOfSource(String sourceId) {
        return input.displayNameOf(sourceId);
    }

    /** The module that owns one function id. */
    ModuleId ownerOf(FunctionId functionId) {
        ModuleId owner = functionOwner.get(functionId.id());
        if (owner == null) {
            throw new IllegalStateException("the project carries no unit for the"
                + " lowered function " + functionId);
        }
        return owner;
    }

    /** One lowered function by id. */
    LoweredFunction function(FunctionId functionId) {
        LoweredFunction function = functionsById.get(functionId.id());
        if (function == null) {
            throw new IllegalStateException("the project carries no lowered"
                + " function " + functionId);
        }
        return function;
    }

    /** The parameter bindings of one function, in declaration order. */
    List<BindingId> parametersOf(FunctionId functionId) {
        return parameters.getOrDefault(functionId.id(), List.of());
    }

    /** The producing op of one value, or {@code null} when producer-less. */
    SemanticOp producerOf(ValueId value) {
        return producerByValue.get(value.id());
    }

    /** The frame label of one function ({@code <modulePath>#<name>@F<id>}). */
    String labelOf(FunctionId functionId) {
        LoweredFunction function = function(functionId);
        return FrameLabels.of(ownerOf(functionId).path(), functionId,
            function.sourceName());
    }

    /** The block id of one function body. */
    BlockId bodyOf(FunctionId functionId) {
        return function(functionId).body();
    }

    /**
     * The member ops of one block in its owning unit's table. A block without
     * a membership row is a producer defect and fails closed.
     */
    List<SemanticOp> memberOps(BlockId block) {
        deal.semantic.ir.StructuredBodyTable table = blockTableOf.get(block);
        if (table == null) {
            throw new IllegalStateException("block " + block
                + " has no membership row in its owning unit's table");
        }
        List<deal.semantic.ir.OpId> members = table.blockOps().get(block);
        if (members == null) {
            throw new IllegalStateException("block " + block
                + " has no membership row in its owning unit's table");
        }
        List<SemanticOp> ops = new ArrayList<>(members.size());
        for (deal.semantic.ir.OpId member : members) {
            SemanticOp op = opsById.get(member);
            if (op == null) {
                throw new IllegalStateException("block " + block
                    + " names the unknown op " + member);
            }
            ops.add(op);
        }
        return ops;
    }

    /** Whether one op is skipped entirely (the entry delegation of ENTRY_INVOKE). */
    boolean isSkipped(deal.semantic.ir.OpId opId) {
        return skippedOps.contains(opId);
    }

    /**
     * Whether one binding's allocation lives in the unit's module-init block,
     * so its storage is module-level rather than per-activation.
     */
    boolean moduleScoped(LoweredModuleUnit unit, BindingId binding,
                         long generation) {
        BlockId initBlock = unit.moduleInit().initBlock();
        for (SemanticOp op : unit.ops()) {
            if (op.payload() instanceof KindPayload.BindingAllocPayload alloc
                    && alloc.binding().equals(binding)
                    && alloc.generation() == generation) {
                return alloc.scope().equals(initBlock);
            }
        }
        return false;
    }

    /** Whether one binding is written by a {@code BINDING_STORE} anywhere. */
    boolean reassigned(LoweredModuleUnit unit, BindingId binding) {
        for (SemanticOp op : unit.ops()) {
            if (op.payload() instanceof KindPayload.BindingStorePayload store
                    && store.binding().equals(binding)) {
                return true;
            }
        }
        return false;
    }

    /** Whether one binding is one unit's import alias. */
    boolean isImportAlias(LoweredModuleUnit unit, BindingId binding) {
        return unit.classBindingFacts().importAliases().contains(binding);
    }

    /**
     * The lowered type of one binding generation inside one unit: an import
     * alias holds the module record (no DEAL type), a parameter holds its
     * declared parameter type, and every other binding holds the type of the
     * value its initializing write supplies.
     */
    RuntimeDescriptor bindingType(LoweredModuleUnit unit, BindingId binding,
                                  long generation) {
        if (isImportAlias(unit, binding)) {
            return null;
        }
        for (LoweredFunction function : unit.functions().values()) {
            List<BindingId> functionParameters = parametersOf(function.functionId());
            int index = functionParameters.indexOf(binding);
            if (index >= 0) {
                List<RuntimeDescriptor> types = function.descriptor().paramTypes();
                if (index >= types.size()) {
                    throw new IllegalStateException("the function "
                        + function.functionId() + " records " + types.size()
                        + " parameter types for " + functionParameters.size()
                        + " parameter bindings");
                }
                return types.get(index);
            }
        }
        for (SemanticOp op : unit.ops()) {
            ValueId value = switch (op.payload()) {
                case KindPayload.BindingInitPayload init
                        when init.binding().equals(binding)
                            && init.generation() == generation -> init.value();
                case KindPayload.BindingStorePayload store
                        when store.binding().equals(binding)
                            && store.generation() == generation -> store.value();
                default -> null;
            };
            if (value != null) {
                SemanticOp producer = producerOf(value);
                if (producer == null) {
                    throw new IllegalStateException("the value " + value
                        + " of the binding " + binding + " has no producer");
                }
                if (producer.resultType() instanceof RuntimeDescriptor descriptor) {
                    return descriptor;
                }
                throw new IllegalStateException("the value " + value
                    + " of the binding " + binding + " carries no descriptor");
            }
        }
        return null;
    }

    /**
     * The staged IR representation of one binding generation, or empty when
     * the binding carries no representation (a {@code null}/{@code undefined}
     * binding). An unresolvable binding is a producer defect.
     */
    java.util.Optional<String> bindingRepresentation(LoweredModuleUnit unit,
                                                     BindingId binding,
                                                     long generation) {
        if (isImportAlias(unit, binding)) {
            return java.util.Optional.of(MODULE_RECORD_TYPE);
        }
        RuntimeDescriptor descriptor = bindingType(unit, binding, generation);
        if (descriptor == null) {
            throw new IllegalStateException("the binding " + binding
                + " names no declared type in the unit " + unit.moduleId());
        }
        if (LlvmTypeMapper.isStaticSingleton(descriptor)) {
            return java.util.Optional.empty();
        }
        java.util.Optional<String> representation =
            LlvmTypeMapper.representationOf(descriptor);
        if (representation.isEmpty()) {
            throw new IllegalStateException("the binding " + binding
                + " has the managed representation " + descriptor
                .canonicalSpecText());
        }
        return representation;
    }

    /** The staged IR representation of one value, or empty for a singleton. */
    java.util.Optional<String> valueRepresentation(LoweredModuleUnit unit,
                                                   ValueId value) {
        SemanticOp producer = producerOf(value);
        if (producer == null) {
            throw new IllegalStateException("the value " + value
                + " has no producing op in the lowered project");
        }
        if (!(producer.resultType() instanceof RuntimeDescriptor descriptor)) {
            throw new IllegalStateException("the value " + value
                + " carries no descriptor");
        }
        if (LlvmTypeMapper.isStaticSingleton(descriptor)) {
            return java.util.Optional.empty();
        }
        java.util.Optional<String> representation =
            LlvmTypeMapper.representationOf(descriptor);
        if (representation.isEmpty()) {
            throw new IllegalStateException("the value " + value
                + " has the managed representation "
                + descriptor.canonicalSpecText());
        }
        return representation;
    }

    // =========================================================================
    // The published export set
    // =========================================================================

    /**
     * The unit modules and their published exports, in project and op order:
     * one export per recorded {@code EXPORT_PUBLISH} whose value is a
     * no-capture closure of a zero-arity lowered body (the classifier has
     * already accepted it).
     */
    private List<LlvmData.UnitModule> unitModules() {
        List<LlvmData.UnitModule> modules = new ArrayList<>();
        for (ModuleId module : project.modules().keySet()) {
            LoweredModuleUnit unit = project.modules().get(module);
            List<LlvmData.UnitExport> exports = new ArrayList<>();
            for (SemanticOp op : unit.ops()) {
                if (!(op.payload() instanceof KindPayload.ExportPublishPayload published)) {
                    continue;
                }
                SemanticOp producer = producerOf(published.value());
                if (producer == null
                        || !(producer.payload() instanceof KindPayload
                            .ClosureNewPayload closure)) {
                    continue;
                }
                exports.add(new LlvmData.UnitExport(published.name(),
                    published.descriptor().canonicalSpecText(),
                    closure.function().id()));
            }
            modules.add(new LlvmData.UnitModule(module.path(), exports));
        }
        return modules;
    }

    /** The index of one module in the project order. */
    long moduleIndex(ModuleId module) {
        int index = modules().indexOf(module);
        if (index < 0) {
            throw new IllegalStateException(
                "the module " + module + " is outside the project closure");
        }
        return index;
    }

    /**
     * The export-set copy of this session, so a caller (the emission test)
     * can read the published export facts without reaching into the emitter.
     */
    List<LlvmData.UnitModule> publishedExports() {
        return unitModules();
    }

    // =========================================================================
    // The EmitterSessionBase naming hooks
    // =========================================================================

    @Override
    protected String slot(ValueId id) {
        return "%v" + id.id();
    }

    @Override
    protected String cell(BindingId id, long generation) {
        return "%c" + id.id() + "_" + generation;
    }

    @Override
    protected String sourceCommentMarker() {
        return ";";
    }

    /** One unit's registered factory registry (unused by the staged subset). */
    ClassFactoryRegistry registryOf(ModuleId module) {
        return Objects.requireNonNull(registries.get(module),
            "the unit " + module + " has no factory registry");
    }
}
