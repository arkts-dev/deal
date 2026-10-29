package deal.codegen.llvm;

import deal.codegen.EmitterSessionBase;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassLayout;
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

    /** The published first emitted layout identity (M1: 1024). */
    static final int EMITTED_LAYOUT_BASE = 1024;

    /**
     * The published runtime-owned array element layouts of the `container`
     * group (`deal/native/memory.h`, C3/R2): the four boxed slots and the two
     * scalar-nullish records, whose `{ tag, base }` shape is the module's own
     * scalar-nullish representation. Both the emitted element access and the
     * runtime JSON decode walk read one of these, so no element storage is
     * module-owned and a decoded array is a value of its declared type.
     */
    static final int ELEMENT_LAYOUT_DATUM = 8;
    static final int ELEMENT_LAYOUT_REFERENCE = 9;
    static final int ELEMENT_LAYOUT_NUMBER = 10;
    static final int ELEMENT_LAYOUT_BOOLEAN = 11;
    static final int ELEMENT_LAYOUT_NULLISH_NUMBER = 12;
    static final int ELEMENT_LAYOUT_NULLISH_BOOLEAN = 13;

    private final ExecutableLoweredProject project;
    private final LlvmEmissionInput input;
    private final Map<Long, LoweredFunction> functionsById = new LinkedHashMap<>();
    private final Map<Long, ModuleId> functionOwner = new LinkedHashMap<>();
    private final Map<Long, List<BindingId>> parameters = new LinkedHashMap<>();
    private final Map<Long, SemanticOp> producerByValue = new LinkedHashMap<>();
    private final Map<Long, ModuleId> valueOwner = new LinkedHashMap<>();
    private List<ModuleId> orderedModules = List.of();
    private final java.util.Set<String> runtimeEntries =
        new java.util.LinkedHashSet<>();
    private final java.util.Set<String> externalGlobals =
        new java.util.LinkedHashSet<>();

    final LlvmData data = new LlvmData();
    final LlvmDebugInfo debug = new LlvmDebugInfo();
    private final LlvmClassPlans classPlans = new LlvmClassPlans(this);
    private final Map<String, LlvmClassPlans.ClassDef> classDefinitions =
        new LinkedHashMap<>();

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
        indexClasses();
        markSkippedEntryOps(project.modules().get(project.entryModule()));
        data.defineUnits(unitModules());
        for (ModuleId module : project.modules().keySet()) {
            debug.compileUnit(displayName(module));
        }
    }

    /**
     * The declared classes of the project closure, in canonical class-text
     * order (C9): every unit's recorded layouts, first occurrence wins, and the
     * builtin {@code Error} class contributes no emitted instance layout (its
     * representation is the runtime's own record).
     */
    private void indexClasses() {
        List<ClassLayout> layouts = new ArrayList<>();
        for (ModuleId module : orderedModules) {
            LoweredModuleUnit unit = project.modules().get(module);
            List<ClassId> ids = new ArrayList<>(unit.classLayouts().keySet());
            ids.sort(java.util.Comparator.comparing(ClassId::text));
            for (ClassId id : ids) {
                layouts.add(unit.classLayouts().get(id));
            }
        }
        for (LlvmClassPlans.ClassDef def : LlvmClassPlans.definitions(layouts)) {
            classDefinitions.putIfAbsent(def.classId().text(), def);
        }
    }

    /** The emitted class plans and static data of this project. */
    LlvmClassPlans classPlans() {
        return classPlans;
    }

    /** Every declared class of the project, in canonical class-text order. */
    List<LlvmClassPlans.ClassDef> classes() {
        return List.copyOf(classDefinitions.values());
    }

    /** One declared class's emitted layout facts. */
    LlvmClassPlans.ClassDef classDefinition(ClassId classId) {
        LlvmClassPlans.ClassDef def = classDefinitions.get(classId.text());
        if (def == null) {
            throw new IllegalStateException("the project declares no emitted"
                + " instance layout for the class " + classId.text());
        }
        return def;
    }

    /**
     * The array element-layout identity of one element descriptor (R2): one of
     * the published runtime-owned element storage layouts. The runtime owns all
     * of them, including the two scalar-nullish records, so an array the emitted
     * module builds and an array the runtime JSON walk rebuilds carry the same
     * storage and the emitted element access reads either one (C3).
     */
    int elementLayout(RuntimeDescriptor element) {
        return switch (element) {
            case RuntimeDescriptor.Number ignored -> ELEMENT_LAYOUT_NUMBER;
            case RuntimeDescriptor.Boolean ignored -> ELEMENT_LAYOUT_BOOLEAN;
            case RuntimeDescriptor.Unknown ignored -> ELEMENT_LAYOUT_DATUM;
            // A `null`/`undefined` element position holds the distinct
            // singleton pointer (C3), the same reference slot every other
            // reference element stores.
            case RuntimeDescriptor.Null ignored -> ELEMENT_LAYOUT_REFERENCE;
            case RuntimeDescriptor.Undefined ignored -> ELEMENT_LAYOUT_REFERENCE;
            case RuntimeDescriptor.Nullable nullable -> {
                String representation = LlvmTypeMapper.representationOf(nullable)
                    .orElseThrow(() -> new IllegalStateException("the array"
                        + " element type '" + element.canonicalSpecText()
                        + "' has no staged storage"));
                if (representation.equals(LlvmTypeMapper.REFERENCE_NULLISH)) {
                    yield ELEMENT_LAYOUT_REFERENCE;
                }
                yield representation.equals(LlvmTypeMapper.NULLISH_NUMBER)
                    ? ELEMENT_LAYOUT_NULLISH_NUMBER
                    : ELEMENT_LAYOUT_NULLISH_BOOLEAN;
            }
            default -> {
                if (LlvmTypeMapper.isManagedPointer(element)) {
                    yield ELEMENT_LAYOUT_REFERENCE;
                }
                throw new IllegalStateException("the array element type '"
                    + element.canonicalSpecText()
                    + "' has no staged element storage");
            }
        };
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

    /** One stdlib function read of a unit and its carrier slot. */
    record StdlibRead(deal.semantic.ir.StdlibFunctionId function,
                      RuntimeDescriptor.Func signature, String slot) {
    }

    /**
     * The distinct stdlib function values one unit reads, in first-read order:
     * each {@code EXPORT_READ} of a stdlib declaration module resolves through
     * the shared catalog, and the unit initializes one carrier per function so
     * repeated reads share one identity.
     */
    List<StdlibRead> stdlibReads(LoweredModuleUnit unit) {
        Map<deal.semantic.ir.StdlibFunctionId, StdlibRead> reads =
            new LinkedHashMap<>();
        for (SemanticOp op : unit.ops()) {
            if (!(op.payload() instanceof KindPayload.ExportReadPayload read)
                    || !(read.descriptor() instanceof RuntimeDescriptor.Func signature)) {
                continue;
            }
            java.util.Optional<deal.semantic.StdlibFunctionCatalog.Entry> entry =
                deal.semantic.StdlibFunctionCatalog.lookup(
                    read.module().path(), read.name());
            if (entry.isEmpty()) {
                continue;
            }
            deal.semantic.ir.StdlibFunctionId function = entry.get().function();
            reads.computeIfAbsent(function, ignored -> new StdlibRead(function,
                signature, data.stdlibSlot(moduleIndex(unit.moduleId()), function)));
        }
        return List.copyOf(reads.values());
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

    /**
     * The registered execution binding of one allocation identity: the
     * project's units share one identity space, so an {@code Indirect} callee
     * of one unit may name an identity whose producing {@code CLOSURE_NEW}
     * sits in another unit of the closure (the same resolution every production
     * backend performs).
     */
    deal.semantic.ir.FunctionExecutionBinding bindingOfIdentity(ValueId identity) {
        for (ModuleId module : orderedModules) {
            LoweredModuleUnit unit = project.modules().get(module);
            deal.semantic.ir.FunctionExecutionBinding found =
                unit.functionBindings().get(
                    new deal.semantic.ir.FunctionAllocationIdentity(identity.id()));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The creation op of one lowered function ({@code CLOSURE_NEW}), or
     * {@code null}: its capture list is the one fact that decides whether the
     * function's value is its own code pointer or a managed environment.
     */
    SemanticOp closureOf(FunctionId functionId) {
        for (ModuleId module : orderedModules) {
            for (SemanticOp op : project.modules().get(module).ops()) {
                if (op.payload() instanceof KindPayload.ClosureNewPayload closure
                        && closure.function().equals(functionId)) {
                    return op;
                }
            }
        }
        return null;
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
     * Whether one op is a payload-owned child: its owner arm emits it, so a
     * block walk that reads a block's own producing value never takes a child's
     * result for the block's value.
     */
    boolean isOwnedChild(deal.semantic.ir.OpId opId) {
        return ownedChildren.contains(opId);
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

    /**
     * Whether one binding generation is carried in a heap cell (C5): every
     * {@code SHARED_CELL} incarnation, every member of one recursive group and
     * every for-of iteration binding (which the shared lowering derives as a
     * {@code SHARED_CELL} incarnation of its own iteration scope). The emitter
     * reads and writes such a binding through the cell, roots the cell, and a
     * {@code CLOSURE_NEW} captures exactly the cells its capture list names.
     */
    boolean cellBinding(LoweredModuleUnit unit, BindingId binding,
                        long generation) {
        for (SemanticOp op : unit.ops()) {
            if (op.payload() instanceof KindPayload.BindingAllocPayload alloc
                    && alloc.binding().equals(binding)
                    && alloc.generation() == generation) {
                return alloc.cellKind()
                    == deal.semantic.ir.BindingCellKind.SHARED_CELL;
            }
            if (op.payload() instanceof KindPayload.ForEachPayload forEach
                    && forEach.binding().equals(binding)
                    && forEach.generation() == generation) {
                return true;
            }
            if (op.payload() instanceof KindPayload.RecursiveGroupInitPayload group
                    && group.bindings().contains(binding)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one binding generation is allocated in some unit's module-init
     * block: its storage is one module-level global slot shared by every unit
     * of the project, so a direct call into another unit's function reads the
     * same cell the callee's own activation does.
     */
    boolean moduleScopedAcrossProject(BindingId binding, long generation) {
        for (LoweredModuleUnit candidate : project.modules().values()) {
            if (moduleScoped(candidate, binding, generation)) {
                return true;
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
        for (SemanticOp op : unit.ops()) {
            if (op.payload() instanceof KindPayload.TryCatchPayload caught
                    && caught.catchBinding().equals(binding)) {
                // docs/spec-v1.5.md try/catch: the catch variable binds the
                // identical Error as `unknown` (C4's handler join).
                return RuntimeDescriptor.Unknown.INSTANCE;
            }
            if (op.payload() instanceof KindPayload.ForEachPayload iteration
                    && iteration.binding().equals(binding)
                    && iteration.generation() == generation) {
                // The iteration binding is written by the FOR_EACH op itself
                // (one fresh binding per iteration), so its declared type is
                // the traversal's own element type (R2, spec for-of).
                if (iteration.mode()
                        == deal.semantic.ir.IterationMode.STRING_SCALARS) {
                    return RuntimeDescriptor.String.INSTANCE;
                }
                SemanticOp iterable = producerOf(iteration.iterable());
                if (iterable == null || !(iterable.resultType()
                        instanceof RuntimeDescriptor.Array arrayType)) {
                    throw new IllegalStateException("the FOR_EACH op "
                        + op.opId() + " iterates a value that is no array");
                }
                return arrayType.element();
            }
            if (op.payload() instanceof KindPayload.RecursiveGroupInitPayload group
                    && group.bindings().contains(binding)) {
                // A recursive-group member is written only by its group's own
                // RECURSIVE_GROUP_INIT, which publishes the member closure of
                // the recorded function at the same index, so the declared
                // type of that incarnation is that function's descriptor (C5:
                // the group's cells are initialized before any call).
                if (group.bindings().size() != group.functions().size()) {
                    throw new IllegalStateException("the recursive group "
                        + op.opId() + " records " + group.bindings().size()
                        + " bindings for " + group.functions().size()
                        + " functions");
                }
                LoweredFunction member = function(
                    group.functions().get(group.bindings().indexOf(binding)));
                return member.descriptor();
            }
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
        RuntimeDescriptor declaredByBoundary = null;
        RuntimeDescriptor produced = null;
        List<SemanticOp> ops = unit.ops();
        for (int index = 0; index < ops.size(); index++) {
            ValueId value = writeValue(ops.get(index), binding, generation);
            if (value == null) {
                continue;
            }
            if (declaredByBoundary == null) {
                declaredByBoundary = declaredBoundaryType(ops, index, value);
            }
            if (produced == null) {
                SemanticOp producer = producerOf(value);
                if (producer == null) {
                    throw new IllegalStateException("the value " + value
                        + " of the binding " + binding + " has no producer");
                }
                if (!(producer.resultType() instanceof RuntimeDescriptor descriptor)) {
                    throw new IllegalStateException("the value " + value
                        + " of the binding " + binding + " carries no descriptor");
                }
                produced = descriptor;
            }
        }
        // The declared position of the binding is the descriptor its own
        // `VARIABLE_DECLARATION`/`VARIABLE_ASSIGNMENT` boundary records, so a
        // widened declaration (`let x: unknown = 1`) keeps the declared type
        // and never the type of the initializing expression.
        return declaredByBoundary != null ? declaredByBoundary : produced;
    }

    /**
     * The stored value of one write op of the binding generation, or
     * {@code null} when the op writes another binding or generation.
     */
    private static ValueId writeValue(SemanticOp op, BindingId binding,
                                      long generation) {
        return switch (op.payload()) {
            case KindPayload.BindingInitPayload init
                    when init.binding().equals(binding)
                        && init.generation() == generation -> init.value();
            case KindPayload.BindingStorePayload store
                    when store.binding().equals(binding)
                        && store.generation() == generation -> store.value();
            default -> null;
        };
    }

    /**
     * The declared descriptor guarding <em>one</em> write of the binding: the
     * `VARIABLE_DECLARATION`/`VARIABLE_ASSIGNMENT` boundary the lowering emits
     * between the write's value and its commit op, so the boundary is found by
     * walking back from the write op. A preceding write of the same value ends
     * the search: that value id already reached its own committed position, so
     * a boundary found beyond it guards the earlier binding and must never be
     * inherited here (the lowering reuses a function-valued identifier's value
     * id for its `BINDING_LOAD`, and two bindings can therefore share one value
     * id).
     */
    private static RuntimeDescriptor declaredBoundaryType(List<SemanticOp> ops,
                                                         int writeIndex,
                                                         ValueId value) {
        for (int index = writeIndex - 1; index >= 0; index--) {
            SemanticOp candidate = ops.get(index);
            if (candidate.payload() instanceof KindPayload.BoundaryPayload boundary
                    && (boundary.kind() == deal.semantic.ir.BoundaryKind
                            .VARIABLE_DECLARATION
                        || boundary.kind() == deal.semantic.ir.BoundaryKind
                            .VARIABLE_ASSIGNMENT)
                    && boundary.input().equals(value)) {
                return boundary.descriptor();
            }
            ValueId earlier = switch (candidate.payload()) {
                case KindPayload.BindingInitPayload init -> init.value();
                case KindPayload.BindingStorePayload store -> store.value();
                default -> null;
            };
            if (value.equals(earlier)) {
                return null;
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
        if (LlvmTypeMapper.isStaticSingleton(descriptor)
                || descriptor instanceof RuntimeDescriptor.Void) {
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

    /**
     * The staged IR representation of one value, or empty when the value owns
     * no representation at all: a {@code null}/{@code undefined} singleton
     * (whose C4 protocol reconstructs the singleton) and a result-only
     * {@code void} (C3 gives it no data slot, and a successful completion
     * projects to undefined only where the result protocol requires it).
     */
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
        if (LlvmTypeMapper.isStaticSingleton(descriptor)
                || descriptor instanceof RuntimeDescriptor.Void) {
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
        for (ModuleId module : modules()) {
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
                    closure.function().id(),
                    closure.signature().isAsync()));
            }
            modules.add(new LlvmData.UnitModule(module.path(), exports));
        }
        return modules;
    }

    /** The index of one unit's published export name in its export table. */
    int exportIndex(ModuleId module, String name) {
        int index = 0;
        for (LlvmData.UnitModule unit : unitModules()) {
            if (!unit.modulePath().equals(module.path())) {
                continue;
            }
            for (LlvmData.UnitExport export : unit.exports()) {
                if (export.name().equals(name)) {
                    return index;
                }
                index++;
            }
        }
        throw new IllegalStateException("the module " + module
            + " publishes no export named '" + name + "'");
    }

    /**
     * The export-table index of one function's published closure, or -1 when
     * the unit publishes no export of that function.
     */
    int exportIndexOfFunction(ModuleId module, FunctionId functionId) {
        int index = 0;
        for (LlvmData.UnitModule unit : unitModules()) {
            if (!unit.modulePath().equals(module.path())) {
                continue;
            }
            for (LlvmData.UnitExport export : unit.exports()) {
                if (export.functionId() == functionId.id()) {
                    return index;
                }
                index++;
            }
        }
        return -1;
    }

    /** The number of export records one unit publishes. */
    int exportCount(ModuleId module) {
        for (LlvmData.UnitModule unit : unitModules()) {
            if (unit.modulePath().equals(module.path())) {
                return unit.exports().size();
            }
        }
        throw new IllegalStateException("the module " + module
            + " is outside the project closure");
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
     * Records one runtime entry the emitted facts reference; the module
     * declares exactly the referenced set, in the published declaration order
     * (C9).
     */
    void referenceRuntimeEntry(String name) {
        runtimeEntries.add(Objects.requireNonNull(name, "name must not be null"));
    }

    /**
     * Records one published external global the emitted facts reference (the
     * two distinct nullish singletons of {@code value.h}).
     */
    void referenceExternalGlobal(String name) {
        externalGlobals.add(Objects.requireNonNull(name, "name must not be null"));
    }

    /** The referenced external globals, in the order first referenced. */
    List<String> externalGlobals() {
        return List.copyOf(externalGlobals);
    }

    /**
     * The runtime entries the emitted module declares: the activation and
     * arm-table baseline plus every entry the emitted facts reference, in the
     * published declaration order so the declaration set derives from the IR
     * and never from an iteration order.
     */
    List<String> referencedRuntimeEntries() {
        java.util.Set<String> all =
            new java.util.LinkedHashSet<>(BASE_ENTRIES);
        all.addAll(runtimeEntries);
        List<String> ordered = new ArrayList<>();
        for (LlvmRuntimeAbi.Declaration declaration
                : LlvmRuntimeAbi.declarations()) {
            if (all.contains(declaration.name())) {
                ordered.add(declaration.name());
            }
        }
        for (String name : all) {
            if (!ordered.contains(name)) {
                throw new IllegalStateException(
                    "the referenced runtime entry " + name + " is not published");
            }
        }
        return List.copyOf(ordered);
    }

    /** The entries every emitted activation references (C2, CallFrameContract). */
    private static final List<String> BASE_ENTRIES = List.of(
        "deal_arm_table_install",
        "deal_call_frame_push",
        "deal_call_frame_pop",
        "deal_root_frame_enter",
        "deal_root_frame_leave");

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
