package deal.codegen.js;

import deal.codegen.SharedEmissionFailure;
import deal.codegen.SourceMapGenerator;
import deal.codegen.SourceMapRecorder;
import deal.diagnostics.DiagnosticRange;
import deal.semantic.StdlibFunctionCatalog;
import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.AsyncTokenId;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.ChainOperandCompletion;
import deal.semantic.ir.ClassFactoryId;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassInterface;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.ControlSelector;
import deal.semantic.ir.ExternalExecutionOwner;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FunctionAllocationIdentity;
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
import deal.semantic.ir.OpResultType;
import deal.semantic.ir.ParameterBoundaryMode;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.StdlibFunctionId;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;
import deal.semantic.ClassRegistrationSeeds;

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
 * creation ops; the account chains; the direct, indirect, host and
 * external calls (the host callee is the recorded source import
 * occurrence's typed surface entry — never a module/name lookup); the
 * runtime-resolved dynamic callee call/async start (the carrier's private
 * class tag selects the class path and the recorded per-class return cell
 * per the shared resolution protocol); the native Promise
 * {@code ASYNC_START}/{@code AWAIT} realization with the single
 * await-site completion check; the host-driven callback crossing of
 * {@code CALLBACK_INVOKE}; the structured control-flow ops; the module
 * init/import/export/entry ops; and the class family — per-attempt
 * default thunks, local and imported-provider construction
 * ({@code CLASS_NEW} under the LOCAL, SHARED_FACTORY and builtin owners),
 * the private defaults-only factory transfer ({@code CLASS_FACTORY}), the
 * presence-aware field reads/writes/deletes, the JSON from/to walkers over
 * the canonical descriptor arrays, and the public class surface (META, the
 * full-construction {@code C$new} and the hidden {@code C$fields} export
 * of a {@code @jsonable} class). The builtin {@code Error} construction is
 * realized because {@code THROW} transport needs the tagged error carrier.
 * The HOST/STDLIB boundary is realized from the admitted occurrence facts
 * and the structural declaration surface: one raw Node require per
 * resolved host file, one validating {@code $rt.loadHost} per source HOST
 * import declaration (the occurrence-owned typed surface carrying fresh
 * wrappers and synthesized {@code C$new}/{@code C$fields} over the raw
 * META/defaults references), every host read and call through that
 * occurrence's own surface entry, the once-only stdlib surface behind the
 * recorded relative require, the cataloged {@code STDLIB_CALL} rows over
 * the deployed stdlib modules, and the host-declaration class construction
 * through the occurrence's synthesized {@code C$new}. The
 * {@code RETAINED_ABI} external calls belong to a later decomposition unit
 * of the same migration and fail closed here. This renderer is the public
 * JavaScript production arm: {@link
 * deal.module.ProductionProjectEmission} invokes it exactly once over the
 * one validated, lowered project for {@code Backend.JS}; no AST emitter
 * exists and no invocation purpose selects an alternate rendering.</p>
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

        /** One binding incarnation: the home cell of a seeded carrier. */
        private record SeedCell(BindingId binding, long generation) {
        }

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
        /**
         * The recorded import kind of every module the whole closure
         * resolves (one kind per resolved module by construction): the
         * read and callee arms select the closed kind from the recorded
         * resolution, never from a specifier spelling or a module name.
         */
        private final Map<ModuleId, ModuleImportKind> importKinds =
            new LinkedHashMap<>();
        /**
         * The artifact's stdlib surface binding per imported stdlib
         * module: the module-scope require binding of that module's first
         * recorded STDLIB {@code MODULE_IMPORT} in this artifact. The
         * deployed stdlib module is once-only (Node's module cache returns
         * the identical object for every relative require of the same
         * deployed file), so its reads, direct cataloged calls and stored
         * call forms all resolve the one surface entry.
         */
        private final Map<ModuleId, String> stdlibSurfaces =
            new LinkedHashMap<>();
        /**
         * The artifact's first HOST import occurrence per resolved
         * declaration module: the alias cell of that occurrence. Each
         * source HOST import owns one typed surface, so a
         * host-declaration class construction runs through that
         * occurrence's synthesized {@code C$new} instead of re-loading or
         * re-wrapping the host module.
         */
        private final Map<ModuleId, BindingId> hostOccurrences =
            new LinkedHashMap<>();
        /**
         * The one binding cell that materializes each seeded intrinsic
         * carrier: the lowerer's root-scope seed {@code BINDING_INIT}. The
         * seed's own init publishes the wrapper value there, and every
         * other materialization of the identity (an identity-preserving
         * load, the adapter's VALUE operand) reads that cell, so one
         * intrinsic is one value and reference identity holds.
         */
        private final Map<Long, SeedCell> intrinsicSeedCells = new HashMap<>();
        /** The binding cell holding each lowered function's closure. */
        private final Map<Long, String> closureBindings = new HashMap<>();
        private final Map<Long, LoweredFunction> functions = new HashMap<>();
        /** Parameter bindings of one lowered function, in JS parameter order. */
        private final Map<Long, List<BindingId>> functionParameters =
            new HashMap<>();
        private final Set<OpId> structuralOwned = new HashSet<>();
        private final Set<OpId> ownedChildren = new HashSet<>();
        private final Set<OpId> skippedOps = new HashSet<>();
        // ----- Project class facts (validated layouts/owners/export surfaces) -----
        /**
         * Every declaration's layout of one canonical class identity, in
         * project order. Two declarations of one identity (a
         * harness-synthesized pair; a resolved source file is its own
         * identity) may declare different fields, so no identity-keyed
         * single layout is a declaration: a construction, factory or JSON
         * helper resolves the layout of its own declaring unit (or of the
         * factory/registration owner it records).
         */
        private final Map<ClassId, List<ClassLayout>> declaredLayouts =
            new LinkedHashMap<>();
        /** The declaring units of one canonical class identity, project order. */
        private final Map<ClassId, List<ModuleId>> declaringModules =
            new LinkedHashMap<>();
        /**
         * The plan-bearing ({@code @jsonable}) declaring unit of each
         * identity, by project order: the unique declaration that generates
         * the identity's JSON helpers. A multiple-declaration identity
         * carries at most one here when the shared admission admits it; a
         * project whose JSON plan key is ambiguous fails closed before
         * rendering.
         */
        private final Map<ClassId, List<ModuleId>> jsonPlanBearers =
            new LinkedHashMap<>();
        /**
         * The declaration unit whose layout the canonical JSON plan key
         * carries: the unique plan-bearing declaration, or (for an identity
         * the shared admission never lets a nested link select) the
         * emission order's last declaration. Every cross-module
         * {@code $fields} read resolves this owner's published surface,
         * never a first-seen sibling.
         */
        private final Map<ClassId, ModuleId> jsonPlanOwners =
            new LinkedHashMap<>();
        /** Declaration-module registration layouts (HOST/extern-C/builtin). */
        private final Map<ClassId, ClassLayout> seedLayouts =
            new LinkedHashMap<>();
        /**
         * The declaration module of a class identity outside the
         * implementation closure (a HOST declaration's class): its public
         * surface is the only declaration the identity has. Such a class has
         * no implementation unit, so it publishes no JSON descriptor array to
         * read.
         */
        private final Map<ClassId, ModuleId> declarationModuleOfClass =
            new LinkedHashMap<>();
        /** The exported classes of each implementation module, declaration order. */
        private final Map<ModuleId, List<ClassId>> exportedClasses =
            new LinkedHashMap<>();
        /** The construction entry of every exported class (interface index fact). */
        private final Map<ClassId, ClassFactoryId> constructionEntries =
            new LinkedHashMap<>();
        /**
         * The {@code @jsonable} classes each unit itself declares (its own
         * generated JSON helpers): a same-identity sibling declaration that
         * is not plan-bearing publishes no {@code C$fields} and no helper
         * export.
         */
        private final Map<ModuleId, Set<ClassId>> jsonableClassesByModule =
            new LinkedHashMap<>();
        /** Per-owning-module {@code CLASS_DEFAULT} ops: a canonical class
         * identity may cover same-named declarations of sibling modules,
         * and each declaring unit's plan uses that unit's own default ops
         * (the plan-bearing declaration's layout and its declaration's
         * defaults agree). */
        private final Map<ModuleId, Map<ClassId, Map<String, SemanticOp>>>
            classDefaultOps = new LinkedHashMap<>();
        /** Emitted {@code $fields} descriptor arrays, by class identity. */
        private final Map<ClassId, String> classFieldsExprs =
            new LinkedHashMap<>();
        /** Emitted {@code $plan} constructor plans, by class identity. */
        private final Map<ClassId, String> classPlanExprs =
            new LinkedHashMap<>();
        /** The call-site span parameter names of the closure being emitted. */
        private String currentCallSpan;
        /** The {@code CLASS_DEFAULT} op currently emitted as a detached thunk. */
        private OpId currentDefaultOp;
        /**
         * The canonical async token's owning start op per token id (the
         * op whose result is the {@link AsyncTokenId.Canonical}).
         */
        private final Map<Long, SemanticOp> canonicalTokenOwner = new HashMap<>();
        /**
         * The publishing {@code ASYNC_START} op per token id the closure
         * records on a start's result — the caller-side alias identity of
         * an external async call included, whose referent canonical token
         * is owned by the callee unit's {@code EXTERNAL_ENTRY} record and
         * therefore has no op in this closure.
         */
        private final Map<Long, OpId> startByTokenId = new HashMap<>();
        /**
         * A nested adapter-source {@code ASYNC_START} op and the owning
         * outer {@code ASYNC_START} op whose adapter invocation publishes
         * its single operation (the nested op's own canonical token is
         * that operation).
         */
        private final Map<OpId, OpId> nestedStartOwner = new HashMap<>();
        /**
         * The consuming {@code AWAIT} op per publishing {@code ASYNC_START}
         * op: the awaited form appends the await site's own span triple to
         * the recorded host operation call.
         */
        private final Map<OpId, SemanticOp> awaitOfStart = new HashMap<>();
        /**
         * The module-level function declarations a {@code CALLBACK_INVOKE}
         * record names as host-driven callbacks, with that record: the
         * published wrapper carries the recorded {@code HOST_TO_DEAL}
         * parameter crossing.
         */
        private final Map<Long, SemanticOp> callbackByFunction = new HashMap<>();

        private final StringBuilder out = new StringBuilder();
        private int indent;
        private int line = 1;
        /**
         * The one project-wide source-map recorder of this emission (the
         * shared {@link SourceMapRecorder}/{@link SourceMapGenerator}
         * authority every backend consumes), or {@code null} for the
         * internal map-off rendering. A non-null recorder records every
         * mapped group's generated position in its owning module's mapper
         * and is also the one display-name authority of the emitted source
         * comments, so comment and sidecar never disagree.
         */
        private final SourceMapRecorder sourceMapRecorder;
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
            indexClassFacts();
            indexAsyncAndCallbackRecords();
            this.sourceMapRecorder = input.sourceMap()
                ? new SourceMapRecorder(displaySourceNames())
                : null;
        }

        /**
         * The project's display source names keyed by module identity, in
         * project order: the owner-supplied {@link JsSourceFile} display
         * name of each implementation unit, which the recorder uses for the
         * emitted comment text and (through the production owner's
         * {@code SourceMapPaths} projection) for the sidecar's
         * {@code source} string.
         */
        private Map<ModuleId, String> displaySourceNames() {
            Map<ModuleId, String> names = new LinkedHashMap<>();
            for (Map.Entry<ModuleId, JsSourceFile> entry
                    : sourceFiles.entrySet()) {
                names.put(entry.getKey(), entry.getValue().displayName());
            }
            return names;
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
                if (op.payload() instanceof KindPayload.ModuleImportPayload payload) {
                    importKinds.putIfAbsent(payload.resolvedModule(), payload.kind());
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
            registerIntrinsicSeedCells(unit);
            for (LoweredFunction function : unit.functions().values()) {
                functions.put(function.functionId().id(), function);
            }
            // The publication pairs: a lowered body's *creation site* owns
            // the spelling every static call resolves, so a direct call never
            // depends on a caller's local scope. The shared invocation-
            // identity rule has exactly two creation forms: a
            // {@code CLOSURE_NEW} publishes the created value in its own slot,
            // and a {@code RECURSIVE_GROUP_INIT} publishes each member's
            // created identity into that member's own cell. A binding
            // initialized from an already-created function value is an
            // identity-preserving *load*, not a creation: it shares the
            // function's identity ValueId but creates nothing, so it never
            // qualifies — a function-typed alias may be reassigned and may
            // live in another function's scope, and spelling the function's
            // static calls with the alias's cell would call whatever the alias
            // holds and would name a cell that is out of scope everywhere
            // else.
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.CLOSURE_NEW
                        || !(op.result() instanceof ValueId valueId)) {
                    continue;
                }
                KindPayload.ClosureNewPayload payload =
                    (KindPayload.ClosureNewPayload) op.payload();
                closureBindings.putIfAbsent(payload.function().id(),
                    slot(valueId));
            }
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.RECURSIVE_GROUP_INIT
                        || !(op.payload() instanceof KindPayload
                            .RecursiveGroupInitPayload group)) {
                    continue;
                }
                for (int i = 0; i < group.functions().size(); i++) {
                    closureBindings.putIfAbsent(group.functions().get(i).id(),
                        cell(group.bindings().get(i), 0));
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
         * Records the seeded-intrinsic carrier cell of one unit: the first
         * {@code BINDING_INIT} of a registered intrinsic identity whose
         * operand identity is produced by no op other than an
         * identity-preserving load of that init's own cell — the same
         * seed-write notion the shared validator admits the key by
         * ({@code BindingsProductionValidator}'s producer-less test, J1).
         * Membership in the module-init scope is not assumed: the recorded
         * seed init is the cell every other materialization of the identity
         * reads.
         */
        private void registerIntrinsicSeedCells(LoweredModuleUnit unit) {
            Set<Long> nonLoadProduced = new HashSet<>();
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.BINDING_LOAD
                        && op.result() instanceof ValueId produced) {
                    nonLoadProduced.add(produced.id());
                }
            }
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.BINDING_INIT
                        || !(op.payload() instanceof KindPayload
                            .BindingInitPayload init)
                        || !intrinsicKinds.containsKey(init.value().id())
                        || nonLoadProduced.contains(init.value().id())) {
                    continue;
                }
                intrinsicSeedCells.putIfAbsent(init.value().id(),
                    new SeedCell(init.binding(), init.generation()));
            }
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

        /**
         * Indexes the project's validated class facts exactly once: every
         * declaration's layout per canonical class identity (plus the
         * project's declaration-module registration layouts), the
         * interface index's exported-class entries with their recorded
         * construction entries, the generated JSON-helper declarations per
         * unit, and each class field's {@code CLASS_DEFAULT} child. A
         * canonical identity may cover two declarations (a
         * harness-synthesized pair) with different fields, so the declared
         * layouts stay per declaration and the cross-module JSON surface
         * resolves the plan-bearing declaration. These are the structural
         * facts the class arms render from; no class fact is rediscovered
         * from source text, an alias name or a target ABI registry.
         */
        private void indexClassFacts() {
            for (LoweredModuleUnit unit : units.values()) {
                for (Map.Entry<ClassId, ClassLayout> entry
                        : unit.classLayouts().entrySet()) {
                    declaredLayouts
                        .computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(entry.getValue());
                    declaringModules
                        .computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(unit.moduleId());
                }
            }
            for (Map.Entry<ClassId, ClassRegistrationSeeds.ClassRegistration> entry
                    : input.seeds().registrations().entrySet()) {
                seedLayouts.put(entry.getKey(), entry.getValue().layout());
            }
            for (Map.Entry<ModuleId, ExternalModuleInterface> entry
                    : input.project().interfaceIndex().modules().entrySet()) {
                for (ClassInterface classInterface : entry.getValue().classes()) {
                    declarationModuleOfClass.putIfAbsent(
                        classInterface.classId(), entry.getKey());
                    constructionEntries.putIfAbsent(classInterface.classId(),
                        classInterface.constructionEntry());
                    exportedClasses
                        .computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(classInterface.classId());
                }
            }
            for (Map.Entry<ModuleId, LoweredModuleUnit> moduleEntry
                    : units.entrySet()) {
                for (SemanticOp op : moduleEntry.getValue().ops()) {
                    switch (op.payload()) {
                        case KindPayload.ClassDefaultPayload payload ->
                            classDefaultOps
                                .computeIfAbsent(moduleEntry.getKey(),
                                    key -> new LinkedHashMap<>())
                                .computeIfAbsent(payload.classId(),
                                    key -> new LinkedHashMap<>())
                                .putIfAbsent(payload.field(), op);
                        case KindPayload.JsonFromClassPayload payload ->
                            indexJsonableDeclaration(moduleEntry.getKey(),
                                payload.layout().classId());
                        case KindPayload.JsonToClassPayload payload ->
                            indexJsonableDeclaration(moduleEntry.getKey(),
                                payload.layout().classId());
                        default -> {
                        }
                    }
                }
            }
            // The canonical JSON plan key carries the unique plan-bearing
            // declaration's layout; an identity the shared admission never
            // lets a nested link select falls back to the emission order's
            // last declaration, exactly like the lowering's plan-owner fact.
            for (Map.Entry<ClassId, List<ModuleId>> entry
                    : declaringModules.entrySet()) {
                List<ModuleId> bearers = jsonPlanBearers.get(entry.getKey());
                jsonPlanOwners.put(entry.getKey(),
                    bearers != null && bearers.size() == 1
                        ? bearers.get(0)
                        : entry.getValue().get(entry.getValue().size() - 1));
            }
            // Every generated JSON helper descends from a module-level
            // exported @jsonable class: a helper class outside its own
            // declaring unit's exported-class set would carry no descriptor
            // array and no public surface, so the disagreement fails closed
            // here. The check is per declaring unit: a same-identity sibling
            // declaration never satisfies it on the helper's behalf.
            for (Map.Entry<ModuleId, Set<ClassId>> entry
                    : jsonableClassesByModule.entrySet()) {
                List<ClassId> exported = exportedClassesOf(entry.getKey());
                for (ClassId classId : entry.getValue()) {
                    if (!exported.contains(classId)) {
                        throw new SharedEmissionFailure("the @jsonable class "
                            + classId + " is not one of the exported classes of"
                            + " its module '" + entry.getKey().path() + "':"
                            + " the generated helpers and their descriptor"
                            + " export exist for module-level exported"
                            + " @jsonable classes only (producer defect)", null);
                    }
                }
            }
        }

        /** One unit's own plan-bearing declaration of a class identity. */
        private void indexJsonableDeclaration(ModuleId module, ClassId classId) {
            jsonableClassesByModule
                .computeIfAbsent(module, key -> new LinkedHashSet<>())
                .add(classId);
            List<ModuleId> bearers = jsonPlanBearers
                .computeIfAbsent(classId, key -> new ArrayList<>());
            if (!bearers.contains(module)) {
                bearers.add(module);
            }
        }

        /** Whether one unit itself generates the identity's JSON helpers. */
        private boolean unitDeclaresJsonable(ModuleId module, ClassId classId) {
            Set<ClassId> classes = jsonableClassesByModule.get(module);
            return classes != null && classes.contains(classId);
        }

        /**
         * Indexes the async and callback records of the whole closure once:
         * the canonical token owners, the nested adapter-source
         * {@code ASYNC_START}s (executed by their owning adapter
         * invocation, never by the block walk), the consuming {@code AWAIT}
         * of every publishing start (the awaited form's completion span),
         * and the {@code CALLBACK_INVOKE} records (the published wrapper's
         * host crossing).
         */
        private void indexAsyncAndCallbackRecords() {
            for (LoweredModuleUnit unit : units.values()) {
                for (SemanticOp op : unit.ops()) {
                    if (op.result() instanceof AsyncTokenId.Canonical canonical) {
                        canonicalTokenOwner.put(canonical.tokenId(), op);
                    }
                    if (op.kind() == SemanticOpKind.ASYNC_START
                            && op.result() instanceof AsyncTokenId token) {
                        startByTokenId.put(token.tokenId(), op.opId());
                        // An external async start owns the caller-side alias
                        // whose canonical referent lives in the callee unit:
                        // the caller-side op is the one that publishes the
                        // operation, so an await naming either identity
                        // resolves to this start.
                        if (token instanceof AsyncTokenId.Alias alias
                                && !canonicalTokenOwner.containsKey(
                                    canonicalToken(alias).tokenId())) {
                            startByTokenId.putIfAbsent(
                                canonicalToken(alias).tokenId(), op.opId());
                        }
                    }
                }
            }
            for (LoweredModuleUnit unit : units.values()) {
                for (SemanticOp op : unit.ops()) {
                    if (op.kind() == SemanticOpKind.ASYNC_START) {
                        OpId parentId = op.origin() == null ? null
                            : op.origin().parentOpId();
                        SemanticOp parent = parentId == null ? null
                            : opsById.get(parentId);
                        if (parent != null
                                && parent.kind() == SemanticOpKind.ASYNC_START) {
                            // The nested adapter-source start: its single
                            // operation is published by the owning adapter
                            // invocation (the outer op), so the block walk
                            // must not execute it a second time.
                            if (!(op.result() instanceof AsyncTokenId.Canonical)) {
                                throw new IllegalStateException("the nested"
                                    + " ASYNC_START " + op.opId() + " carries"
                                    + " no canonical token result (producer"
                                    + " defect)");
                            }
                            nestedStartOwner.put(op.opId(), parentId);
                            skippedOps.add(op.opId());
                        }
                    }
                }
                for (SemanticOp op : unit.ops()) {
                    if (op.kind() == SemanticOpKind.AWAIT) {
                        KindPayload.AwaitPayload payload =
                            (KindPayload.AwaitPayload) op.payload();
                        awaitOfStart.putIfAbsent(
                            publishingStart(op, payload.token()), op);
                    }
                }
                for (SemanticOp op : unit.ops()) {
                    if (op.kind() != SemanticOpKind.CALLBACK_INVOKE) {
                        continue;
                    }
                    KindPayload.CallbackInvokePayload payload =
                        (KindPayload.CallbackInvokePayload) op.payload();
                    SemanticOp producer = producerByValue.get(payload.function().id());
                    if (producer == null
                            || producer.kind() != SemanticOpKind.CLOSURE_NEW) {
                        throw new IllegalStateException("the CALLBACK_INVOKE "
                            + op.opId() + " names " + payload.function()
                            + ", which no CLOSURE_NEW op produces; a"
                            + " host-driven callback is a module-level"
                            + " lowered body (producer defect)");
                    }
                    FunctionId functionId =
                        ((KindPayload.ClosureNewPayload) producer.payload())
                            .function();
                    callbackByFunction.put(functionId.id(), op);
                }
            }
        }

        /** One unit's own declared layout of a class identity, or null. */
        private ClassLayout unitLayoutOf(ModuleId module, ClassId classId) {
            LoweredModuleUnit unit = units.get(module);
            return unit == null ? null : unit.classLayouts().get(classId);
        }

        /**
         * The declaring unit's own layout of one class identity: the class
         * machinery of a unit (its factory entry, its constructor plan and
         * its JSON descriptor array) renders exactly the fields that unit
         * declares, never a same-identity sibling's first-seen layout.
         */
        private ClassLayout requireUnitLayout(ModuleId module, ClassId classId,
                                              SemanticOp op) {
            ClassLayout layout = unitLayoutOf(module, classId);
            if (layout == null) {
                throw rejected(op, "module '" + module.path() + "' declares no"
                    + " layout for the class " + classId + " (producer defect)");
            }
            return layout;
        }

        /** One class field's recorded {@code CLASS_DEFAULT} op, or null. */
        private SemanticOp defaultOpOf(ClassId classId, String field) {
            Map<ClassId, Map<String, SemanticOp>> moduleOps =
                classDefaultOps.get(currentModule);
            Map<String, SemanticOp> byField = moduleOps == null ? null
                : moduleOps.get(classId);
            return byField == null ? null : byField.get(field);
        }

        /** The classes exported by one module, in interface declaration order. */
        private List<ClassId> exportedClassesOf(ModuleId module) {
            List<ClassId> classes = exportedClasses.get(module);
            return classes == null ? List.of() : classes;
        }

        /**
         * The registered {@code CLASS_FACTORY} op of one construction entry
         * over the closure's delivered registries; a conflicting registration
         * is a producer defect (never a last-writer-wins selection).
         */
        private OpId factoryOpOf(SemanticOp op, ClassFactoryId factoryRef) {
            try {
                return ClassFactoryRegistry.ownerFactoryOp(registries, factoryRef);
            } catch (IllegalStateException conflict) {
                throw rejected(op, "the construction entry " + factoryRef
                    + " resolves through no unique factory registration: "
                    + conflict.getMessage());
            }
        }

        /** The canonical token one token identity resolves to (alias chains). */
        private static AsyncTokenId.Canonical canonicalToken(AsyncTokenId token) {
            AsyncTokenId current = token;
            while (current instanceof AsyncTokenId.Alias alias) {
                current = alias.referent();
            }
            return (AsyncTokenId.Canonical) current;
        }

        /**
         * The {@code ASYNC_START} op that publishes the operation of one
         * token: the start carrying the token's own identity, or the start
         * carrying the alias whose referent the token names (an external
         * callee's canonical token has no op in the caller's closure).
         */
        private OpId publishingStart(SemanticOp awaitingOp, AsyncTokenId token) {
            OpId found = startByTokenId.get(token.tokenId());
            if (found == null && token instanceof AsyncTokenId.Alias alias) {
                found = startByTokenId.get(alias.referent().tokenId());
            }
            if (found == null) {
                throw unsupported(awaitingOp, "the async token " + token
                    + " resolves to no started operation in the closure"
                    + " (producer defect)");
            }
            return nestedStartOwner.getOrDefault(found, found);
        }

        /**
         * The emitted operation slot of one token: the publishing start's
         * slot — for a nested adapter-source start, the owning outer start's
         * slot, because one adapter invocation publishes the one operation
         * both tokens name.
         */
        private String tokenSlotOf(SemanticOp awaitingOp, AsyncTokenId token) {
            return startSlot(publishingStart(awaitingOp, token));
        }

        private static String startSlot(OpId startOp) {
            return "$a" + startOp.id();
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

        /**
         * Indexes one artifact's own import occurrences in recorded source
         * order: the stdlib surface binding of each imported stdlib module
         * (the first recorded STDLIB {@code MODULE_IMPORT}'s require
         * binding) and the first HOST occurrence of each resolved
         * declaration module (its alias cell, which holds that
         * occurrence's typed surface). Both indexes are artifact-scoped: an
         * artifact never reads another artifact's bindings, and the
         * occurrence selection is the recorded import declaration order —
         * never a module/name lookup.
         */
        private void indexArtifactImports(LoweredModuleUnit unit) {
            stdlibSurfaces.clear();
            hostOccurrences.clear();
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.MODULE_IMPORT
                        || !(op.payload()
                            instanceof KindPayload.ModuleImportPayload payload)) {
                    continue;
                }
                switch (payload.kind()) {
                    case HOST -> {
                        if (!payload.aliasCells().isEmpty()) {
                            hostOccurrences.putIfAbsent(
                                payload.resolvedModule(),
                                payload.aliasCells().get(0));
                        }
                    }
                    case STDLIB -> stdlibSurfaces.putIfAbsent(
                        payload.resolvedModule(), "$d" + op.opId().id());
                    case COMPILED -> {
                        // The compiled read arm resolves the published
                        // surface by module identity; no occurrence index.
                    }
                }
            }
        }

        private JsModuleArtifact emitModule(ModuleId module) {
            LoweredModuleUnit unit = units.get(module);
            currentModule = module;
            out.setLength(0);
            line = 1;
            indent = 0;
            indexArtifactImports(unit);

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
            // The module-init wrapper is an entry group: it maps to the
            // recorded MODULE_INIT op's own local site, exactly like the
            // module-init group of the shared LuaJIT/JVM emitters.
            SemanticOp moduleInitOp = moduleInitOpOf(unit);
            if (moduleInitOp != null) {
                beginMappedGroup(moduleInitOp);
            }
            emit("$rt.initModule(" + jsString(module.path()) + ", () => {");
            indent++;
            emitModuleInitBody(unit, unit.moduleInit().initBlock());
            indent--;
            emit("});");
            emit("$module.exports = $rt.moduleSurface(" + jsString(module.path())
                + ");");

            SourceMapGenerator generator = sourceMapRecorder == null ? null
                : sourceMapRecorder.generatorFor(module);
            List<SourceMapGenerator.Mapping> mappings = null;
            if (sourceMapRecorder != null) {
                // An artifact whose ops recorded no mappable position still
                // carries an ordered (possibly empty) list, so its sidecar
                // exists with an empty mappings array.
                mappings = generator == null ? List.of() : generator.mappings();
            }
            return new JsModuleArtifact(module, artifactRelativePath(module),
                out.toString(), mappings);
        }

        /** The unit's recorded {@code MODULE_INIT} op, or null. */
        private static SemanticOp moduleInitOpOf(LoweredModuleUnit unit) {
            for (SemanticOp op : unit.ops()) {
                if (op.kind() == SemanticOpKind.MODULE_INIT) {
                    return op;
                }
            }
            return null;
        }

        /**
         * Emits one function scope's body: the predeclared value slots, then
         * the block's ops. A function scope is the module-init body or one
         * lowered closure body; nested closure bodies own their own slots.
         */
        private void emitFunctionBody(LoweredModuleUnit unit, BlockId block) {
            emitSlotDeclarations(unit, block);
            declareBlockBindings(unit, block);
            emitOps(unit, block);
        }

        /**
         * Emits the module-init body of one artifact: the slots and binding
         * cells, the recorded module-init ops, the class machinery (default
         * thunks, the private factory entries, the class metadata/constructor
         * plans and the JSON descriptor arrays) and then the entry
         * delegation. The entry delegation runs after the class machinery so
         * the direct-run {@code main} observes complete class artifacts
         * before any construction, default or JSON walk can reach them; every
         * other init op keeps its recorded position.
         */
        private void emitModuleInitBody(LoweredModuleUnit unit, BlockId block) {
            emitSlotDeclarations(unit, block);
            declareBlockBindings(unit, block);
            List<SemanticOp> entryOps = new ArrayList<>();
            emitOps(unit, block, entryOps);
            emitClassMachinery(unit);
            for (SemanticOp entryOp : entryOps) {
                emitOp(unit, entryOp);
            }
        }

        /** The predeclared value-slot declaration of one emitted scope. */
        private void emitSlotDeclarations(LoweredModuleUnit unit, BlockId block) {
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
                if (op.kind() == SemanticOpKind.ASYNC_START
                        && !skippedOps.contains(op.opId())) {
                    // The started operation's slot (the single Promise one
                    // start publishes; the awaiting op reads it).
                    slots.add(startSlot(op.opId()));
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
            emitOps(unit, block, null);
        }

        /**
         * Emits one block's ops (no braces; the caller owns the scope). When
         * {@code postponedEntries} is non-null, the block's ENTRY_INVOKE
         * delegations are collected there instead of emitted (the module-init
         * body runs them after the class machinery) and the current detached
         * class-default op is skipped (its thunk owns it).
         */
        private void emitOps(LoweredModuleUnit unit, BlockId block,
                             List<SemanticOp> postponedEntries) {
            List<OpId> members = memberOps(unit, block);
            if (members == null) {
                throw new IllegalStateException("block " + block
                    + " has no membership row in its owning unit's table"
                    + " (a malformed table; the production validator rejects"
                    + " this)");
            }
            for (OpId opId : members) {
                if (ownedChildren.contains(opId) || skippedOps.contains(opId)
                        || opId.equals(currentDefaultOp)) {
                    continue;
                }
                SemanticOp op = opsById.get(opId);
                if (op == null) {
                    throw new IllegalStateException("block " + block
                        + " names the unknown op " + opId);
                }
                if (postponedEntries != null
                        && op.kind() == SemanticOpKind.ENTRY_INVOKE) {
                    postponedEntries.add(op);
                    continue;
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
                case ASYNC_START -> emitAsyncStart(op);
                case AWAIT -> emitAwait(op);
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
                case STDLIB_CALL -> emitStdlibCall(op);
                case EXPORT_PUBLISH -> emitExportPublish(op);
                case ENTRY_INVOKE -> emitEntryInvoke(unit, op);
                case MODULE_INIT -> emit("// The artifact's registered record"
                    + " and init body carry this record.");
                case EXTERNAL_ENTRY -> emit("// EXTERNAL_ENTRY '"
                    + ((KindPayload.ExternalEntryPayload) op.payload())
                        .exportName()
                    + "': the published CommonJS export is the invocation"
                    + " record; the callee's callable body carries its"
                    + " crossing checks.");
                case CALLBACK_INVOKE -> throw unsupported(op,
                    "the CALLBACK_INVOKE " + op.opId() + " is an unattached"
                    + " host-driven dispatch record: its published wrapper"
                    + " carries the HOST_TO_DEAL crossing and the block walk"
                    + " must not execute it (producer defect)");
                case CLASS_NEW -> emitClassNew(op);
                case FIELD_READ -> emitFieldRead(op);
                case FIELD_WRITE -> emitFieldWrite(op);
                case FIELD_DELETE -> emitFieldDelete(op);
                case JSON_FROM_CLASS -> emitJsonFromClass(op);
                case JSON_TO_CLASS -> emitJsonToClass(op);
                case CLASS_DEFAULT, CLASS_FACTORY -> throw unsupported(op, "the"
                    + " detached " + op.kind() + " " + op.opId() + " executes"
                    + " only under its triggering constructor or factory arm —"
                    + " the block walk never runs it (producer defect)");
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
                case NUMBER_NEG -> "-" + numeric(operand);
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
                case NUMBER_ADD -> "(" + numeric(left) + " + "
                    + numeric(right) + ")";
                case NUMBER_SUB -> "(" + numeric(left) + " - "
                    + numeric(right) + ")";
                case NUMBER_MUL -> "(" + numeric(left) + " * "
                    + numeric(right) + ")";
                case NUMBER_DIV_IEEE -> "(" + numeric(left) + " / "
                    + numeric(right) + ")";
                // The numeric selectors unwrap the read-side number-
                // variant carrier ($rt.numOf) exactly like the shared
                // executor's number views: a decoded JSON slot stays
                // comparable and computable by value on every carrier.
                case INT32_EQ, NUMBER_EQ -> "(" + numeric(left) + " === "
                    + numeric(right) + ")";
                case BOOLEAN_EQ, REFERENCE_EQ, STRING_EQ,
                     BYTES_EQ -> "(" + left + " === " + right + ")";
                case INT32_NE, NUMBER_NE -> "(" + numeric(left) + " !== "
                    + numeric(right) + ")";
                case BOOLEAN_NE, REFERENCE_NE, STRING_NE,
                     BYTES_NE -> "(" + left + " !== " + right + ")";
                case INT32_LT, NUMBER_LT -> "(" + numeric(left) + " < "
                    + numeric(right) + ")";
                case INT32_LE, NUMBER_LE -> "(" + numeric(left) + " <= "
                    + numeric(right) + ")";
                case INT32_GT, NUMBER_GT -> "(" + numeric(left) + " > "
                    + numeric(right) + ")";
                case INT32_GE, NUMBER_GE -> "(" + numeric(left) + " >= "
                    + numeric(right) + ")";
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
                case NULLABLE_EQ -> "((" + nullableOperand(op, 0, left)
                    + " === " + nullableOperand(op, 1, right)
                    + ") || ($rt.isNilEquivalent(" + left
                    + ") && $rt.isNilEquivalent(" + right + ")))";
                case NULLABLE_NE -> "!((" + nullableOperand(op, 0, left)
                    + " === " + nullableOperand(op, 1, right)
                    + ") || ($rt.isNilEquivalent(" + left
                    + ") && $rt.isNilEquivalent(" + right + ")))";
                case NULLABLE_NULL_EQ -> "$rt.isNilEquivalent("
                    + nullableSideExpr(payload.side(), left, right) + ")";
                case NULLABLE_NULL_NE -> "!$rt.isNilEquivalent("
                    + nullableSideExpr(payload.side(), left, right) + ")";
            };
            emit(slotOf(op) + " = " + expression + ";");
        }

        /** The numeric value view of one operand (the carrier unwrap). */
        private static String numeric(String operand) {
            return "$rt.numOf(" + operand + ")";
        }

        /** One nullable-equality operand: a nullable numeric operand
         * unwraps its read-side carrier; every other operand passes
         * through (the shared inner-equality rule). */
        private static String nullableOperand(SemanticOp op, int index,
                                              String operand) {
            if (op.operandTypes().size() <= index) {
                return operand;
            }
            RuntimeDescriptor descriptor = op.operandTypes().get(index);
            RuntimeDescriptor inner = descriptor
                instanceof RuntimeDescriptor.Nullable nullable
                    ? nullable.inner() : descriptor;
            return inner == RuntimeDescriptor.Int.INSTANCE
                    || inner == RuntimeDescriptor.Number.INSTANCE
                ? numeric(operand) : operand;
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
                if (boundary == null) {
                    throw new IllegalStateException("an element or field boundary"
                        + " child is missing (producer defect)");
                }
                String input = slot(value);
                // The ARRAY_LITERAL_ELEMENT cell is the closed
                // ARRAY_ELEMENT_DESCRIPTOR row: its failure is E8003 with
                // the 1-based element index, the element descriptor and
                // the element's reference kind (the same projection the
                // entire-array checkArray walk runs), never the generic
                // E8001 descriptor row.
                emit(input + " = $rt.arrayElementCheck("
                    + jsString(payload.elementDescriptor().canonicalSpecText())
                    + ", " + (i + 1) + ", " + input + ", "
                    + spanArgs(boundary) + ");");
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
                : "$rt.arrayLength(" + array + ")";
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
            if (boundary != null && !defersContextualRead(boundary)) {
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
                emit(target + " = $rt.tableGet(" + container + ", " + index
                    + ");");
            } else {
                String length = normalizeLengthSlot(payload.slot());
                emit("if (" + index + " < 0 || " + index + " >= " + length
                    + ") { $rt.fail(\"E8002\", \"array index out of bounds\","
                    + " " + spanArgs(boundary) + "); }");
                // The carrier split is $rt.arrayAt's own (a native Array
                // first, the std/json array-marked Map second): every
                // carrier checkArray admits reads at the checked position,
                // and the declared element's number kind selects the
                // read-side variant carrier a decoded slot materializes.
                emit(target + " = $rt.arrayAt(" + container + ", " + index
                    + ", " + (isNumberDescriptor(op.resultType()) ? "true"
                        : "false") + ");");
            }
            if (isArrayElementRead(boundary)) {
                // The ARRAY_ELEMENT_READ cell runs the bounds gate only (the
                // shared ARRAY_READ_INDEX_THEN_DESCRIPTOR policy,
                // BoundaryExecutor.checkArrayRead): an in-range hole/missing
                // element passes through, and the next recorded boundary —
                // the contextual check — decides null versus the E8001 arm
                // at its own origin, exactly like the retained emission.
                return;
            }
            if (!defersContextualRead(boundary)) {
                emitBoundaryCheck(boundary, target, descriptorOf(boundary),
                    payload.elementBoundaryOpId());
            }
        }

        /**
         * Whether one boundary cell is the deferred contextual read: a
         * {@code CONTEXTUAL_TABLE_READ} over a composite carrier (a
         * function, an array or the bytes carrier) passes the raw read
         * through unvalidated, exactly like the shared oracle and the
         * LuaJIT/JVM emitters — the consuming declared cell (a parameter
         * or declaration crossing) owns the projection at its own origin,
         * so a masking read-site E8001 never precedes the recorded
         * family.
         */
        private boolean defersContextualRead(SemanticOp boundary) {
            if (boundary == null
                    || !(boundary.payload()
                        instanceof KindPayload.BoundaryPayload payload)
                    || payload.kind() != BoundaryKind.CONTEXTUAL_TABLE_READ) {
                return false;
            }
            RuntimeDescriptor descriptor = payload.descriptor();
            RuntimeDescriptor inner = descriptor
                instanceof RuntimeDescriptor.Nullable nullable
                    ? nullable.inner() : descriptor;
            return inner instanceof RuntimeDescriptor.Func
                || inner instanceof RuntimeDescriptor.Array
                || inner instanceof RuntimeDescriptor.Bytes;
        }

        /** Whether one element boundary is the array read's bounds-only cell. */
        private static boolean isArrayElementRead(SemanticOp boundary) {
            return boundary != null
                && boundary.payload() instanceof KindPayload.BoundaryPayload
                    payload
                && payload.kind() == BoundaryKind.ARRAY_ELEMENT_READ;
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
                emit("$rt.tableSet(" + container + ", " + index + ", " + value
                    + ");");
            } else {
                // The carrier split is $rt.arraySet's own (a native Array
                // assigns the slot, the std/json array-marked Map sets its
                // array-form key) so a write through one alias is observed
                // by every alias and by the shared encode walk; the written
                // value's own static number kind records the slot variant.
                emit("$rt.arraySet(" + container + ", " + index + ", " + value
                    + ", " + (isNumberDescriptor(producerResultType(
                        payload.value())) ? "true" : "false") + ");");
            }
        }

        private void emitIndexDelete(SemanticOp op) {
            KindPayload.IndexDeletePayload payload =
                (KindPayload.IndexDeletePayload) op.payload();
            String container = slot(payload.container());
            String index = slot(payload.slot());
            if (normalizeMode(payload.slot()) == IndexMode.TABLE_WRITE) {
                emit("$rt.tableDelete(" + container + ", " + index + ");");
            } else {
                String length = normalizeLengthSlot(payload.slot());
                // The append-at-length guard suppresses the write at
                // i === length; the committed step is $rt.arrayDelete's
                // carrier split (a native Array slot or the marked Map's
                // array-form key set to the nil-equivalent).
                emit("if (" + index + " < " + length + ") { $rt.arrayDelete("
                    + container + ", " + index + "); }");
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

        /** One value's producing op result descriptor, or null when the
         * producer carries none. */
        private OpResultType producerResultType(ValueId value) {
            SemanticOp producer = producerByValue.get(value.id());
            return producer == null ? null : producer.resultType();
        }

        /** A number-typed descriptor (a nullable number included): the
         * declared element/operand kind that selects the read-side numeric
         * variant carrier. */
        private static boolean isNumberDescriptor(OpResultType resultType) {
            if (!(resultType instanceof RuntimeDescriptor descriptor)) {
                return false;
            }
            RuntimeDescriptor inner = descriptor
                instanceof RuntimeDescriptor.Nullable nullable
                    ? nullable.inner() : descriptor;
            return inner == RuntimeDescriptor.Number.INSTANCE;
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
            if (boundary != null && !defersContextualRead(boundary)) {
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
            if (op.result() instanceof ValueId valueId) {
                // A value-publishing free boundary (the refined-operand
                // cell): the admitted value is the consumer's operand, so
                // the check publishes its own slot instead of rewriting
                // the read's (an address chain's operand closure executes
                // this op at its consuming child's position, an index
                // read's container/key, a table member operation's
                // container child and a .length receiver name it
                // directly).
                emit(slot(valueId) + " = "
                    + boundaryCheckExpr(op, slot(payload.input())) + ";");
                return;
            }
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
            emit(inputSlot + " = " + boundaryCheckExpr(boundary, inputSlot)
                + ";");
        }

        /**
         * The admission expression of one committed boundary cell: the
         * recorded descriptor check at the boundary's own origin,
         * replacing the input with the admitted value (the int cell's
         * {@code -0} normalization included).
         */
        private String boundaryCheckExpr(SemanticOp boundary, String input) {
            return "$rt.checkType("
                + jsString(descriptorOf(boundary).canonicalSpecText()) + ", "
                + input + ", " + spanArgs(boundary) + ")";
        }

        /**
         * One recorded parameter cell of a call or async start: a
         * {@code DEAL_TO_HOST}+{@code HOST_PARAMETER} cell (a host-family
         * callee — a host function, a host-materialized value or a
         * conversion intrinsic) runs the pinned E8010 composition at its
         * one-based parameter position and the call origin instead of the
         * generic descriptor check, whose E8001 arm would mask the
         * recorded family; every other family runs its own admitted
         * descriptor check.
         */
        private void emitCallParameter(SemanticOp boundary, String inputSlot,
                                       RuntimeDescriptor descriptor, int index) {
            if (boundary.failurePolicy() == FailurePolicyId.HOST_PARAMETER) {
                emit(inputSlot + " = $rt.hostParam(" + index + ", "
                    + jsString(descriptor.canonicalSpecText()) + ", "
                    + inputSlot + ", " + spanArgs(boundary) + ");");
                return;
            }
            emitBoundaryCheck(boundary, inputSlot, descriptor);
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
                + bindingValueExpr(payload.binding(), payload.generation(),
                    payload.value()) + ";");
        }

        /**
         * The carrier expression of one binding-initialized value. A
         * registered intrinsic identity is the seeded carrier: the seed
         * {@code BINDING_INIT} materializes the one wrapper value, and every
         * other site — the identity-preserving function-typed loads
         * included — reads the seed binding's cell, so the seeded identity
         * stays one value and its reference identity survives. Every other
         * value is the slot of its producing op.
         */
        private String bindingValueExpr(BindingId binding, long generation,
                                        ValueId valueId) {
            IntrinsicKind kind = intrinsicKinds.get(valueId.id());
            if (kind != null) {
                SeedCell seed = intrinsicSeedCells.get(valueId.id());
                if (seed == null) {
                    return intrinsicWrapper(kind);
                }
                if (seed.binding().equals(binding)
                        && seed.generation() == generation) {
                    return intrinsicWrapper(kind);
                }
                return cellSource(seed.binding(), seed.generation());
            }
            if (producerByValue.containsKey(valueId.id())) {
                return slot(valueId);
            }
            throw new IllegalStateException("the producer-less value "
                + valueId + " names no registered intrinsic identity (a"
                + " producer defect)");
        }

        private static String intrinsicWrapper(IntrinsicKind kind) {
            return switch (kind) {
                case INT_CONVERT -> "$rt.function(\"(number)->int\", (v, $f, $l,"
                    + " $c) => $rt.intConvert(v, $f, $l, $c), \"HOST\")";
                case NUMBER_CONVERT -> "$rt.function(\"(int)->number\", (v, $f,"
                    + " $l, $c) => $rt.numberConvert(v, $f, $l, $c), \"HOST\")";
                case BYTES_NEW -> "$rt.function(\"(int)->bytes\", (v, $f, $l,"
                    + " $c) => $rt.bytes(v, $f, $l, $c), \"HOST\")";
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
            boolean callSpan = needsCallSpan(owner, function.body());
            StringBuilder params = new StringBuilder();
            for (int i = 0; i < parameters.size(); i++) {
                if (i > 0) {
                    params.append(", ");
                }
                params.append(cell(parameters.get(i), 0));
            }
            if (callSpan) {
                // The trailing call-site span triple every emitted call
                // appends; the generated @jsonable toJson body reads it as
                // the shared JSON_TO_ERROR projection's call origin.
                if (params.length() > 0) {
                    params.append(", ");
                }
                params.append("$file, $line, $column");
            }
            // An async declared body emits the native async function: the
            // awaited operations suspend and complete through real
            // Promises, and the resolved completion is the declared
            // return value the body's own RETURN cell checked.
            String keyword = function.descriptor().isAsync() ? "async " : "";
            // The emitted function expression keeps the lowered function's
            // original source name (docs/spec-v1.3.md §Source maps and
            // debugging: a stack trace names both the generated function and
            // the original source function). The generated half is the
            // deterministic "<sourceName>$f" spelling of the retained JS
            // artifact ABI; a name-less function expression stays anonymous.
            String name = function.sourceName() == null ? null
                : function.sourceName() + "$f";
            emit(prefix + "$rt.function("
                + jsString(function.descriptor().canonicalSpecText()) + ", "
                + keyword + (name == null
                    ? "(" + params + ") => {"
                    : "function " + name + "(" + params + ") {"));
            indent++;
            emitCallbackCrossing(functionId, parameters);
            String previousSpan = currentCallSpan;
            currentCallSpan = callSpan ? "$file, $line, $column" : null;
            try {
                emitFunctionBody(owner, function.body());
            } finally {
                currentCallSpan = previousSpan;
            }
            indent--;
            emit("}, " + jsString("DEAL_BODY") + suffix);
        }

        /**
         * The host-driven callback crossing of one closure: when a
         * {@code CALLBACK_INVOKE} record names this module-level function,
         * the published wrapper is the host invocation entry, so its
         * prologue runs the recorded {@code HOST_TO_DEAL} parameter cells
         * in one-based order (the descriptor check at each cell's own
         * origin) before the body. A DEAL call site's own parameter cells
         * are unaffected: the same admitted value passes any repeated
         * pure check, and the body publishes no partial result on
         * failure.
         */
        private void emitCallbackCrossing(FunctionId functionId,
                                          List<BindingId> parameters) {
            SemanticOp callback = callbackByFunction.get(functionId.id());
            if (callback == null) {
                return;
            }
            KindPayload.CallbackInvokePayload payload =
                (KindPayload.CallbackInvokePayload) callback.payload();
            if (payload.parameterBoundaryOpIds().size() != parameters.size()) {
                throw unsupported(callback, "the CALLBACK_INVOKE "
                    + callback.opId() + " records "
                    + payload.parameterBoundaryOpIds().size()
                    + " HOST_TO_DEAL parameter cell(s) for " + parameters.size()
                    + " lowered parameter(s) (producer defect)");
            }
            for (int i = 0; i < parameters.size(); i++) {
                SemanticOp boundary = opsById.get(
                    payload.parameterBoundaryOpIds().get(i));
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                if (boundaryPayload.kind() != BoundaryKind.HOST_TO_DEAL) {
                    throw unsupported(boundary, "the CALLBACK_INVOKE "
                        + callback.opId() + " parameter cell " + (i + 1)
                        + " carries the boundary kind " + boundaryPayload.kind()
                        + " instead of HOST_TO_DEAL (producer defect)");
                }
                String cellExpr = cell(parameters.get(i), 0);
                emit(cellExpr + " = $rt.checkType("
                    + jsString(boundaryPayload.descriptor().canonicalSpecText())
                    + ", " + cellExpr + ", " + spanArgs(boundary) + ");");
            }
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
            String sourceExpr = null;
            String thunkFn = null;
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
                case AdaptSourceRef.Thunk thunk -> {
                    // One detached re-executor: every invocation re-runs the
                    // recorded thunk block and consumes its final producing
                    // value, so side effects repeat and a fresh closure per
                    // run keeps fresh identity.
                    thunkFn = "$at" + op.opId().id();
                    emit("const " + thunkFn + " = () => {");
                    indent++;
                    emitFunctionBody(units.get(opModule.get(op.opId())),
                        thunk.blockId());
                    emit("return " + thunkResultSlot(op, thunk.blockId())
                        + ";");
                    indent--;
                    emit("};");
                }
            }
            // The adapter's ABI is the retained call shape: the target
            // signature's parameters followed by the call-site span
            // triple, which the invocation forwards to the source wrapper
            // unchanged, so a failure inside the source (the conversion
            // ladder behind an arity-extension adapter included) carries
            // the authoritative call origin.
            int targetArity = payload.targetSignature().paramTypes().size();
            int sourceArity = payload.sourceSignature().paramTypes().size();
            // An async target emits the native async function: the
            // invocation returns the source's single operation as a real
            // Promise (the converted completion), never a second start.
            String keyword = payload.targetSignature().isAsync() ? "async " : "";
            // The wrapper is created through a block-scoped binding that is
            // fresh per execution of this adapt op: one adapt site in a loop
            // creates one wrapper per iteration, and each wrapper keeps its
            // own resolved-source record. The result slot publishes the
            // current execution's wrapper only.
            String wrapper = "$aw" + op.opId().id();
            emit("{");
            indent++;
            emit("const " + wrapper + " = $rt.function("
                + jsString(payload.targetSignature().canonicalSpecText())
                + ", " + keyword + "(" + adapterParameters(targetArity)
                + ") => {");
            indent++;
            // The adapter's own wrapper checks every parameter of the
            // target signature — the ignored trailing parameters of an
            // arity extension included — before it resolves the source
            // and before the leading-M projection
            // (docs/spec-v1.3.md Arity extension adapter: the wrapper
            // checks the ignored parameter too). A DEAL call site's
            // parameter cells already admitted the same values, so the
            // repeated check is idempotent; a host that invokes the
            // published adapter value directly (a host-declared function
            // parameter or a callback's adapted return) has no cell of
            // its own, and this wrapper is its only enforcement point.
            for (int i = 0; i < targetArity; i++) {
                emit("$p" + i + " = $rt.checkType("
                    + jsString(payload.targetSignature().paramTypes().get(i)
                        .canonicalSpecText())
                    + ", $p" + i + ", $file, $line, $column);");
            }
            emit("const $src = " + (thunkFn == null ? sourceExpr
                : thunkFn + "()") + ";");
            // The resolved source is recorded on the carrier instance the
            // invocation runs in: the emitted dynamic dispatch of an adapter
            // callee reads the source's own class tag to select the recorded
            // per-source return cell, and the source is resolved exactly once
            // per invocation (a thunk source's single re-execution included).
            // The binding, never the adapt op's reusable result slot, holds
            // the wrapper, so a later execution cannot overwrite an earlier
            // retained wrapper's record.
            emit(wrapper + ".$src = $src;");
            emit("$rt.checkFunctionSig("
                + jsString(payload.sourceSignature().canonicalSpecText())
                + ", $src.$sig, $src, $file, $line, $column);");
            emit("return $src.$f(" + adapterInnerArguments(sourceArity)
                + ");");
            indent--;
            emit("}, " + jsString("ADAPTER") + ");");
            emit(slotOf(op) + " = " + wrapper + ";");
            indent--;
            emit("}");
        }

        /**
         * The final producing value of one adapter thunk block: the last
         * non-owned member op that publishes a value, exactly the source
         * value the adapter invocation consumes (the block's own ops ran in
         * source order inside the re-executor).
         */
        private String thunkResultSlot(SemanticOp adaptOp, BlockId block) {
            LoweredModuleUnit unit = units.get(opModule.get(adaptOp.opId()));
            List<OpId> members = memberOps(unit, block);
            if (members == null) {
                throw unsupported(adaptOp, "the adapter thunk block " + block
                    + " has no membership row (producer defect)");
            }
            for (int i = members.size() - 1; i >= 0; i--) {
                OpId opId = members.get(i);
                if (ownedChildren.contains(opId)) {
                    continue;
                }
                SemanticOp op = opsById.get(opId);
                if (op != null && op.result() instanceof ValueId valueId) {
                    return slot(valueId);
                }
                break;
            }
            throw unsupported(adaptOp, "the adapter thunk block " + block
                + " has no final producing value (producer defect)");
        }

        /** The adapter's declared parameters: target arity plus the span. */
        private static String adapterParameters(int targetArity) {
            StringBuilder params = new StringBuilder();
            for (int i = 0; i < targetArity; i++) {
                if (params.length() > 0) {
                    params.append(", ");
                }
                params.append("$p").append(i);
            }
            if (params.length() > 0) {
                params.append(", ");
            }
            params.append("$file, $line, $column");
            return params.toString();
        }

        /**
         * The adapter's inner call arguments: the source signature's
         * leading parameters (the D15 leading-M projection) followed by the
         * received span triple.
         */
        private static String adapterInnerArguments(int sourceArity) {
            StringBuilder args = new StringBuilder();
            for (int i = 0; i < sourceArity; i++) {
                if (args.length() > 0) {
                    args.append(", ");
                }
                args.append("$p").append(i);
            }
            if (args.length() > 0) {
                args.append(", ");
            }
            args.append("$file, $line, $column");
            return args.toString();
        }

        private String adaptValueExpr(AdaptSourceRef.Value value) {
            ValueId valueId = value.value();
            IntrinsicKind kind = intrinsicKinds.get(valueId.id());
            if (kind != null) {
                // The seeded identity's VALUE capture is the seed cell's
                // carrier: the adapter retains the one materialized value,
                // never a fresh wrapper (identity preservation).
                SeedCell seed = intrinsicSeedCells.get(valueId.id());
                return seed == null ? intrinsicWrapper(kind)
                    : cellSource(seed.binding(), seed.generation());
            }
            if (producerByValue.containsKey(valueId.id())) {
                return slot(valueId);
            }
            throw new IllegalStateException("the adaptation source value "
                + valueId + " has no producer and no registered"
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
                default -> {
                    String checked = "$rt.checkType("
                        + jsString(payload.descriptor().canonicalSpecText()) + ", "
                        + input + ", " + spanArgs(boundary) + ")";
                    if (boundary.result() instanceof ValueId result) {
                        // A value-publishing chain boundary (the
                        // refined-operand cell): the admitted value is the
                        // chain's own receiver/key identity, so the check
                        // publishes its own slot and never rewrites the
                        // read's.
                        emit(slot(result) + " = " + checked + ";");
                    } else {
                        emit(input + " = " + checked + ";");
                    }
                }
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
                    + String.join(", ", externalArguments) + "], "
                    + spanArgs(op) + ");");
                return;
            }
            if (payload.callee() instanceof KindPayload.CallCallee.Dynamic
                    dynamicCallee) {
                emitDynamicCall(op, payload, dynamicCallee);
                return;
            }
            List<String> arguments = new ArrayList<>();
            for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                SemanticOp boundary = opsById.get(
                    payload.parameterBoundaryOpIds().get(i));
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                emitCallParameter(boundary, slot(boundaryPayload.input()),
                    boundaryPayload.descriptor(), i + 1);
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
            List<String> callArguments = new ArrayList<>(arguments);
            callArguments.add(spanArgs(op));
            if (binding instanceof FunctionExecutionBinding.HostFunction
                    || binding instanceof FunctionExecutionBinding.HostFunctionValue) {
                // The host callee is the recorded source import occurrence's
                // typed surface entry (or the host-materialized value at its
                // own crossing): the call passes the checked arguments plus
                // the call-site span triple the typed wrapper's boundary
                // checks consume, and the single caller-side
                // HOST_TO_DEAL return cell runs on the returned value.
                emit(slotOf(op) + " = " + hostCalleeExpr(op, binding) + ".$f("
                    + String.join(", ", callArguments) + ");");
                SemanticOp returnBoundary = payload.returnBoundaryOpId() == null
                    ? null : opsById.get(payload.returnBoundaryOpId());
                if (returnBoundary != null) {
                    emitBoundaryCheck(returnBoundary, slotOf(op),
                        descriptorOf(returnBoundary));
                }
                return;
            }
            emit(slotOf(op) + " = " + calleeExpr(op, payload) + ".$f("
                + String.join(", ", callArguments) + ");");
        }

        /**
         * The CALL arm of a runtime-resolved callee: the recorded
         * parameter cells run once in one-based order, the carrier's own
         * private class tag selects exactly one class path, and the
         * recorded per-class return cell runs once — the DEAL-body cell by
         * the invocation site only when it is the call-owned form (a
         * callee-owned body runs its own cell inside), the
         * {@code HOST_TO_DEAL} cell for the HOST class, and for an adapter
         * carrier the D15-resolved source's own tag selects the same
         * paths. A carrier without a resolvable class fails closed with
         * the pinned E8001 {@code expected function} projection at the
         * call origin; no class is selected from the checked descriptor,
         * a callee spelling or an argument type.
         */
        private void emitDynamicCall(SemanticOp op, KindPayload.CallPayload payload,
                                     KindPayload.CallCallee.Dynamic callee) {
            KindPayload.DynamicReturnBoundary cells = payload.dynamicReturnBoundary();
            if (cells == null) {
                throw unsupported(op, "the dynamic CALL " + op.opId()
                    + " records no DynamicReturnBoundary cell set (producer"
                    + " defect)");
            }
            SemanticOp dealCell = opsById.get(cells.dealBodyBoundaryOpId());
            SemanticOp hostCell = opsById.get(cells.hostBoundaryOpId());
            if (dealCell == null || hostCell == null
                    || !(hostCell.payload()
                        instanceof KindPayload.BoundaryPayload hostPayload)
                    || hostPayload.kind() != BoundaryKind.HOST_TO_DEAL) {
                throw unsupported(op, "the dynamic CALL " + op.opId()
                    + " records a return-cell set that resolves to no"
                    + " FUNCTION_RETURN/HOST_TO_DEAL pair (producer defect)");
            }
            List<String> arguments = new ArrayList<>();
            for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                SemanticOp boundary = opsById.get(
                    payload.parameterBoundaryOpIds().get(i));
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                emitCallParameter(boundary, slot(boundaryPayload.input()),
                    boundaryPayload.descriptor(), i + 1);
                arguments.add(slot(boundaryPayload.input()));
            }
            boolean callOwnedDealCell = callOwnedCell(dealCell);
            String id = Long.toString(op.opId().id());
            String carrier = "$dc" + id;
            String classSlot = "$dk" + id;
            String sourceClass = "$ds" + id;
            String result = slotOf(op);
            String invoke = carrier + ".$f("
                + (arguments.isEmpty() ? ""
                    : String.join(", ", arguments) + ", ")
                + spanArgs(op) + ")";
            emit("{");
            indent++;
            emit("const " + carrier + " = " + slot(callee.callee()) + ";");
            emit("const " + classSlot + " = $rt.dynClass(" + carrier
                + ");");
            emit("if (" + classSlot + " === \"DEAL_BODY\") {");
            indent++;
            emit(result + " = " + invoke + ";");
            if (callOwnedDealCell) {
                emitBoundaryCheck(dealCell, result, descriptorOf(dealCell));
            }
            indent--;
            emit("} else if (" + classSlot + " === \"ADAPTER\") {");
            indent++;
            emit(result + " = " + invoke + ";");
            emit("const " + sourceClass + " = $rt.dynClass(" + carrier
                + ".$src);");
            emit("if (" + sourceClass + " === null) {");
            indent++;
            emit("$rt.dynFail(" + carrier + ".$src, " + spanArgs(op)
                + ");");
            indent--;
            emit("} else if (" + sourceClass + " === \"DEAL_BODY\") {");
            indent++;
            if (callOwnedDealCell) {
                emitBoundaryCheck(dealCell, result, descriptorOf(dealCell));
            }
            indent--;
            emit("} else if (" + sourceClass + " === \"HOST\") {");
            indent++;
            emitBoundaryCheck(hostCell, result, descriptorOf(hostCell));
            indent--;
            emit("}");
            indent--;
            emit("} else if (" + classSlot + " === \"HOST\") {");
            indent++;
            emit(result + " = " + invoke + ";");
            emitBoundaryCheck(hostCell, result, descriptorOf(hostCell));
            indent--;
            emit("} else {");
            indent++;
            emit("$rt.dynFail(" + carrier + ", " + spanArgs(op) + ");");
            indent--;
            emit("}");
            indent--;
            emit("}");
        }

        /**
         * Whether one recorded DEAL-body/task cell is the call-owned form:
         * its parent {@code RETURN} names no lowered body of the closure
         * (the record shape the validator's dynamic arm requires). The
         * callee-owned form's parent names the callee body, whose own
         * RETURN runs the recorded cell inside the class path.
         */
        private boolean callOwnedCell(SemanticOp cell) {
            OpId parentId = cell.origin() == null ? null
                : cell.origin().parentOpId();
            SemanticOp parent = parentId == null ? null : opsById.get(parentId);
            if (parent == null || parent.kind() != SemanticOpKind.RETURN
                    || !(parent.payload()
                        instanceof KindPayload.ReturnPayload returned)) {
                return false;
            }
            for (LoweredModuleUnit moduleUnit : units.values()) {
                if (moduleUnit.functions().containsKey(returned.function())) {
                    return false;
                }
            }
            return true;
        }

        /**
         * The host callee expression of one call: the recorded source
         * import occurrence's typed surface entry (its alias cell holds
         * the occurrence's fresh typed surface, so the read never falls
         * back to a module/name lookup), or the value the recorded
         * {@code HOST_TO_DEAL} crossing materialized.
         */
        private String hostCalleeExpr(SemanticOp op,
                                      FunctionExecutionBinding binding) {
            switch (binding) {
                case FunctionExecutionBinding.HostFunction host -> {
                    if (host.importCell() != null) {
                        return cell(host.importCell(), 0) + "["
                            + jsString(host.exportName()) + "]";
                    }
                    // A declared function export of a STDLIB import records
                    // no occurrence cell: its surface is once-only per
                    // resolved module, so every call form reaches the same
                    // deployed module entry the value read produces.
                    if (importKindOf(host.hostModuleId())
                            == ModuleImportKind.STDLIB) {
                        return stdlibSurface(op, host.hostModuleId()) + "["
                            + jsString(host.exportName()) + "]";
                    }
                    throw unsupported(op, "the " + op.kind() + " "
                        + op.opId() + " resolves the host export '"
                        + host.hostModuleId().path() + "#"
                        + host.exportName() + "' whose binding records no"
                        + " source import occurrence cell (producer"
                        + " defect; the typed surface is per source import"
                        + " occurrence)");
                }
                case FunctionExecutionBinding.HostFunctionValue value -> {
                    SemanticOp crossing = opsById.get(
                        value.materializingBoundaryOpId());
                    if (crossing == null || !(crossing.payload()
                            instanceof KindPayload.BoundaryPayload boundary)) {
                        throw unsupported(op, "the " + op.kind() + " "
                            + op.opId() + " resolves the host-materialized"
                            + " function value of "
                            + value.materializingBoundaryOpId() + ", which is"
                            + " not a recorded boundary op (producer defect)");
                    }
                    return slot(boundary.input());
                }
                default -> throw new IllegalStateException("the host callee"
                    + " resolution accepts a HostFunction or HostFunctionValue"
                    + " binding only; got " + binding + " (producer defect)");
            }
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
                    bindingOfIdentity(op, indirect.callee());
                case KindPayload.CallCallee.Dynamic ignored -> null;
            };
        }

        /**
         * The recorded binding of one allocation identity: value identities
         * are module-scoped, so the op's own unit's registry is consulted
         * first and a same-numbered identity of another unit never selects
         * a binding (the occurrence correlation of a HOST value read is
         * exactly that unit's fact). The closure-wide scan remains only as
         * the defensive form for a reference the owning unit does not
         * record.
         */
        private FunctionExecutionBinding bindingOfIdentity(SemanticOp op,
                                                           ValueId identity) {
            ModuleId owner = opModule.get(op.opId());
            LoweredModuleUnit unit = owner == null ? null : units.get(owner);
            if (unit != null) {
                FunctionExecutionBinding own = unit.functionBindings().get(
                    new FunctionAllocationIdentity(identity.id()));
                if (own != null) {
                    return own;
                }
            }
            for (LoweredModuleUnit candidate : units.values()) {
                FunctionExecutionBinding found = candidate.functionBindings().get(
                    new FunctionAllocationIdentity(identity.id()));
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        /** The recorded binding of one callee reference, or null for Dynamic. */
        private FunctionExecutionBinding resolvedBindingOf(SemanticOp op,
                KindPayload.CallCallee callee) {
            return switch (callee) {
                case KindPayload.CallCallee.Static staticCallee ->
                    staticCallee.binding();
                case KindPayload.CallCallee.Indirect indirect ->
                    bindingOfIdentity(op, indirect.callee());
                case KindPayload.CallCallee.Dynamic ignored -> null;
            };
        }

        // =====================================================================
        // Async operations
        // =====================================================================

        /**
         * {@code ASYNC_START} — exactly one native operation start: the
         * recorded parameter cells run in one-based order at the call site,
         * then the recorded callee is invoked once (an async wrapper's
         * {@code $f} returns the real Promise; an external async call
         * starts the callee unit's published entry exactly once). The
         * started operation is published in the start's own slot, so the
         * token's single {@code AWAIT} consumes exactly this operation. A
         * nested adapter-source start executes inside its owning adapter
         * invocation and never here.
         */
        private void emitAsyncStart(SemanticOp op) {
            KindPayload.AsyncStartPayload payload =
                (KindPayload.AsyncStartPayload) op.payload();
            if (nestedStartOwner.containsKey(op.opId())) {
                throw new IllegalStateException("the nested adapter-source"
                    + " ASYNC_START " + op.opId() + " executes inside its"
                    + " owning adapter invocation; the block walk must skip it"
                    + " (producer defect)");
            }
            if (payload.parameterBoundaryMode() != ParameterBoundaryMode.RUN) {
                throw unsupported(op, "the ASYNC_START " + op.opId() + " carries"
                    + " the parameter-boundary mode "
                    + payload.parameterBoundaryMode() + " outside an owning"
                    + " adapter invocation (producer defect)");
            }
            List<String> arguments = new ArrayList<>();
            for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                SemanticOp boundary = opsById.get(
                    payload.parameterBoundaryOpIds().get(i));
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                emitCallParameter(boundary, slot(boundaryPayload.input()),
                    boundaryPayload.descriptor(), i + 1);
                arguments.add(slot(boundaryPayload.input()));
            }
            String target = startSlot(op.opId());
            SemanticOp await = awaitOfStart.get(op.opId());
            if (payload.callee() instanceof KindPayload.CallCallee.Dynamic
                    dynamicCallee) {
                emitDynamicAsyncStart(op, payload, dynamicCallee, arguments,
                    await);
                return;
            }
            FunctionExecutionBinding binding =
                resolvedBindingOf(op, payload.callee());
            if (binding instanceof FunctionExecutionBinding.ExternalFunction
                    external) {
                if (external.executionOwner()
                        != ExternalExecutionOwner.SHARED_BODY) {
                    throw unsupported(op, "the ASYNC_START " + op.opId()
                        + " resolves the RETAINED_ABI external '"
                        + external.moduleId().path() + "#"
                        + external.exportName() + "': the retained-ABI async"
                        + " slice belongs to a realized later decomposition"
                        + " unit");
                }
                // The callee unit's published async entry starts the one
                // operation; the caller records no return boundary (the
                // callee body's RETURN runs its own FUNCTION_RETURN cell) and
                // the awaited completion is checked at the await site.
                emit(target + " = $rt.callExport("
                    + jsString(external.moduleId().path()) + ", "
                    + jsString(external.exportName()) + ", ["
                    + String.join(", ", arguments) + "]);");
                return;
            }
            List<String> callArguments = new ArrayList<>(arguments);
            callArguments.add(spanArgs(op));
            if (await != null) {
                // The awaited form carries the await site's own span triple
                // after the call-site triple: a host async wrapper splits
                // the two (shape/parameter checks at the call site, the
                // completion check at the await site).
                callArguments.add(spanArgs(await));
            }
            emit(target + " = " + asyncCalleeExpr(op, payload, binding)
                + ".$f(" + String.join(", ", callArguments) + ");");
        }

        /**
         * The ASYNC_START arm of a runtime-resolved callee: one native
         * start of the resolved class's single operation. A DEAL-body
         * carrier starts the callee body (its own task cell runs inside; a
         * call-owned recorded task cell runs once in the caller-side task
         * wrapper before the token completes), an adapter carrier starts
         * its D15-resolved source class the same way, and a HOST carrier
         * starts the occurrence's async entry, whose operation handle and
         * completion checks the typed wrapper owns. The caller's single
         * AWAIT drains the published operation and runs the single
         * ASYNC_COMPLETION cell; an unresolvable carrier fails closed at
         * the start origin.
         */
        private void emitDynamicAsyncStart(SemanticOp op,
                                           KindPayload.AsyncStartPayload payload,
                                           KindPayload.CallCallee.Dynamic callee,
                                           List<String> arguments,
                                           SemanticOp await) {
            SemanticOp taskCell = payload.returnBoundaryOpId() == null ? null
                : opsById.get(payload.returnBoundaryOpId());
            boolean callOwnedTaskCell = taskCell != null
                && callOwnedCell(taskCell);
            String id = Long.toString(op.opId().id());
            String carrier = "$dc" + id;
            String classSlot = "$dk" + id;
            String sourceClass = "$ds" + id;
            String value = "$dv" + id;
            String target = startSlot(op.opId());
            StringBuilder callArguments = new StringBuilder();
            if (!arguments.isEmpty()) {
                callArguments.append(String.join(", ", arguments))
                    .append(", ");
            }
            callArguments.append(spanArgs(op));
            if (await != null) {
                callArguments.append(", ").append(spanArgs(await));
            }
            emit("{");
            indent++;
            emit("const " + carrier + " = " + slot(callee.callee()) + ";");
            emit("const " + classSlot + " = $rt.dynClass(" + carrier
                + ");");
            emit("if (" + classSlot + " === \"DEAL_BODY\") {");
            indent++;
            if (callOwnedTaskCell) {
                emit(target + " = (async () => {");
                indent++;
                emit("const " + value + " = await " + carrier + ".$f("
                    + callArguments + ");");
                emit("return " + boundaryCheckExpr(taskCell, value) + ";");
                indent--;
                emit("})();");
            } else {
                emit(target + " = " + carrier + ".$f(" + callArguments
                    + ");");
            }
            indent--;
            emit("} else if (" + classSlot + " === \"ADAPTER\") {");
            indent++;
            emit(target + " = (async () => {");
            indent++;
            emit("const " + value + " = await " + carrier + ".$f("
                + callArguments + ");");
            emit("const " + sourceClass + " = $rt.dynClass(" + carrier
                + ".$src);");
            emit("if (" + sourceClass + " === null) {");
            indent++;
            emit("$rt.dynFail(" + carrier + ".$src, " + spanArgs(op)
                + ");");
            indent--;
            emit("}");
            if (callOwnedTaskCell) {
                emit("if (" + sourceClass + " === \"DEAL_BODY\") {");
                indent++;
                emit("return " + boundaryCheckExpr(taskCell, value) + ";");
                indent--;
                emit("}");
            }
            emit("return " + value + ";");
            indent--;
            emit("})();");
            indent--;
            emit("} else if (" + classSlot + " === \"HOST\") {");
            indent++;
            emit(target + " = " + carrier + ".$f(" + callArguments
                + ");");
            indent--;
            emit("} else {");
            indent++;
            emit("$rt.dynFail(" + carrier + ", " + spanArgs(op) + ");");
            indent--;
            emit("}");
            indent--;
            emit("}");
        }

        /**
         * The callee expression of one async start: the recorded binding's
         * value — a lowered body's created closure (or the indirect
         * callee's own slot), an adapter's created wrapper, or the
         * occurrence-owned host entry. An external binding is handled by
         * the {@code callExport} arm before this method runs.
         */
        private String asyncCalleeExpr(SemanticOp op,
                                       KindPayload.AsyncStartPayload payload,
                                       FunctionExecutionBinding binding) {
            if (binding instanceof FunctionExecutionBinding.HostFunction
                    || binding instanceof FunctionExecutionBinding.HostFunctionValue) {
                return hostCalleeExpr(op, binding);
            }
            boolean indirect = payload.callee()
                instanceof KindPayload.CallCallee.Indirect;
            if (binding instanceof FunctionExecutionBinding.LoweredBody body) {
                if (indirect) {
                    return slot(((KindPayload.CallCallee.Indirect)
                        payload.callee()).callee());
                }
                String closure = closureBindings.get(body.functionId().id());
                if (closure == null) {
                    throw unsupported(op, "the static ASYNC_START " + op.opId()
                        + " names the lowered body " + body.functionId()
                        + " whose created closure has no recorded binding cell"
                        + " (producer defect)");
                }
                return closure;
            }
            if (binding instanceof FunctionExecutionBinding.AdapterBinding
                    adapter) {
                if (indirect) {
                    return slot(((KindPayload.CallCallee.Indirect)
                        payload.callee()).callee());
                }
                SemanticOp adaptOp = opsById.get(adapter.adaptOpId());
                if (adaptOp == null
                        || !(adaptOp.result() instanceof ValueId valueId)) {
                    throw unsupported(op, "the static ASYNC_START " + op.opId()
                        + " names the adapter " + adapter.adaptOpId()
                        + " whose creation op carries no result value"
                        + " (producer defect)");
                }
                return slot(valueId);
            }
            throw unsupported(op, "the ASYNC_START " + op.opId() + " resolves"
                + " the " + (binding == null ? "dynamic"
                    : binding.getClass().getSimpleName())
                + " binding: the runtime execution-class dispatch belongs to a"
                + " realized later decomposition unit");
        }

        /**
         * {@code AWAIT} — one native await of the token's operation and the
         * single {@code ASYNC_COMPLETION} cell at the await site. A
         * rejected operation propagates its identical DEAL error (never a
         * re-check or synthesized copy); a completion value crosses the
         * recorded boundary check once.
         */
        private void emitAwait(SemanticOp op) {
            KindPayload.AwaitPayload payload =
                (KindPayload.AwaitPayload) op.payload();
            String target = slotOf(op);
            emit(target + " = await " + tokenSlotOf(op, payload.token()) + ";");
            SemanticOp boundary = opsById.get(payload.completionBoundaryOpId());
            if (boundary == null) {
                throw unsupported(op, "the AWAIT " + op.opId() + " names no"
                    + " recorded ASYNC_COMPLETION boundary (producer defect)");
            }
            emitBoundaryCheck(boundary, target, descriptorOf(boundary));
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
                // The iteration binding is a mutable `let` cell declared
                // once per iteration (the spec's fresh iteration binding
                // captured by binding): a body assignment or a capture's
                // store commits this cell, and a closure created here
                // captures this iteration's own incarnation.
                emit("let " + cellName + " = " + element + ";");
                emitBlockContent(unit, payload.body());
                indent--;
                emit("}");
            } else {
                // A `for (let x of ...)` binds a fresh mutable cell per
                // iteration: the scalar loop binding is assignable in the
                // body and through a captured closure, and the binding is
                // re-created for the next scalar.
                emit(label + ": for (let " + cellName + " of $rt.scalars("
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

        /**
         * One {@code MODULE_IMPORT} — the load-once initialization record
         * of its resolved target, followed by the pinned initializing write
         * of the import's alias cell(s).
         *
         * <p>A COMPILED import requires the target artifact and publishes
         * that module's own surface. A HOST import requires the actual
         * resolved host file once and validates it against the structural
         * declaration surface through {@code $rt.loadHost}: the returned
         * typed surface is the source import occurrence's own (fresh
         * wrappers and synthesized {@code C$new}/{@code C$fields} over the
         * raw META/defaults references), and it is the only surface the
         * occurrence's reads and calls reach. A STDLIB import requires the
         * deployed (project-overridable) stdlib module; that surface is
         * once-only per resolved module and records no occurrence cell.
         * The require specifiers keep the retained
         * {@code relativeSpecifier}/{@code importRequireSpecifier} rules
         * relative to the emitted output, an already-relative host
         * specifier spelling included.</p>
         */
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
                case HOST -> {
                    String loaded = "$d" + op.opId().id();
                    emit("const " + loaded + " = $require("
                        + jsString(importSpecifier(payload.rawSpecifier()))
                        + ");");
                    String declaredMap = declaredHostMap(op,
                        payload.resolvedModule());
                    String call = "$rt.loadHost(" + loaded + ", " + declaredMap
                        + ", " + jsString(payload.rawSpecifier()) + ", "
                        + spanArgs(op) + ")";
                    if (payload.aliasCells().isEmpty()) {
                        // An operation-free import record (no source alias
                        // cell): the source declaration still validates
                        // exactly once, and no surface value escapes.
                        emit(call + ";");
                    } else {
                        for (BindingId alias : payload.aliasCells()) {
                            emit(cell(alias, 0) + " = " + call + ";");
                        }
                    }
                }
                case STDLIB -> {
                    String loaded = "$d" + op.opId().id();
                    emit("const " + loaded + " = $require("
                        + jsString(importSpecifier(payload.rawSpecifier()))
                        + ");");
                    for (BindingId alias : payload.aliasCells()) {
                        emit(cell(alias, 0) + " = " + loaded + ";");
                    }
                }
            }
        }

        /**
         * One {@code EXPORT_READ} — the checked read of one resolved
         * module's export through the reading occurrence's own surface.
         *
         * <p>A COMPILED read resolves the owner module's published value
         * through the private implementation record. A HOST read resolves
         * the typed surface entry of the recorded source import occurrence
         * (its alias cell's {@code $rt.loadHost} result, so repeated reads
         * with one occurrence return the identical wrapper and never
         * re-wrap; a module/name lookup is never a fallback). A STDLIB
         * read resolves the deployed module's export — the once-only
         * surface entry, identical to the wrapper every call form
         * reaches.</p>
         */
        private void emitExportRead(SemanticOp op) {
            KindPayload.ExportReadPayload payload =
                (KindPayload.ExportReadPayload) op.payload();
            ModuleImportKind kind = importKindOf(payload.module());
            if (kind == ModuleImportKind.HOST) {
                emit(slotOf(op) + " = " + hostReadSurface(op, payload)
                    + "[" + jsString(payload.name()) + "];");
                return;
            }
            if (kind == ModuleImportKind.STDLIB) {
                emit(slotOf(op) + " = "
                    + stdlibSurface(op, payload.module()) + "["
                    + jsString(payload.name()) + "];");
                return;
            }
            emit(slotOf(op) + " = $rt.readModule("
                + jsString(payload.module().path()) + ", "
                + jsString(payload.name()) + ");");
        }

        /**
         * The typed surface expression of one host import read: the
         * recorded function-typed read registers exactly one
         * {@code HostFunction} binding carrying its owning source import
         * occurrence's alias cell, and that cell holds the occurrence's
         * typed surface. A read without that correlation is a producer
         * defect: a module/name or first-alias lookup never selects a
         * typed host value.
         */
        private String hostReadSurface(SemanticOp op,
                                       KindPayload.ExportReadPayload payload) {
            if (!(op.result() instanceof ValueId valueId)) {
                throw rejected(op, "the host EXPORT_READ " + op.opId()
                    + " publishes no value identity (producer defect)");
            }
            FunctionExecutionBinding binding = bindingOfIdentity(op, valueId);
            if (!(binding instanceof FunctionExecutionBinding.HostFunction host)
                    || host.importCell() == null) {
                throw rejected(op, "the host EXPORT_READ " + op.opId()
                    + " of '" + payload.module().path() + "#" + payload.name()
                    + " records no owning import-occurrence cell (the typed"
                    + " surface is per source import declaration; a module/name"
                    + " lookup is never a fallback — producer defect)");
            }
            return cell(host.importCell(), 0);
        }

        /**
         * The once-only stdlib surface expression of one artifact: the
         * require binding of the recorded STDLIB import of that resolved
         * module. An artifact that reads or calls a stdlib module it never
         * imported fails closed (the import declaration is the resolution
         * that materialized the surface).
         */
        private String stdlibSurface(SemanticOp op, ModuleId module) {
            String surface = stdlibSurfaces.get(module);
            if (surface == null) {
                throw rejected(op, "the " + op.kind() + " " + op.opId()
                    + " resolves the stdlib module '" + module.path()
                    + "' which no MODULE_IMPORT of this artifact imports (the"
                    + " import declaration materializes the stdlib surface —"
                    + " producer defect)");
            }
            return surface;
        }

        /**
         * {@code STDLIB_CALL} — one cataloged stdlib row over the deployed
         * (project-overridable) stdlib module: the recorded
         * {@code STDLIB_PARAMETER} cells in their one-based declared order,
         * the cataloged callable invoked exactly once with the call-site
         * span triple the deployed wrapper's own boundary checks consume,
         * and the recorded {@code STDLIB_RETURN} cell over the result. The
         * callable is the module surface entry the value read also
         * produces, so a direct call, a stored function value and a
         * namespace read reach the identical wrapper (streams, number
         * time, canonical {@code Error} and JSON included).
         */
        private void emitStdlibCall(SemanticOp op) {
            KindPayload.StdlibCallPayload payload =
                (KindPayload.StdlibCallPayload) op.payload();
            StdlibFunctionCatalog.Entry row = stdlibRowOf(op, payload.function());
            if (op.resultType() instanceof RuntimeDescriptor resultDescriptor
                    && !resultDescriptor.equals(row.returnDescriptor())) {
                throw rejected(op, "the STDLIB_CALL " + op.opId() + " of "
                    + payload.function() + " carries the result descriptor "
                    + resultDescriptor.canonicalSpecText() + " but the closed"
                    + " catalog row declares "
                    + row.returnDescriptor().canonicalSpecText()
                    + " (the row's declared signature is the single authority"
                    + " — producer defect)");
            }
            List<SemanticOp> parameterBoundaries = stdlibParamBoundaries(op);
            if (parameterBoundaries.size() != payload.args().size()) {
                throw rejected(op, "the STDLIB_CALL " + op.opId() + " records "
                    + payload.args().size() + " argument(s) but "
                    + parameterBoundaries.size() + " STDLIB_PARAMETER cell(s)"
                    + " (one cell per declared parameter — producer defect)");
            }
            List<String> callArguments = new ArrayList<>();
            for (int i = 0; i < parameterBoundaries.size(); i++) {
                SemanticOp boundary = parameterBoundaries.get(i);
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                if (!boundaryPayload.input().equals(payload.args().get(i))) {
                    throw rejected(op, "the STDLIB_CALL " + op.opId()
                        + " parameter cell " + boundary.opId() + " checks "
                        + boundaryPayload.input() + ", not the recorded"
                        + " argument " + payload.args().get(i) + " at position "
                        + (i + 1) + " (producer defect)");
                }
                emitBoundaryCheck(boundary, slot(boundaryPayload.input()),
                    boundaryPayload.descriptor());
                callArguments.add(slot(boundaryPayload.input()));
            }
            callArguments.add(spanArgs(op));
            ModuleId stdlibModule = new ModuleId(row.modulePath());
            emit(slotOf(op) + " = " + stdlibSurface(op, stdlibModule) + "["
                + jsString(row.exportName()) + "].$f("
                + String.join(", ", callArguments) + ");");
            SemanticOp returnBoundary = stdlibReturnBoundary(op);
            if (returnBoundary != null) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) returnBoundary.payload();
                if (!boundaryPayload.input().equals(op.result())) {
                    throw rejected(op, "the STDLIB_CALL " + op.opId()
                        + " return cell " + returnBoundary.opId() + " checks "
                        + boundaryPayload.input() + ", not the call result "
                        + op.result() + " (producer defect)");
                }
                emitBoundaryCheck(returnBoundary, slotOf(op),
                    boundaryPayload.descriptor());
            }
        }

        /**
         * The closed catalog row of one {@code STDLIB_CALL} id: the catalog
         * is the STDLIB kind's resolution authority, so an id the closed
         * table does not carry is a producer defect.
         */
        private StdlibFunctionCatalog.Entry stdlibRowOf(SemanticOp op,
                                                        StdlibFunctionId function) {
            for (StdlibFunctionCatalog.Entry entry
                    : StdlibFunctionCatalog.entries()) {
                if (entry.function() == function) {
                    return entry;
                }
            }
            throw rejected(op, "the STDLIB_CALL " + op.opId() + " resolves "
                + function + " which the closed stdlib catalog does not carry"
                + " (producer defect)");
        }

        /** The STDLIB_PARAMETER children of one call, in one-based order. */
        private List<SemanticOp> stdlibParamBoundaries(SemanticOp op) {
            List<SemanticOp> boundaries = new ArrayList<>();
            for (SemanticOp candidate : opsById.values()) {
                if (candidate.kind() == SemanticOpKind.BOUNDARY
                        && op.opId().equals(candidate.origin().parentOpId())
                        && ((KindPayload.BoundaryPayload) candidate.payload())
                            .kind() == BoundaryKind.STDLIB_PARAMETER) {
                    boundaries.add(candidate);
                }
            }
            boundaries.sort(java.util.Comparator.comparingLong(
                candidate -> candidate.opId().id()));
            return boundaries;
        }

        /** The single recorded STDLIB_RETURN cell of one call, or null. */
        private SemanticOp stdlibReturnBoundary(SemanticOp op) {
            SemanticOp found = null;
            for (SemanticOp candidate : opsById.values()) {
                if (candidate.kind() != SemanticOpKind.BOUNDARY
                        || !op.opId().equals(candidate.origin().parentOpId())
                        || ((KindPayload.BoundaryPayload) candidate.payload())
                            .kind() != BoundaryKind.STDLIB_RETURN) {
                    continue;
                }
                if (found == null || candidate.opId().id() < found.opId().id()) {
                    found = candidate;
                }
            }
            return found;
        }

        /**
         * The require specifier of one HOST/STDLIB import declaration,
         * relative to the emitting artifact's own output directory: an
         * already-relative host specifier keeps its spelling verbatim (the
         * retained already-relative spelling rule), every other
         * target takes the relative path from this artifact's directory to
         * the deployed file, the extension omitted. No ambient
         * {@code NODE_PATH}/{@code node_modules} resolution exists.
         */
        private String importSpecifier(String rawSpecifier) {
            if (rawSpecifier.startsWith("./") || rawSpecifier.startsWith("../")) {
                return rawSpecifier;
            }
            return relativeSpecifier(currentModule.path(), rawSpecifier);
        }

        /**
         * The declared-map expression of one HOST import: the structural
         * declaration module's exports in the supplied ascending Java
         * String name order (the pinned JS declared-export read order),
         * each entry carrying its structural descriptor and — for a
         * declared class — the declaration-order field metadata. The
         * runtime loader validates the raw module object against exactly
         * this map; extra raw exports stay ignorable and no emitter-side
         * execution or copying of host code exists.
         */
        private String declaredHostMap(SemanticOp op, ModuleId module) {
            RuntimeDeclarationSurface.RuntimeDeclarationModule declared =
                input.declarations().modules().get(module);
            if (declared == null) {
                throw rejected(op, "the HOST MODULE_IMPORT " + op.opId()
                    + " resolves the declaration module '" + module.path()
                    + "' which the structural declaration surface does not"
                    + " carry (the projection covers every declaration module"
                    + " of the compilation — producer defect)");
            }
            if (declared.kind()
                    != RuntimeDeclarationSurface.DeclarationKind.HOST) {
                throw rejected(op, "the HOST MODULE_IMPORT " + op.opId()
                    + " resolves the " + declared.kind() + " declaration"
                    + " module '" + module.path() + "': a host import never"
                    + " resolves an extern-C declaration (the JavaScript"
                    + " backend rejects @extern-c imports with E6006 before"
                    + " lowering — producer defect)");
            }
            StringBuilder map = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, RuntimeDescriptor> export
                    : declared.exports().entrySet()) {
                if (!first) {
                    map.append(", ");
                }
                first = false;
                map.append(jsString(export.getKey())).append(": ")
                    .append(declaredHostEntry(op, declared, export.getKey(),
                        export.getValue()));
            }
            return map.append("}").toString();
        }

        /** One declared host export's runtime declared-map entry. */
        private String declaredHostEntry(SemanticOp op,
                RuntimeDeclarationSurface.RuntimeDeclarationModule declared,
                String name, RuntimeDescriptor descriptor) {
            if (descriptor instanceof RuntimeDescriptor.Class classDescriptor) {
                RuntimeDeclarationSurface.RuntimeDeclaredClass declaredClass =
                    declared.classes().get(name);
                if (declaredClass == null
                        || !declaredClass.classId()
                            .equals(classDescriptor.classId())) {
                    throw rejected(op, "the declared host class export '"
                        + name + "' carrying " + classDescriptor.classId()
                        + " has no matching declared-class entry in the"
                        + " structural declaration surface (producer"
                        + " defect)");
                }
                return "{ $k: " + jsString("class") + ", $d: "
                    + jsString(descriptor.canonicalSpecText()) + ", $fields: "
                    + declaredHostFields(declaredClass) + " }";
            }
            // A function export is the declared function form; every other
            // declaration kind (unreachable for checker-accepted host
            // declarations, which export functions and classes only) keeps
            // the defensive function form, so the deployed runtime raises
            // its pinned E8011 unsupported-descriptor arm at load.
            return "{ $k: " + jsString("function") + ", $d: "
                + jsString(descriptor.canonicalSpecText()) + " }";
        }

        /**
         * One declared class's field-metadata array in declaration order:
         * the field name, its structural descriptor, and the optional /
         * nullable / has-default flags ({@code optional} is exactly
         * {@code !required}).
         */
        private String declaredHostFields(
                RuntimeDeclarationSurface.RuntimeDeclaredClass declaredClass) {
            StringBuilder fields = new StringBuilder("[");
            boolean first = true;
            for (RuntimeDeclarationSurface.RuntimeDeclaredField field
                    : declaredClass.fields()) {
                if (!first) {
                    fields.append(", ");
                }
                first = false;
                fields.append("{ name: ").append(jsString(field.name()))
                    .append(", $d: ")
                    .append(jsString(field.descriptor().canonicalSpecText()))
                    .append(", optional: ").append(!field.required())
                    .append(", nullable: ")
                    .append(field.descriptor()
                        instanceof RuntimeDescriptor.Nullable)
                    .append(", hasDefault: ").append(field.hasDefault())
                    .append(" }");
            }
            return fields.append("]").toString();
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
        // Classes: layouts, defaults, factories, construction, fields, JSON
        // =====================================================================

        /**
         * The module-init class machinery: one per-attempt default thunk per
         * recorded {@code CLASS_DEFAULT} op, one private factory entry per
         * registered construction entry, and the public class surface
         * (META, the full-construction {@code C$new}, and the hidden
         * {@code C$fields} descriptor export of a {@code @jsonable} class).
         * Creating the closures executes no default and no construction; the
         * per-attempt thunks give every construction (and every nested or
         * reentrant attempt) a fresh scope, so an inner attempt never
         * overwrites an outer attempt's operands. Ordinary captures of the
         * surrounding module cells stay live.
         */
        private void emitClassMachinery(LoweredModuleUnit unit) {
            boolean emitted = false;
            for (SemanticOp op : unit.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_DEFAULT) {
                    emitClassDefaultThunk(unit, op);
                    emitted = true;
                }
            }
            ClassFactoryRegistry registry = registries.get(unit.moduleId());
            if (registry != null) {
                for (Map.Entry<ClassFactoryId, OpId> entry
                        : registry.factories().entrySet()) {
                    SemanticOp factoryOp = opsById.get(entry.getValue());
                    if (factoryOp == null
                            || factoryOp.kind() != SemanticOpKind.CLASS_FACTORY) {
                        throw new SharedEmissionFailure("the class-factory"
                            + " registry of module '" + unit.moduleId().path()
                            + "' binds " + entry.getKey() + " to "
                            + entry.getValue() + ", which is not a recorded"
                            + " CLASS_FACTORY (producer defect)", null);
                    }
                    emitClassFactoryEntry(unit, entry.getKey(), factoryOp);
                    emitted = true;
                }
            }
            List<ClassId> exported = exportedClassesOf(unit.moduleId());
            for (ClassId classId : exported) {
                ClassLayout layout = requireUnitLayout(unit.moduleId(), classId,
                    null);
                beginMappedClassGroup(unit, classId);
                emitClassPlan(classId, layout);
                if (unitDeclaresJsonable(unit.moduleId(), classId)) {
                    emitClassFieldsArray(classId, layout);
                }
                emitted = true;
            }
            if (!exported.isEmpty()) {
                emit("// The public class surface: the META value, the"
                    + " full-construction C$new, and (for a @jsonable class)");
                emit("// the hidden C$fields descriptor export.");
                for (ClassId classId : exported) {
                    String modulePath = unit.moduleId().path();
                    String name = classId.name();
                    // The exported class's published surface is a wrapper
                    // group: it maps to the class's recorded declaration
                    // site, never to a caller's statement.
                    beginMappedClassGroup(unit, classId);
                    emit("$rt.publishModule(" + jsString(modulePath) + ", "
                        + jsString(name) + ", { $kind: \"class\", $classname: "
                        + jsString(classId.text()) + " });");
                    // The public constructor keeps the retained plan phase
                    // order (the host-provided object's extra-key scan, then
                    // the omitted required defaults, then validation) — the
                    // surface's provided set is host-supplied, while the
                    // recorded CLASS_NEW arms render the shared construction
                    // phase order (defaults, extra-key scan, declaration-order
                    // boundaries) for the source literals the checker admits.
                    emit("$rt.publishModule(" + jsString(modulePath) + ", "
                        + jsString(name + "$new") + ", (provided, $file, $line,"
                        + " $column) => $rt.classPlan("
                        + jsString(classId.text()) + ", "
                        + classPlanExprs.get(classId) + ", provided, $file,"
                        + " $line, $column));");
                    if (unitDeclaresJsonable(unit.moduleId(), classId)) {
                        emit("$rt.publishModule(" + jsString(modulePath) + ", "
                            + jsString(name + "$fields") + ", "
                            + classFieldsExprs.get(classId) + ");");
                    }
                }
            }
            if (emitted) {
                emit("");
            }
        }

        /**
         * Opens the mapped group of one exported class's derived wrapper
         * group (its constructor plan, its {@code $fields} descriptor array
         * and its published public surface) at the class's recorded local
         * declaration site — the copied origin of the class's
         * {@code CLASS_FACTORY} construction entry. A class the project
         * never constructs, imports or decodes records no factory op: its
         * derived group is then genuinely unlocated and stays unmapped
         * instead of borrowing a coordinate from another statement.
         */
        private void beginMappedClassGroup(LoweredModuleUnit unit,
                                           ClassId classId) {
            SemanticOp declaration = classFactoryOpOf(unit.moduleId(), classId);
            if (declaration != null) {
                beginMappedGroup(declaration);
            }
        }

        /**
         * The recorded {@code CLASS_FACTORY} op of one class in the given
         * module (the owner-side construction entry the class declaration
         * produced), or null when the unit records none.
         */
        private SemanticOp classFactoryOpOf(ModuleId module, ClassId classId) {
            ClassFactoryRegistry registry = registries.get(module);
            if (registry == null) {
                return null;
            }
            for (OpId factoryOpId : registry.factories().values()) {
                SemanticOp factoryOp = opsById.get(factoryOpId);
                if (factoryOp != null
                        && factoryOp.payload()
                            instanceof KindPayload.ClassFactoryPayload payload
                        && payload.classId().equals(classId)) {
                    return factoryOp;
                }
            }
            return null;
        }

        /**
         * One detached class-default thunk: the recorded default block as a
         * fresh-scope arrow created at module load and invoked once per
         * construction attempt. The result is the {@code CLASS_DEFAULT} op's
         * own value slot; the thunk runs no boundary of its own (the
         * constructor's {@code CLASS_DEFAULT_FIELD} child admits the value at
         * its recorded origin).
         */
        private void emitClassDefaultThunk(LoweredModuleUnit unit, SemanticOp op) {
            KindPayload.ClassDefaultPayload payload =
                (KindPayload.ClassDefaultPayload) op.payload();
            if (!(op.result() instanceof ValueId result)) {
                throw rejected(op, "the CLASS_DEFAULT " + op.opId()
                    + " publishes no ValueId result (producer defect)");
            }
            // The thunk's own group maps to the recorded default
            // expression's local site; the descriptive line follows.
            beginMappedGroup(op);
            emit("// Class default " + payload.classId().text() + "."
                + payload.field());
            emit("const " + defaultThunkName(op) + " = () => {");
            indent++;
            OpId previous = currentDefaultOp;
            currentDefaultOp = op.opId();
            try {
                emitFunctionBody(unit, payload.defaultBlock());
            } finally {
                currentDefaultOp = previous;
            }
            emit("return " + slot(result) + ";");
            indent--;
            emit("};");
        }

        /**
         * One private class-factory entry: the owner module's defaults-only
         * transfer. The entry runs every omitted required-present default in
         * declaration order (a caller-provided field is skipped), returns the
         * internal defaults-filled transfer value and tags nothing — the
         * caller performs the public construction. Registration executes no
         * default.
         */
        private void emitClassFactoryEntry(LoweredModuleUnit unit,
                                           ClassFactoryId factoryId,
                                           SemanticOp factoryOp) {
            KindPayload.ClassFactoryPayload payload =
                (KindPayload.ClassFactoryPayload) factoryOp.payload();
            ClassLayout layout = requireUnitLayout(unit.moduleId(),
                payload.classId(), factoryOp);
            String transfer = "$t" + factoryOp.opId().id();
            // The factory entry is the owner module's own wrapper group:
            // it maps to the class's recorded local declaration site.
            beginMappedGroup(factoryOp);
            emit("// The defaults-only factory transfer of "
                + payload.classId().text() + ".");
            emit("$rt.registerFactory(" + jsString(unit.moduleId().path()) + ", "
                + factoryId.id() + ", ($provided) => {");
            indent++;
            emit("const " + transfer + " = {};");
            for (OpId defaultOpId : payload.classDefaultOpIds()) {
                SemanticOp defaultOp = opsById.get(defaultOpId);
                if (defaultOp == null
                        || defaultOp.kind() != SemanticOpKind.CLASS_DEFAULT) {
                    throw rejected(factoryOp, "the CLASS_FACTORY "
                        + factoryOp.opId() + " names the CLASS_DEFAULT child "
                        + defaultOpId + ", which is not a recorded"
                        + " CLASS_DEFAULT (producer defect)");
                }
                KindPayload.ClassDefaultPayload defaultPayload =
                    (KindPayload.ClassDefaultPayload) defaultOp.payload();
                if (!defaultPayload.classId().equals(payload.classId())) {
                    throw rejected(factoryOp, "the CLASS_FACTORY "
                        + factoryOp.opId() + " names the CLASS_DEFAULT child "
                        + defaultOpId + " of " + defaultPayload.classId()
                        + ", not of " + payload.classId()
                        + " (producer defect)");
                }
                if (!(defaultOp.result() instanceof ValueId result)) {
                    throw rejected(defaultOp, "the CLASS_DEFAULT " + defaultOpId
                        + " publishes no ValueId result (producer defect)");
                }
                if (fieldOf(layout, defaultPayload.field()) == null) {
                    throw rejected(factoryOp, "the CLASS_FACTORY "
                        + factoryOp.opId() + " names the CLASS_DEFAULT child "
                        + defaultOpId + " of the undeclared field '"
                        + defaultPayload.field() + "' (producer defect)");
                }
                emit("if ($provided.indexOf("
                    + jsString(defaultPayload.field()) + ") < 0) {");
                indent++;
                String attempt = "$v" + factoryOp.opId().id() + "_"
                    + defaultOp.opId().id();
                emit("const " + attempt + " = " + defaultThunkName(defaultOp)
                    + "();");
                emit("$rt.setProp(" + transfer + ", "
                    + jsString(defaultPayload.field()) + ", " + attempt
                    + ");");
                indent--;
                emit("}");
            }
            emit("return " + transfer + ";");
            indent--;
            emit("});");
        }

        /**
         * One class's constructor plan over the published plan shape: the
         * declaration-order fields with their canonical descriptors and the
         * per-field default evaluators. It is the {@code C$new} full
         * construction's input (the retained public constructor ABI); the
         * plan entries carry no private layout identifier and no target
         * grammar.
         */
        private void emitClassPlan(ClassId classId, ClassLayout layout) {
            StringBuilder expression = new StringBuilder("[");
            for (int i = 0; i < layout.fields().size(); i++) {
                ClassLayout.FieldLayout field = layout.fields().get(i);
                if (i > 0) {
                    expression.append(", ");
                }
                expression.append("{ name: ").append(jsString(field.name()))
                    .append(", descriptor: ")
                    .append(jsString(field.descriptor().canonicalSpecText()))
                    .append(", optional: ").append(!field.required());
                SemanticOp defaultOp = defaultOpOf(classId, field.name());
                if (defaultOp != null && field.required()) {
                    expression.append(", evaluator: ")
                        .append(defaultThunkName(defaultOp));
                }
                expression.append(" }");
            }
            expression.append("]");
            String name = "$p" + classId.name();
            emit("const " + name + " = " + expression + ";");
            classPlanExprs.put(classId, name);
        }

        /**
         * One class's declaration-order {@code $fields} descriptor array (the
         * pinned JSON descriptor shape): the field name, the closed JSON kind,
         * the nested class/array sub-shape, the optional/nullable flags, the
         * has-default flag and the per-field default evaluator. A same-module
         * class-valued field carries its nested array inline (the checked
         * jsonable dependency graph is acyclic); a class of another module
         * carries that module's published {@code C$fields} export, so its own
         * default blocks run in their owning module.
         */
        private void emitClassFieldsArray(ClassId classId, ClassLayout layout) {
            String name = "$f" + classId.name();
            emit("const " + name + " = "
                + fieldsArrayText(classId, layout, new LinkedHashSet<>()) + ";");
            classFieldsExprs.put(classId, name);
        }

        private String fieldsArrayText(ClassId classId, ClassLayout layout,
                                       Set<ClassId> stack) {
            if (!stack.add(classId)) {
                throw new SharedEmissionFailure("the @jsonable class " + classId
                    + " closes a class-valued descriptor cycle: the checked"
                    + " jsonable dependency graph is acyclic (producer defect)",
                    null);
            }
            StringBuilder array = new StringBuilder("[");
            for (int i = 0; i < layout.fields().size(); i++) {
                if (i > 0) {
                    array.append(", ");
                }
                array.append(fieldEntryText(classId, layout.fields().get(i), stack));
            }
            array.append("]");
            stack.remove(classId);
            return array.toString();
        }

        private String fieldEntryText(ClassId classId,
                                      ClassLayout.FieldLayout field,
                                      Set<ClassId> stack) {
            RuntimeDescriptor descriptor = field.descriptor();
            boolean nullable = descriptor instanceof RuntimeDescriptor.Nullable;
            RuntimeDescriptor base = nullable
                ? ((RuntimeDescriptor.Nullable) descriptor).inner() : descriptor;
            StringBuilder entry = new StringBuilder("{ name: ")
                .append(jsString(field.name()))
                .append(", jtype: ").append(jsString(jsonJType(base)));
            switch (base) {
                case RuntimeDescriptor.Class classDescriptor -> {
                    entry.append(", className: ")
                        .append(jsString(classDescriptor.classId().text()))
                        .append(", fields: ")
                        .append(nestedFieldsText(classDescriptor.classId(), stack));
                }
                case RuntimeDescriptor.Array array -> entry.append(", element: ")
                    .append(elementEntryText(array.element(), stack));
                default -> {
                }
            }
            SemanticOp defaultOp = defaultOpOf(classId, field.name());
            entry.append(", optional: ").append(!field.required())
                .append(", nullable: ").append(nullable)
                .append(", hasDefault: ").append(defaultOp != null);
            if (defaultOp != null && field.required()) {
                entry.append(", evaluator: ").append(defaultThunkName(defaultOp));
            }
            return entry.append(" }").toString();
        }

        /** The nested descriptor array of one class-valued field. */
        private String nestedFieldsText(ClassId nested, Set<ClassId> stack) {
            ModuleId owner = jsonPlanOwners.get(nested);
            if (owner == null) {
                ModuleId declarationModule =
                    declarationModuleOfClass.get(nested);
                if (declarationModule != null
                        && !units.containsKey(declarationModule)) {
                    // A declaration-file class has no implementation unit, so
                    // it generates no C$fields record and no class-default
                    // evaluator: a nested JSON walk over it cannot be
                    // rendered. The shared JSON layout validation rejects the
                    // link before rendering, so reaching this is a producer
                    // defect.
                    throw new SharedEmissionFailure("the class " + nested
                        + " of a jsonable field is declared by the module '"
                        + declarationModule.path() + "' which has no"
                        + " implementation unit: the nested"
                        + " declaration-class walk has no plan, factory or"
                        + " default evaluator (producer defect)", null);
                }
                throw new SharedEmissionFailure("the class " + nested
                    + " of a jsonable field resolves in no declaration of"
                    + " the project closure (producer defect)", null);
            }
            if (owner.equals(currentModule)) {
                ClassLayout nestedLayout = unitLayoutOf(currentModule, nested);
                if (nestedLayout == null) {
                    throw new SharedEmissionFailure("the plan-bearing jsonable"
                        + " class " + nested + " carries no layout in its"
                        + " declaring unit (producer defect)", null);
                }
                return fieldsArrayText(nested, nestedLayout, stack);
            }
            // The canonical JSON plan key carries the plan-bearing
            // declaration's layout: a consumer reads that declaration's
            // published descriptor array, never a same-identity sibling's.
            return "$rt.readModule(" + jsString(owner.path()) + ", "
                + jsString(nested.name() + "$fields") + ")";
        }

        /** One array element's descriptor entry (no name/default roles). */
        private String elementEntryText(RuntimeDescriptor element,
                                        Set<ClassId> stack) {
            boolean nullable = element instanceof RuntimeDescriptor.Nullable;
            RuntimeDescriptor base = nullable
                ? ((RuntimeDescriptor.Nullable) element).inner() : element;
            StringBuilder entry = new StringBuilder("{ jtype: ")
                .append(jsString(jsonJType(base)));
            switch (base) {
                case RuntimeDescriptor.Class classDescriptor -> {
                    entry.append(", className: ")
                        .append(jsString(classDescriptor.classId().text()))
                        .append(", fields: ")
                        .append(nestedFieldsText(classDescriptor.classId(), stack));
                }
                case RuntimeDescriptor.Array array -> entry.append(", element: ")
                    .append(elementEntryText(array.element(), stack));
                default -> {
                }
            }
            return entry.append(", optional: false, nullable: ")
                .append(nullable).append(" }").toString();
        }

        /** The closed JSON kind name of one canonical descriptor. */
        private String jsonJType(RuntimeDescriptor descriptor) {
            if (descriptor == RuntimeDescriptor.Null.INSTANCE) {
                return "null";
            }
            if (descriptor == RuntimeDescriptor.Boolean.INSTANCE) {
                return "boolean";
            }
            if (descriptor == RuntimeDescriptor.Int.INSTANCE) {
                return "int";
            }
            if (descriptor == RuntimeDescriptor.Number.INSTANCE) {
                return "number";
            }
            if (descriptor == RuntimeDescriptor.String.INSTANCE) {
                return "string";
            }
            if (descriptor == RuntimeDescriptor.Table.INSTANCE) {
                return "table";
            }
            if (descriptor instanceof RuntimeDescriptor.Class) {
                return "class";
            }
            if (descriptor instanceof RuntimeDescriptor.Array) {
                return "array";
            }
            throw new SharedEmissionFailure("the @jsonable field descriptor "
                + descriptor.canonicalSpecText() + " is not a JSON kind: the"
                + " checker's jsonable-field rule admits the primitive, array,"
                + " nullable and jsonable-class shapes only (producer defect)",
                null);
        }

        // ----- Construction -----

        /**
         * One {@code CLASS_NEW}: the shared construction phases over the
         * resolved module-level layout and default owner. {@code LOCAL}
         * constructs directly, {@code SHARED_FACTORY} transfers the owner
         * module's defaults first, {@code HOST_DEFAULTS} runs the host
         * occurrence's synthesized full construction over the loaded
         * defaults, and the builtin {@code Error} keeps its
         * compiler-constant defaults. An extern-C owner fails closed: the
         * JavaScript backend rejects @extern-c before lowering (E6006).
         */
        private void emitClassNew(SemanticOp op) {
            KindPayload.ClassNewPayload payload =
                (KindPayload.ClassNewPayload) op.payload();
            switch (payload.defaultOwner()) {
                case LOCAL -> emitClassNewLocal(op, payload);
                case SHARED_FACTORY -> emitClassNewSharedFactory(op, payload);
                case BUILTIN_DEFAULTS -> emitBuiltinErrorConstruction(op);
                case HOST_DEFAULTS -> emitClassNewHostDefaults(op, payload);
                case FFI_PLAN -> throw unsupported(op, "the CLASS_NEW "
                    + op.opId() + " carries the extern-C C-struct default owner"
                    + " of " + payload.classId() + ": the JavaScript backend"
                    + " rejects @extern-c before lowering (E6006), so a C-struct"
                    + " construction reaching this renderer is a producer defect");
            }
        }

        /**
         * One host-declaration class construction: the class's declaration
         * module is a HOST module and the raw defaults are that host's own
         * data, so the construction runs the source import occurrence's
         * synthesized {@code C$new} — the full-construction entry the typed
         * surface owns (fresh per import, over the raw {@code <C>_defaults}
         * reference {@code $rt.loadHost} retained). The provided values
         * therefore cross the host's own defaults-map gate, and each
         * provided field then runs its recorded declaration-order boundary
         * at its own origin. A failed construction publishes no instance
         * and the completed host/default effects stay.
         */
        private void emitClassNewHostDefaults(SemanticOp op,
                                              KindPayload.ClassNewPayload payload) {
            ClassLayout layout = requirePayloadLayout(op, payload.classId(),
                payload.layout());
            ClassLayout registered = seedLayouts.get(payload.classId());
            if (registered == null || !registered.equals(layout)) {
                throw rejected(op, "the CLASS_NEW " + op.opId()
                    + " carries a host-declaration layout for "
                    + payload.classId() + " that differs from the declaration"
                    + " module's registered layout (producer defect)");
            }
            if (payload.classFactoryRef() != null
                    || !payload.classDefaultOpIds().isEmpty()) {
                throw rejected(op, "the CLASS_NEW " + op.opId() + " carries the"
                    + " host-declaration default owner of " + payload.classId()
                    + " with a factory ref or a default child list: the loaded"
                    + " host defaults are data and run in the host's own"
                    + " construction (producer defect)");
            }
            DeclaredHostClass declared = requireDeclaredHostClass(op,
                payload.classId());
            emitExtraFieldFailure(op, payload, layout);
            String provided = "$pv" + op.opId().id();
            emit("const " + provided + " = {};");
            for (KindPayload.ProvidedField field : payload.providedFields()) {
                emit("$rt.setProp(" + provided + ", " + jsString(field.name())
                    + ", " + slot(field.valueOpId()) + ");");
            }
            String target = slotOf(op);
            emit(target + " = " + cell(declared.importCell(), 0) + "["
                + jsString(declared.name() + "$new") + "](" + provided + ", "
                + spanArgs(op) + ");");
            for (KindPayload.FieldBoundary entry : payload.fieldBoundaries()) {
                SemanticOp boundary = requireBoundaryChild(op,
                    entry.boundaryOpId());
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                if (boundaryPayload.kind() != BoundaryKind.CLASS_LITERAL_FIELD) {
                    throw rejected(op, "the CLASS_NEW " + op.opId()
                        + " host-declaration construction carries the "
                        + boundaryPayload.kind() + " boundary cell "
                        + boundary.opId() + ": the host defaults are data, so"
                        + " no default child exists (producer defect)");
                }
                String admitted = "$h" + boundary.opId().id();
                emit("const " + admitted + " = "
                    + boundaryCheckExpr(boundary,
                        slot(boundaryPayload.input())) + ";");
                emit("$rt.setProp(" + target + ", " + jsString(entry.field())
                    + ", " + admitted + ");");
            }
        }

        /** One declared host class of the structural declaration surface. */
        private record DeclaredHostClass(String name, BindingId importCell) {
        }

        /**
         * The declared host class of one class identity together with the
         * artifact's source import occurrence of its declaration module.
         * The occurrence is the recorded import declaration that named the
         * class (the class's own declaration module is reachable only
         * through it); a class no declaration module declares, or a
         * construction in an artifact without that import occurrence, is a
         * producer defect — never an invented surface.
         */
        private DeclaredHostClass requireDeclaredHostClass(SemanticOp op,
                                                           ClassId classId) {
            // The artifact's own HOST occurrences first, in recorded import
            // order: a construction resolves the declaration module its
            // import declaration named, never a first-identity match.
            for (Map.Entry<ModuleId, BindingId> occurrence
                    : hostOccurrences.entrySet()) {
                RuntimeDeclarationSurface.RuntimeDeclarationModule declared =
                    input.declarations().modules().get(occurrence.getKey());
                if (declared == null || declared.kind()
                        != RuntimeDeclarationSurface.DeclarationKind.HOST) {
                    continue;
                }
                for (Map.Entry<String,
                        RuntimeDeclarationSurface.RuntimeDeclaredClass>
                        declaredClass : declared.classes().entrySet()) {
                    if (declaredClass.getValue().classId().equals(classId)) {
                        return new DeclaredHostClass(declaredClass.getKey(),
                            occurrence.getValue());
                    }
                }
            }
            for (Map.Entry<ModuleId,
                    RuntimeDeclarationSurface.RuntimeDeclarationModule> entry
                    : input.declarations().modules().entrySet()) {
                for (Map.Entry<String,
                        RuntimeDeclarationSurface.RuntimeDeclaredClass>
                        declared : entry.getValue().classes().entrySet()) {
                    if (!declared.getValue().classId().equals(classId)) {
                        continue;
                    }
                    throw rejected(op, "the CLASS_NEW " + op.opId()
                        + " constructs the declared host class " + classId
                        + " of module '" + entry.getKey().path()
                        + "' which no HOST import occurrence of this artifact"
                        + " imports: the construction runs through the import"
                        + " declaration's own typed surface (producer defect)");
                }
            }
            throw rejected(op, "the CLASS_NEW " + op.opId() + " carries the"
                + " host-declaration default owner of " + classId + ", which no"
                + " class of the structural declaration surface declares"
                + " (producer defect)");
        }

        private void emitClassNewLocal(SemanticOp op,
                                       KindPayload.ClassNewPayload payload) {
            ClassLayout layout = requirePayloadLayout(op, payload.classId(),
                payload.layout());
            ClassLayout unitLayout = unitLayoutOf(currentModule,
                payload.classId());
            if (unitLayout == null || !unitLayout.equals(layout)) {
                throw rejected(op, "the LOCAL CLASS_NEW " + op.opId()
                    + " of " + payload.classId() + " records a layout that"
                    + " differs from its declaring unit's layout (producer"
                    + " defect)");
            }
            Set<String> providedNames = new LinkedHashSet<>();
            for (KindPayload.ProvidedField field : payload.providedFields()) {
                providedNames.add(field.name());
            }
            // Phase 1: the omitted required-present defaults, in declaration
            // order, each a fresh per-attempt thunk invocation into the
            // construction's own attempt slot (so a nested or reentrant
            // attempt never overwrites this attempt's operands).
            Map<Long, String> defaultAttempts = new LinkedHashMap<>();
            for (OpId defaultOpId : payload.classDefaultOpIds()) {
                SemanticOp defaultOp = requireDefaultChild(op, payload.classId(),
                    defaultOpId);
                KindPayload.ClassDefaultPayload defaultPayload =
                    (KindPayload.ClassDefaultPayload) defaultOp.payload();
                if (providedNames.contains(defaultPayload.field())) {
                    continue;
                }
                String attempt = "$v" + op.opId().id() + "_"
                    + defaultOp.opId().id();
                emit("let " + attempt + " = " + defaultThunkName(defaultOp)
                    + "();");
                defaultAttempts.put(((ValueId) defaultOp.result()).id(), attempt);
            }
            // Phase 2: the extra-key scan (the first provided-source name
            // outside the layout), after the defaults and before any boundary.
            emitExtraFieldFailure(op, payload, layout);
            // Phase 3: the declaration-order field boundaries.
            Map<String, String> fieldValues = new LinkedHashMap<>();
            for (KindPayload.FieldBoundary entry : payload.fieldBoundaries()) {
                SemanticOp boundary = requireBoundaryChild(op, entry.boundaryOpId());
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                String input = defaultAttempts.getOrDefault(
                    boundaryPayload.input().id(), slot(boundaryPayload.input()));
                emitBoundaryCheck(boundary, input, boundaryPayload.descriptor());
                fieldValues.put(entry.field(), input);
            }
            emitInstance(op, payload, layout, providedNames, fieldValues);
        }

        private void emitClassNewSharedFactory(SemanticOp op,
                                               KindPayload.ClassNewPayload payload) {
            OpId factoryOpId = factoryOpOf(op, payload.classFactoryRef());
            if (factoryOpId == null) {
                throw rejected(op, "the CLASS_NEW " + op.opId()
                    + " classFactoryRef " + payload.classFactoryRef()
                    + " resolves in no module's ClassFactoryRegistry in the"
                    + " closure (producer defect)");
            }
            ClassLayout layout = requirePayloadLayout(op, payload.classId(),
                payload.layout());
            ClassLayout ownerLayout = unitLayoutOf(factoryOpId.module(),
                payload.classId());
            if (ownerLayout == null || !ownerLayout.equals(layout)) {
                throw rejected(op, "the SHARED_FACTORY CLASS_NEW " + op.opId()
                    + " of " + payload.classId() + " records a layout that"
                    + " differs from the declaration of its factory owner '"
                    + factoryOpId.module().path() + "' (producer defect)");
            }
            if (factoryOpId.module().equals(currentModule)) {
                throw rejected(op, "the CLASS_NEW " + op.opId()
                    + " of the same-module class " + payload.classId()
                    + " carries the SHARED_FACTORY owner: same-module literals"
                    + " stay LOCAL (producer defect)");
            }
            SemanticOp factoryOp = opsById.get(factoryOpId);
            if (factoryOp == null
                    || factoryOp.kind() != SemanticOpKind.CLASS_FACTORY) {
                throw rejected(op, "the CLASS_NEW " + op.opId()
                    + " resolves the factory op " + factoryOpId + " outside"
                    + " the pinned CLASS_FACTORY kind (producer defect)");
            }
            KindPayload.ClassFactoryPayload factoryPayload =
                (KindPayload.ClassFactoryPayload) factoryOp.payload();
            if (!factoryPayload.classId().equals(payload.classId())) {
                throw rejected(factoryOp, "the CLASS_NEW " + op.opId()
                    + " resolves a factory of " + factoryPayload.classId()
                    + " (producer defect)");
            }
            if (!(factoryOp.result() instanceof ValueId factoryResult)) {
                throw rejected(factoryOp, "the CLASS_FACTORY " + factoryOp.opId()
                    + " publishes no ValueId result (producer defect)");
            }
            Set<String> providedNames = new LinkedHashSet<>();
            StringBuilder names = new StringBuilder();
            for (KindPayload.ProvidedField field : payload.providedFields()) {
                providedNames.add(field.name());
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(jsString(field.name()));
            }
            // Phase 1: the owner's defaults-only transfer (the owner's
            // recorded default blocks run in their owning module).
            String transfer = "$t" + op.opId().id();
            emit("const " + transfer + " = $rt.callFactory("
                + jsString(factoryOpId.module().path()) + ", "
                + payload.classFactoryRef().id() + ", [" + names + "], "
                + spanArgs(op) + ");");
            // Phase 2: the extra-key scan.
            emitExtraFieldFailure(op, payload, layout);
            // Phase 3: the declaration-order field boundaries; a defaulted
            // field's value is read from the transferred instance (the
            // cross-unit default input), a provided field's from its own
            // operand slot.
            Map<String, String> fieldValues = new LinkedHashMap<>();
            for (KindPayload.FieldBoundary entry : payload.fieldBoundaries()) {
                SemanticOp boundary = requireBoundaryChild(op, entry.boundaryOpId());
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                String input;
                if (entry.kind() == BoundaryKind.CLASS_DEFAULT_FIELD) {
                    if (!boundaryPayload.input().equals(factoryResult)) {
                        throw rejected(op, "the CLASS_NEW " + op.opId()
                            + " field boundary " + entry.boundaryOpId()
                            + " carries input " + boundaryPayload.input()
                            + ": the pinned SHARED_FACTORY default input is the"
                            + " owner CLASS_FACTORY result " + factoryResult
                            + " (producer defect)");
                    }
                    input = "$c" + boundary.opId().id();
                    emit("let " + input + " = " + transfer + "["
                        + jsString(entry.field()) + "];");
                    emitBoundaryCheck(boundary, input, boundaryPayload.descriptor());
                } else {
                    input = slot(boundaryPayload.input());
                    emitBoundaryCheck(boundary, input, boundaryPayload.descriptor());
                }
                fieldValues.put(entry.field(), input);
            }
            emitInstance(op, payload, layout, providedNames, fieldValues);
        }

        /**
         * The extra-key rejection of one construction: the first
         * provided-source name outside the resolved layout raises the pinned
         * E8007 at the construction's own origin — after the defaults ran and
         * before any provided/default boundary, so completed default effects
         * remain and no partial instance publishes.
         */
        private void emitExtraFieldFailure(SemanticOp op,
                                           KindPayload.ClassNewPayload payload,
                                           ClassLayout layout) {
            for (KindPayload.ProvidedField field : payload.providedFields()) {
                if (fieldOf(layout, field.name()) != null) {
                    continue;
                }
                emit("$rt.fail(\"E8007\", "
                    + jsString("extra field '" + field.name() + "' in class '"
                        + payload.classId().text() + "'") + ", "
                    + spanArgs(op) + ");");
                return;
            }
        }

        /**
         * The constructed instance: provided values in provided-source
         * order, then every remaining declared field in declaration order
         * (its admitted default or the absent-optional sentinel), then the
         * public tag pair. The object publishes as one value at the op's
         * result; a failed boundary never reaches this build.
         */
        private void emitInstance(SemanticOp op, KindPayload.ClassNewPayload payload,
                                  ClassLayout layout, Set<String> providedNames,
                                  Map<String, String> fieldValues) {
            String target = slotOf(op);
            emit(target + " = {};");
            for (KindPayload.ProvidedField field : payload.providedFields()) {
                String value = fieldValues.get(field.name());
                if (value == null) {
                    throw rejected(op, "the CLASS_NEW " + op.opId()
                        + " carries no boundary for the provided field '"
                        + field.name() + "' (producer defect)");
                }
                emit("$rt.setProp(" + target + ", " + jsString(field.name())
                    + ", " + value + ");");
            }
            for (ClassLayout.FieldLayout field : layout.fields()) {
                if (providedNames.contains(field.name())) {
                    continue;
                }
                String value = fieldValues.get(field.name());
                if (value != null) {
                    emit("$rt.setProp(" + target + ", " + jsString(field.name())
                        + ", " + value + ");");
                } else if (!field.required()) {
                    emit("$rt.setProp(" + target + ", " + jsString(field.name())
                        + ", $rt.MISSING);");
                }
            }
            emit("$rt.setProp(" + target + ", \"$kind\", \"class\");");
            emit("$rt.setProp(" + target + ", \"$classname\", "
                + jsString(payload.classId().text()) + ");");
        }

        // ----- Field operations -----

        /**
         * {@code FIELD_READ}: the receiver boundary admits the instance (its
         * recorded untagged-class cell), the presence-aware read maps an
         * absent optional or a deleted required field to language null, and
         * the recorded optional-field boundary admits the read value at its
         * own origin — a missing required field still fails there.
         */
        private void emitFieldRead(SemanticOp op) {
            KindPayload.FieldReadPayload payload =
                (KindPayload.FieldReadPayload) op.payload();
            SemanticOp receiver = requireBoundaryChild(op,
                BoundaryKind.UNTYPED_CLASS_INPUT);
            SemanticOp fieldBoundary = requireBoundaryChild(op,
                BoundaryKind.OPTIONAL_FIELD_READ);
            String receiverDescriptor = descriptorOf(receiver).canonicalSpecText();
            String target = slotOf(op);
            emit(target + " = $rt.optRead($rt.checkType("
                + jsString(receiverDescriptor) + ", "
                + slot(payload.classValue()) + ", " + spanArgs(receiver) + ")["
                + jsString(payload.field()) + "]);");
            emitBoundaryCheck(fieldBoundary, target,
                descriptorOf(fieldBoundary));
        }

        /**
         * {@code FIELD_WRITE}: the receiver boundary admits the instance, the
         * recorded field-assignment boundary admits the stored value at its
         * own origin, then exactly one own-property commit runs. A failed
         * check commits nothing.
         */
        private void emitFieldWrite(SemanticOp op) {
            KindPayload.FieldWritePayload payload =
                (KindPayload.FieldWritePayload) op.payload();
            SemanticOp receiver = requireBoundaryChild(op,
                BoundaryKind.UNTYPED_CLASS_INPUT);
            SemanticOp fieldBoundary = requireBoundaryChild(op,
                BoundaryKind.CLASS_FIELD_ASSIGNMENT);
            String instance = "$w" + op.opId().id();
            emit("const " + instance + " = $rt.checkType("
                + jsString(descriptorOf(receiver).canonicalSpecText()) + ", "
                + slot(payload.classValue()) + ", " + spanArgs(receiver) + ");");
            emitBoundaryCheck(fieldBoundary, slot(payload.value()),
                descriptorOf(fieldBoundary));
            emit("$rt.setProp(" + instance + ", " + jsString(payload.field())
                + ", " + slot(payload.value()) + ");");
        }

        /**
         * {@code FIELD_DELETE}: the receiver boundary admits the instance,
         * then the field becomes absent (the absent-optional sentinel), so a
         * later read maps it to language null and {@code has} reports false.
         * Deleting an already-absent field is a no-op commit.
         */
        private void emitFieldDelete(SemanticOp op) {
            KindPayload.FieldDeletePayload payload =
                (KindPayload.FieldDeletePayload) op.payload();
            SemanticOp receiver = requireBoundaryChild(op,
                BoundaryKind.UNTYPED_CLASS_INPUT);
            String instance = "$x" + op.opId().id();
            emit("const " + instance + " = $rt.checkType("
                + jsString(descriptorOf(receiver).canonicalSpecText()) + ", "
                + slot(payload.classValue()) + ", " + spanArgs(receiver) + ");");
            emit("$rt.setProp(" + instance + ", " + jsString(payload.field())
                + ", $rt.MISSING);");
        }

        // ----- JSON helpers -----

        /**
         * {@code JSON_FROM_CLASS}: the canonical JS JSON walker over the
         * class's declaration-order descriptor array. Every parse, key,
         * provided-value, default or validation failure returns the DEAL null
         * with no partial instance; a provided-value failure runs no default.
         */
        private void emitJsonFromClass(SemanticOp op) {
            KindPayload.JsonFromClassPayload payload =
                (KindPayload.JsonFromClassPayload) op.payload();
            requireLocalJsonLayout(op, payload.layout());
            emit(slotOf(op) + " = $rt.jsonFromJson("
                + jsString(payload.layout().classId().text()) + ", "
                + classFieldsExpr(op, payload.layout().classId()) + ", null, "
                + slot(payload.jsonString()) + ", " + callSpanArgs(op) + ");");
        }

        /**
         * {@code JSON_TO_CLASS}: the canonical JS JSON walker over the
         * class's declaration-order descriptor array. Absent optionals are
         * omitted, present nulls are preserved, and the first declaration-
         * order unsupported/cycle/nonfinite value raises E8001 at the current
         * call expression's own origin (the generated helper forwards its
         * call-site span).
         */
        private void emitJsonToClass(SemanticOp op) {
            KindPayload.JsonToClassPayload payload =
                (KindPayload.JsonToClassPayload) op.payload();
            requireLocalJsonLayout(op, payload.layout());
            emit(slotOf(op) + " = $rt.jsonToJson("
                + jsString(payload.layout().classId().text()) + ", "
                + slot(payload.classValue()) + ", "
                + classFieldsExpr(op, payload.layout().classId()) + ", "
                + callSpanArgs(op) + ");");
        }

        /**
         * The generated JSON helper of one class runs over exactly its own
         * declaring unit's layout: the helper is generated by that
         * declaration, so the recorded payload layout must be its layout
         * (a same-identity sibling's layout is never substituted).
         */
        private void requireLocalJsonLayout(SemanticOp op, ClassLayout layout) {
            requirePayloadLayout(op, layout.classId(), layout);
            ClassLayout unitLayout = unitLayoutOf(currentModule,
                layout.classId());
            if (unitLayout == null || !unitLayout.equals(layout)) {
                throw rejected(op, "the " + op.kind() + " " + op.opId()
                    + " records a layout of " + layout.classId() + " that"
                    + " differs from its declaring unit's layout (producer"
                    + " defect)");
            }
        }

        /** The emitted {@code $fields} binding of one jsonable class. */
        private String classFieldsExpr(SemanticOp op, ClassId classId) {
            if (unitLayoutOf(currentModule, classId) == null) {
                throw rejected(op, "the @jsonable class " + classId
                    + " is not declared by the module '" + currentModule.path()
                    + " that generates its JSON helpers (producer defect)");
            }
            // The module emits one descriptor array per class it declares
            // and generates helpers for; the binding every local helper
            // reads is its own declaration's array.
            return "$f" + classId.name();
        }

        /**
         * The span arguments of one generated JSON helper operation: the
         * enclosing closure's own call-site span parameters when the helper
         * body declares them (the call origin the shared JSON_TO_ERROR
         * projection names), otherwise the operation's recorded origin.
         */
        private String callSpanArgs(SemanticOp op) {
            return currentCallSpan != null ? currentCallSpan : spanArgs(op);
        }

        // ----- Class-fact lookups -----

        /** One declared field of a layout, or null. */
        private static ClassLayout.FieldLayout fieldOf(ClassLayout layout,
                                                       String name) {
            for (ClassLayout.FieldLayout field : layout.fields()) {
                if (field.name().equals(name)) {
                    return field;
                }
            }
            return null;
        }

        /**
         * The exact layout a construction or JSON payload records,
         * validated as one of the class identity's project facts: a
         * declaration layout of some unit or a declaration-module
         * registration layout. A canonical class identity can cover
         * same-named declarations of sibling modules with different fields,
         * so the payload's own layout — the exact declaration the shared
         * lowering selected — supplies the field set; an identity-keyed
         * single layout would silently select a sibling. A disagreement with
         * every recorded layout is a producer defect, never a silent
         * selection.
         */
        private ClassLayout requirePayloadLayout(SemanticOp op, ClassId classId,
                                                 ClassLayout expected) {
            if (expected == null) {
                throw rejected(op, "the " + op.kind() + " " + op.opId()
                    + " of class " + classId + " records no layout (producer"
                    + " defect)");
            }
            List<ClassLayout> declared = declaredLayouts.get(classId);
            if (declared != null && declared.contains(expected)) {
                return expected;
            }
            ClassLayout registered = seedLayouts.get(classId);
            if (expected.equals(registered)) {
                return expected;
            }
            throw rejected(op, "the class " + classId + " resolves to a"
                + " layout that differs from every recorded declaration and"
                + " registration of the project closure (producer defect)");
        }

        /** One construction's recorded {@code CLASS_DEFAULT} child. */
        private SemanticOp requireDefaultChild(SemanticOp op, ClassId classId,
                                               OpId defaultOpId) {
            SemanticOp defaultOp = opsById.get(defaultOpId);
            if (defaultOp == null
                    || defaultOp.kind() != SemanticOpKind.CLASS_DEFAULT) {
                throw rejected(op, "the " + op.kind() + " " + op.opId()
                    + " names the CLASS_DEFAULT child " + defaultOpId
                    + ", which is not a recorded CLASS_DEFAULT (producer"
                    + " defect)");
            }
            KindPayload.ClassDefaultPayload payload =
                (KindPayload.ClassDefaultPayload) defaultOp.payload();
            if (!payload.classId().equals(classId)) {
                throw rejected(op, "the " + op.kind() + " " + op.opId()
                    + " names the CLASS_DEFAULT child " + defaultOpId
                    + " of " + payload.classId() + ", not of " + classId
                    + " (producer defect)");
            }
            if (!(defaultOp.result() instanceof ValueId)) {
                throw rejected(defaultOp, "the CLASS_DEFAULT " + defaultOpId
                    + " publishes no ValueId result (producer defect)");
            }
            return defaultOp;
        }

        /** One op's boundary child of the given kind, or a fail-closed defect. */
        private SemanticOp requireBoundaryChild(SemanticOp op, BoundaryKind kind) {
            SemanticOp boundary = boundaryChildOfKind(op, kind);
            if (boundary == null) {
                throw rejected(op, "the " + op.kind() + " " + op.opId()
                    + " carries no " + kind + " boundary child (producer"
                    + " defect)");
            }
            return boundary;
        }

        private SemanticOp requireBoundaryChild(SemanticOp op, OpId boundaryOpId) {
            SemanticOp boundary = opsById.get(boundaryOpId);
            if (boundary == null || boundary.kind() != SemanticOpKind.BOUNDARY) {
                throw rejected(op, "the " + op.kind() + " " + op.opId()
                    + " names the unknown boundary child " + boundaryOpId
                    + " (producer defect)");
            }
            return boundary;
        }

        /** The single boundary child of one op with the given kind, or null. */
        private SemanticOp boundaryChildOfKind(SemanticOp op, BoundaryKind kind) {
            SemanticOp found = null;
            for (SemanticOp candidate : opsById.values()) {
                if (candidate.kind() != SemanticOpKind.BOUNDARY
                        || !op.opId().equals(candidate.origin().parentOpId())) {
                    continue;
                }
                if (((KindPayload.BoundaryPayload) candidate.payload()).kind()
                        != kind) {
                    continue;
                }
                if (found == null || candidate.opId().id() < found.opId().id()) {
                    found = candidate;
                }
            }
            return found;
        }

        /** The emitted name of one {@code CLASS_DEFAULT} thunk. */
        private static String defaultThunkName(SemanticOp defaultOp) {
            return "$d" + defaultOp.opId().id();
        }

        /** The shared rejection of one identified operation. */
        private SharedEmissionFailure rejected(SemanticOp op, String message) {
            DiagnosticRange range = op == null || op.origin() == null
                    || op.origin().span() == null ? null
                : op.origin().span().range();
            return new SharedEmissionFailure(message, range);
        }

        /**
         * Whether one body (structures included) carries a generated
         * {@code JSON_TO_CLASS} operation: exactly those closures declare the
         * trailing call-site span parameters the shared toJson projection
         * renders its failure origin from.
         */
        private boolean needsCallSpan(LoweredModuleUnit unit, BlockId block) {
            return needsCallSpan(unit, block, new HashSet<>());
        }

        private boolean needsCallSpan(LoweredModuleUnit unit, BlockId block,
                                      Set<BlockId> seen) {
            if (block == null || !seen.add(block)) {
                return false;
            }
            List<OpId> members = memberOps(unit, block);
            if (members == null) {
                return false;
            }
            for (OpId opId : members) {
                SemanticOp op = opsById.get(opId);
                if (op == null) {
                    continue;
                }
                if (op.kind() == SemanticOpKind.JSON_TO_CLASS) {
                    return true;
                }
                switch (op.payload()) {
                    case KindPayload.BranchPayload payload -> {
                        if (needsCallSpan(unit, payload.selectedBlock(), seen)
                                || needsCallSpan(unit, payload.alternateBlock(),
                                    seen)) {
                            return true;
                        }
                    }
                    case KindPayload.LoopPayload payload -> {
                        if (needsCallSpan(unit, payload.initBlock(), seen)
                                || needsCallSpan(unit, payload.bodyBlock(), seen)
                                || needsCallSpan(unit, payload.updateBlock(), seen)) {
                            return true;
                        }
                    }
                    case KindPayload.ForEachPayload payload -> {
                        if (needsCallSpan(unit, payload.body(), seen)) {
                            return true;
                        }
                    }
                    case KindPayload.TryCatchPayload payload -> {
                        if (needsCallSpan(unit, payload.tryBlock(), seen)
                                || needsCallSpan(unit, payload.catchBlock(), seen)) {
                            return true;
                        }
                    }
                    default -> {
                    }
                }
            }
            return false;
        }

        // =====================================================================
        // Helpers
        // =====================================================================

        /**
         * The recorded (closed) import kind of one resolved module, or null
         * when no unit of the closure imports it. One kind per resolved
         * module exists by construction (the resolution classifies the
         * target once), so the read and callee arms select from these
         * recorded facts and never from a spelling.
         */
        private ModuleImportKind importKindOf(ModuleId module) {
            return importKinds.get(module);
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
         * Emits one group's source comment and records its generated
         * position through the one project-wide {@link SourceMapRecorder}
         * (the map_path_ownership and mapping_origin_split rules):
         * a local USER/SYNTHETIC group maps to its recorded local source
         * start and names this module's display file. A foreign-origin
         * child stays at its execution position and maps through its
         * recorded parent op when that parent is local — the caller's
         * initiating call/check group — and is omitted when no local
         * parent links it: a foreign origin never emits foreign coordinates
         * under the caller's filename, and no nearest statement is ever
         * selected. The recorder also supplies the one display spelling the
         * sidecar's {@code source} string shares, so comment and mapping
         * never disagree. With the internal map flag off nothing is
         * emitted here and the artifact keeps exactly its map-off bytes
         * (its runtime locations stay embedded).
         */
        private void beginMappedGroup(SemanticOp op) {
            if (sourceMapRecorder == null) {
                return;
            }
            SourceSpan span = localMappingSpan(op);
            if (span == null) {
                return;
            }
            int generatedLine = line;
            int generatedColumn = indent * 2 + 1;
            emit("// " + sourceCommentText(
                sourceMapRecorder.displayName(currentModule, span.file()))
                + ":" + span.startLine() + ":" + span.startColumn());
            sourceMapRecorder.record(currentModule, generatedLine,
                generatedColumn, span.startLine(), span.startColumn());
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
