package deal.codegen.lua;

import deal.codegen.EmitterSessionBase;
import deal.ffi.FfiGeneratedModule;
import deal.ffi.FfiFunctionDescriptor;
import deal.ffi.FfiImportedClassPlanReference;
import deal.ffi.FfiImportedFunctionReference;
import deal.semantic.DescriptorService;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.StdlibFunctionCatalog;
import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.AsyncTokenId;
import deal.semantic.ir.AsyncTokenOwner;
import deal.semantic.ir.BindingCellKind;
import deal.semantic.ir.BindingGeneration;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.ChainOperandCompletion;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.DefaultOwner;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.FailureArm;
import deal.semantic.ir.FailureArmId;
import deal.semantic.ir.FailureContractRegistry;
import deal.semantic.ir.ExternalAsyncLink;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.IterationMode;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleImportKind;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ParameterBoundaryMode;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;
import deal.types.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static deal.codegen.SemanticEmitterShared.descriptorText;
import static deal.codegen.SemanticEmitterShared.staticKind;

public final class LuaSemanticEmitter {

    /**
     * The chunk's hoisted shared scratch temps (goto can never jump into a
     * local's scope, so every check/return temp is a top-level
     * assignment). The chunk declares them for the module-init walk, and
     * every emitted body function re-declares the same names as its own
     * locals so a body never captures them as upvalues: LuaJIT binds a
     * closure with more than 60 upvalues, and the large bodies of the
     * corpus would otherwise cross that limit through the chunk's helper
     * functions, factories, and temps alone.
     */
    private static final String HOISTED_TEMPS =
        "__chk, __rvT, __rvcT, __okT, __resT, __terrT, "
            + "__cerrT, __wrappedT, __itT, __itnT, __elemT, __okB, __chkB, "
            + "__okD, __chkD, "
            + "__instT, __fT, __eT, __jokT, __jresT, __jarmT, __jcparT, __jfactT, "
            + "__hbT, "
            + "__dynC, __dynK, __dynM, __dynS, __dynSK, __dynA, __dynE, "
            + "__tA, __okH, __errH, __oA";

    /**
     * The chunk's one factory store: every function factory
     * ({@code __factories.F<id>}), detached class-default function
     * ({@code __factories.D<opId>}), and adapter thunk re-executor
     * ({@code __factories.T<opId>}) is a field of this single chunk-level
     * local table. LuaJIT bounds one function at 200 locals, so the
     * factories MUST NOT be pre-declared as one chunk local per factory:
     * a growing program would cross the limit (the skill example already
     * does). The table is declared before every assignment and every
     * reference, so a body emitted before a factory's assignment still
     * resolves the field at call time — never a nil global — and each
     * body captures the one table instead of one upvalue per factory.
     */
    private static final String FACTORY_TABLE = "__factories";

    /**
     * The number of state slots one emitted save/restore statement
     * carries. LuaJIT rejects a single multi-assignment with more than 200
     * variable names and an over-long expression, so a body with hundreds
     * of state slots is written in bounded statements.
     */
    private static final int STATE_CHUNK = 40;

    private LuaSemanticEmitter() {
    }

    /** Emits the complete LuaJIT module artifact for the validated unit. */
    public static String emitModule(LoweredModuleUnit unit, StructuredBodyTable table) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(table, "table must not be null");
        return new Session(unit, table, true, true).emit();
    }

    /**
     * Emits the combined trace artifact of a validated executable
     * project (the cross-module CLASSES surface): one chunk carries the
     * prelude once, every module's function factories, adapter thunks,
     * and detached class-default functions, then each module's init walk
     * in dependency order (each under its own {@code __module} tag). A
     * caller's {@code CLASS_NEW(SHARED_FACTORY)} arm resolves the
     * owner's factory op and default blocks through the closure, so the
     * owner-side events carry the owner's module path and the factory's
     * cross-unit parent. Single-unit sessions are the singleton closure
     * of the same machinery.
     *
     */
    public static String emitProject(ExecutableLoweredProject project,
                                     Map<ModuleId, StructuredBodyTable> tables,
                                     Map<ModuleId, ClassFactoryRegistry> registries) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(registries, "registries must not be null");
        return new Session(project, tables, registries, true, null).emit();
    }

    public static String emitProject(ExecutableLoweredProject project,
                                     Map<ModuleId, StructuredBodyTable> tables,
                                     Map<ModuleId, ClassFactoryRegistry> registries,
                                     HostDeclarationSurface declarationSurface) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(registries, "registries must not be null");
        Objects.requireNonNull(declarationSurface,
            "declarationSurface must not be null");
        return new Session(project, tables, registries, true,
            declarationSurface).emit();
    }

    public static String emitProductionProject(ExecutableLoweredProject project,
                                               Map<ModuleId, StructuredBodyTable> tables,
                                               Map<ModuleId, ClassFactoryRegistry> registries,
                                               HostDeclarationSurface declarationSurface) {
        return emitProductionProject(project, tables, registries,
            declarationSurface, null);
    }

    public static String emitProductionProject(ExecutableLoweredProject project,
                                               Map<ModuleId, StructuredBodyTable> tables,
                                               Map<ModuleId, ClassFactoryRegistry> registries,
                                               HostDeclarationSurface declarationSurface,
                                               FfiEmissionInput ffiEmissionInput) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(registries, "registries must not be null");
        Objects.requireNonNull(declarationSurface,
            "declarationSurface must not be null");
        return new Session(project, tables, registries, false,
            declarationSurface, ffiEmissionInput).emit();
    }

    public static String emitProductionModule(LoweredModuleUnit unit,
                                              StructuredBodyTable table,
                                              boolean entryModule) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(table, "table must not be null");
        return new Session(unit, table, false, entryModule).emit();
    }

    /**
     * The typed provider gap of the extern-C load emission's binding step
     * (design source {@code plan-evaluator-provider-binding-surface} P4 and
     * the provider-gap fail-closed contract): a provider the emitting
     * artifact does not publish in the wrapper convention before the
     * binding position, or one import alias the declaration facts bind to
     * two provider modules. The message carries the producing facts — the
     * extern-C import's raw specifier and statement origin, the resolved
     * declaration module, and the offending provider alias and module —
     * and {@link #consumingModulePath()} is the module whose
     * {@code MODULE_IMPORT} carries the extern-C import. The production arm
     * maps this signal to exactly one E6005 {@code SHARED_EMITTER_COVERAGE}
     * whose detail names the consuming module and those facts; the emitter
     * never renders a bare generic gap for it.
     */
    public static final class FfiProviderGap extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /** The module whose MODULE_IMPORT carries the extern-C import. */
        private final String consumingModulePath;

        FfiProviderGap(String consumingModulePath, String detail) {
            super(detail);
            this.consumingModulePath = Objects.requireNonNull(
                consumingModulePath, "consumingModulePath must not be null");
        }

        /** The consuming (emitting) module of the extern-C import. */
        public String consumingModulePath() {
            return consumingModulePath;
        }
    }

    // =========================================================================
    // Session
    // =========================================================================

    private static final class Session extends EmitterSessionBase {
        /** Production mode: no trace protocol, DEAL_ERROR_CODE terminal. */
        final boolean trace;
        /**
         * Whether the session carries a whole validated closure (the
         * project entries) or one module (the per-unit entries). The
         * read's emit-time ownership guard applies to a project session
         * only: a COMPILED read whose owner module is not among the
         * session's units is a producer defect there (the one lowering
         * resolves every COMPILED import to a closure module), while a
         * per-unit session resolves the owner's published surface of the
         * same program at execution and never fails closed for a foreign
         * owner.
         */
        final boolean projectSession;
        /**
         * The compile's host declaration surface (the declared-map source
         * of the {@code MODULE_IMPORT(HOST)} load): non-null in the
         * production project session, null in the trace/unit sessions.
         */
        final HostDeclarationSurface hostSurface;

        final Map<ClassId, ModuleId> hostClassModules = new LinkedHashMap<>();

        final Map<ClassId, ModuleId> ffiClassModules = new LinkedHashMap<>();

        final FfiEmissionInput ffiInput;
        /**
         * The declaration modules whose load this session's walk already
         * emitted into the chunk-global {@code __exportSurfaces} registry
         * (the {@code load_host} and {@code load_ffi} loads, in emission
         * order). The provider-binding predicate admits a declaration
         * provider exactly when its load was emitted before the binding
         * position: the binding captures the registry entry by value, so
         * a load emitted later creates a second surface object the
         * captured binding never observes.
         */
        final java.util.Set<ModuleId> emittedDeclarationLoads =
            new java.util.LinkedHashSet<>();
        /** The per-session ordinal of the emitted FFI import locals. */
        int ffiImportCounter;
        /** The selected entry module runs the ENTRY_INVOKE delegation. */
        final boolean entryModule;
        /** The function id of the factory currently being emitted (the
         *  per-function return-trampoline label suffix): every RETURN of
         *  the body jumps to the function's trampoline label — the last
         *  statement of the closure body — because a raw Lua return
         *  followed by a structure's closing label (a return inside a
         *  branch/loop/for-each block) or by the trampoline label itself
         *  (a top-level return before the tail) is invalid Lua. */
        FunctionId currentFunctionId = null;
        /** Structure ancestors are computed statically from the block tree
         *  per transfer (never a runtime-sensitive stack). */

        Session(LoweredModuleUnit unit, StructuredBodyTable table, boolean trace,
                boolean entryModule) {
            super(unit, table);
            this.trace = trace;
            this.projectSession = false;
            this.hostSurface = null;
            this.ffiInput = null;
            this.entryModule = entryModule;
            registerUnit(unit, table, new ClassFactoryRegistry(Map.of()));
            if (!entryModule) {
                // A non-entry module never runs its ENTRY_INVOKE delegation
                // (the retained emitter invokes main() only from the entry
                // module): skip the entry op and its delegated CALL.
                markSkippedEntryOps(unit);
            }
        }

        /**
         * The project-mode session (the cross-module factory surface):
         * every module's unit, table, and class-factory registry in one
         * combined artifact — the CLASS_NEW(SHARED_FACTORY) arm resolves
         * the owner's factory op and default blocks through the closure.
         */
        Session(ExecutableLoweredProject project, Map<ModuleId, StructuredBodyTable> tables,
                Map<ModuleId, ClassFactoryRegistry> registries, boolean trace) {
            this(project, tables, registries, trace, null);
        }

        Session(ExecutableLoweredProject project, Map<ModuleId, StructuredBodyTable> tables,
                Map<ModuleId, ClassFactoryRegistry> registries, boolean trace,
                HostDeclarationSurface hostSurface) {
            this(project, tables, registries, trace, hostSurface, null);
        }

        /**
         * The FFI-capable production project session: the combined closure
         * plus the compile's host declaration surface (the declared-map
         * source of the {@code MODULE_IMPORT(HOST)} load and the extern-C
         * declaration-kind source) and the compile's FFI emission input
         * (the extern-C generated-module metadata of the emitted
         * {@code load_ffi} prelude and the manifest-directory text
         * resolving a manifest-relative loader text). A null FFI input is
         * a session without an FFI emission input; a non-null input
         * requires the declaration surface.
         */
        Session(ExecutableLoweredProject project, Map<ModuleId, StructuredBodyTable> tables,
                Map<ModuleId, ClassFactoryRegistry> registries, boolean trace,
                HostDeclarationSurface hostSurface, FfiEmissionInput ffiInput) {
            super(project.modules().get(project.entryModule()),
                tables.get(project.entryModule()));
            if (ffiInput != null && hostSurface == null) {
                throw new IllegalArgumentException(
                    "an FFI emission input requires the compile's host"
                        + " declaration surface (the extern-C declaration"
                        + " kind and the provider coverage both resolve through"
                        + " it — a producer defect)");
            }
            if (unit == null || table == null) {
                throw new IllegalArgumentException(
                    "the entry module is not in the executable closure");
            }
            this.hostSurface = hostSurface;
            this.ffiInput = ffiInput;
            this.projectSession = true;
            this.trace = trace;
            this.entryModule = true;
            registerDeclarationClasses();
            for (Map.Entry<ModuleId, LoweredModuleUnit> entry
                    : project.modules().entrySet()) {
                registerUnit(entry.getValue(), tables.get(entry.getKey()),
                    registries.getOrDefault(entry.getKey(),
                        new ClassFactoryRegistry(Map.of())));
                if (!entry.getKey().equals(project.entryModule())) {
                    // A non-entry module never runs its ENTRY_INVOKE
                    // delegation: skip the entry op and its delegated
                    // CALL in the combined walk.
                    markSkippedEntryOps(entry.getValue());
                }
            }
        }

        private void registerDeclarationClasses() {
            if (hostSurface == null) {
                return;
            }
            for (ModuleId declarationModule : hostSurface.moduleIds()) {
                HostDeclarationSurface.DeclarationFacts facts =
                    hostSurface.require(declarationModule);
                boolean externC = facts.kind()
                    == HostDeclarationSurface.DeclarationKind.EXTERN_C;
                for (Map.Entry<String, Type> export : facts.exports().entrySet()) {
                    if (!(export.getValue() instanceof Type.Class)) {
                        continue;
                    }
                    RuntimeDescriptor descriptor =
                        DescriptorService.describe(export.getValue());
                    if (descriptor instanceof RuntimeDescriptor.Class classDescriptor) {
                        if (externC) {
                            ffiClassModules.put(classDescriptor.classId(),
                                declarationModule);
                        } else {
                            hostClassModules.put(classDescriptor.classId(),
                                declarationModule);
                        }
                    }
                }
            }
        }

        /** Whether one class identity is a declared host class of the compile. */
        private boolean isHostClass(ClassId classId) {
            return hostClassModules.containsKey(classId);
        }

        private String ffiPlanExpr(ClassId classId) {
            ModuleId declarationModule = ffiClassModules.get(classId);
            if (declarationModule == null) {
                throw new IllegalStateException("the class " + classId
                    + " is not a declared extern-C class of the compile's"
                    + " declaration surface (the class plan entry has exactly one"
                    + " source — a producer defect)");
            }
            return "__ffiClassPlan(" + luaString(declarationModule.path())
                + ", " + luaString(classId.name()) + ")";
        }

        /**
         * The emitted expression of one declared host class's loaded
         * {@code <C>_defaults} entry (K10): the module's published surface
         * entry — the module table {@code __rt.load_host} returned — read
         * back by the construction site. An absent surface or entry is a
         * fail-closed producer defect in the prelude helper, never a silent
         * default.
         */
        private String hostDefaultsExpr(ClassId classId) {
            ModuleId declarationModule = hostClassModules.get(classId);
            if (declarationModule == null) {
                throw new IllegalStateException("the class " + classId
                    + " is not a declared host class of the compile's declaration"
                    + " surface (the class defaults entry has exactly one source —"
                    + " a producer defect)");
            }
            return "__hostClassDefaults(" + luaString(declarationModule.path())
                + ", " + luaString(classId.name()) + ")";
        }

        // -- naming ---------------------------------------------------------------

        protected String slot(ValueId id) {
            return "S.v" + id.id();
        }

        protected String cell(BindingId id, long generation) {
            return "S.b" + id.id() + "g" + generation;
        }

        String label(BlockId id) {
            return "L" + id.id();
        }

        String loopExit(OpId loopOp) {
            return "X" + loopOp.id();
        }

        String loopCont(OpId loopOp) {
            return "Y" + loopOp.id();
        }

        String fnFactory(FunctionId id) {
            return FACTORY_TABLE + ".F" + id.id();
        }

        String returnLabel(FunctionId id) {
            return "__ret" + id.id();
        }

        // -- static kinds -----------------------------------------------------------

        static String bcheckExpr(RuntimeDescriptor descriptor, String value) {
            return "__bcheck(" + bcheckArgs(descriptor, value) + ")";
        }

        /**
         * The argument list of one prelude boundary check: the descriptor
         * text, the static kind, the value, and — when the descriptor
         * carries a function position — the descriptor's canonical spec
         * text as the trailing argument. The text of every non-function
         * check is emitted unchanged.
         */
        static String bcheckArgs(RuntimeDescriptor descriptor, String value) {
            String args = luaString(descriptorText(descriptor)) + ", "
                + luaString(staticKind(descriptor)) + ", " + value;
            if (containsFunction(descriptor)) {
                args += ", " + luaString(descriptor.canonicalSpecText());
            }
            return args;
        }

        /** Whether one descriptor carries a function position (recursively). */
        static boolean containsFunction(RuntimeDescriptor descriptor) {
            return switch (descriptor) {
                case RuntimeDescriptor.Func ignored -> true;
                case RuntimeDescriptor.Nullable nullable ->
                    containsFunction(nullable.inner());
                case RuntimeDescriptor.Array array -> containsFunction(array.element());
                default -> false;
            };
        }

        // -- lua literals -----------------------------------------------------------

        static String luaString(String text) {
            StringBuilder sb = new StringBuilder();
            sb.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\t' -> sb.append("\\t");
                    case '\r' -> sb.append("\\r");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\%03d", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
            return sb.toString();
        }

        static String luaNumber(double value) {
            if (Double.isNaN(value)) {
                return "(0/0)";
            }
            if (Double.isInfinite(value)) {
                return value > 0 ? "(1/0)" : "(-1/0)";
            }
            if (value == 0.0 && Double.doubleToRawLongBits(value) < 0) {
                return "-0x0.0p0";
            }
            return Double.toHexString(value);
        }

        // -- emission ----------------------------------------------------------------

        String emit() {
            out.append("-- deal.semantic-ir/1 shared LuaJIT artifact (ISSUE-0410 "
                + "decomposition tail)\n");
            // The shared run state (frames, sequence, allocation ids, the
            // async task registry and FIFO queue) is chunk-global: a
            // multi-module drive runs several artifacts in one process,
            // and the execution state is one — exactly the oracle's one
            // execution per run. __module stays chunk-local (every chunk
            // names its own module in its events).
            out.append("__frames = __frames or {}\n");
            // The innermost active call's origin (the JSON_TO_ERROR
            // projection renders the invoking call expression: the
            // generated C$toJson body's walk reads the origin of the call
            // that invoked it, never the generated body's synthetic
            // anchor). Chunk-global like the frame state.
            out.append("__callOrigins = __callOrigins or {}\n");
            out.append("__fnModules = __fnModules or {}\n");
            out.append("__seq = __seq or 0\n");
            out.append("local __module\n");
            // The nesting-safe module save/restore stack of the
            // cross-module CLASS_FACTORY transfer: one push per transfer,
            // one pop per restore — a transfer nested inside another
            // (an owner default that constructs another module's class)
            // restores its own saved module, never the enclosing one's.
            out.append("local __modStack = {}\n");

            out.append("local __svStack = {}\n");
            out.append("__bodyActive = __bodyActive or {}\n");
            out.append("__allocIds = __allocIds or {}\n");
            out.append("__allocNext = __allocNext or 1\n");
            // The mode flag of the prelude's console realization (M4): the
            // row invoker's single-effect write branches on it, so the
            // direct STDLIB_CALL arm and the cataloged callable share one
            // console realization in both modes. Chunk-local (like __module),
            // so a second chunk of one process never changes this chunk's
            // mode.
            out.append("local __traceMode = ").append(trace ? "true" : "false")
                .append("\n");
            out.append(preludeWithArms());
            out.append(PRELUDE_ASYNC);
            out.append(JSON_PRELUDE);
            if (!trace) {
                // Production: the event helpers are no-ops.
                out.append("__ev = function() end\n");
                out.append("__normalizeEvent = function() end\n");
            }
            // The per-module export-surface registry (K15 item 1: the
            // module's namespace value): one surface per module of the
            // closure, keyed by the module identity (the dotted module
            // path), created idempotently before the module walks and
            // registered in the identity-keyed namespace state
            // (__nsSurface) the shared table consumers read — the registry
            // is chunk-global and every surface keeps its published
            // entries, so a repeated deferred-main drive or a second chunk
            // of the same process never wipes a surface. It is declared in
            // both modes: every E7-lowered unit with exports carries
            // EXPORT_PUBLISH ops (SemanticLowerer's emitE7Terminals) whose
            // publication emission references the registry — a trace-mode
            // session over such a unit must emit valid Lua too
            // (production-only is the entry-surface return terminal, not
            // the declaration).
            out.append("__exportSurfaces = __exportSurfaces or {}\n");
            // The program-scoped cataloged-callable registry (M4): one
            // carrier per catalog row per module per program, so a read
            // before any surface population and the later whole-surface
            // population observe one callable object; chunk-global exactly
            // like the surface registry (a second chunk of the same
            // process resolves the same callables).
            out.append("__stdlibEntries = __stdlibEntries or {}\n");
            // The program-scoped intrinsic carriers (J2): one memoized
            // class-tagged carrier per intrinsic kind per program, so
            // every materialization of one intrinsic (a seed init, a load,
            // an adapter operand, a residual read) observes the identical
            // object and functions compare by reference identity;
            // chunk-global exactly like the surface and stdlib registries
            // (a second chunk of the same process resolves the same
            // carriers).
            out.append("__intrinsicCarriers = __intrinsicCarriers or {}\n");

            if (bindsHostRuntime()) {
                out.append("local __rt = require(\"deal.runtime\")\n");
                out.append("__rtNull = __rt.__NULL\n");
                out.append("__rtMissing = __rt.__MISSING\n");
                out.append(HOST_BOUNDARY_PRELUDE);
            } else if (bindsBytesRuntime()) {

                out.append("local __rt = require(\"deal.runtime\")\n");
                out.append("__rtNull = __rt.__NULL\n");
                out.append("__rtMissing = __rt.__MISSING\n");
            }
            if (bindsBytesRuntime()) {
                out.append(BYTES_PRELUDE);
            }
            for (LoweredModuleUnit moduleUnit : units.values()) {
                String moduleKey = luaString(moduleUnit.moduleId().path());
                out.append("__exportSurfaces[").append(moduleKey)
                    .append("] = __exportSurfaces[").append(moduleKey)
                    .append("] or {}\n");
                out.append("__nsSurface(__exportSurfaces[").append(moduleKey)
                    .append("])\n");
            }
            emitStdlibSurfacePopulation();
            // The host-driven callback dispatch table (CALLBACK_INVOKE):
            // a chunk-global in both modes — the per-unit dispatch entries
            // and the two host-seam helpers are the scenario host's
            // invocation surface.
            out.append("__callbacks = {}\n");
            out.append("__callbacks.__hostAtom = __hostAtom\n");
            out.append("__callbacks.__errtext = __errtext\n");
            // The async host seam defaults: the scenario host adapter
            // overrides both entries before any drive; an async host
            // operation without the override is a producer defect, never
            // a silent projection.
            out.append("__callbacks.__hostStartAsync = function(label, ...)\n");
            out.append("  error(\"an async host start has no deterministic host seam "
                + "(the scenario host drives it)\", 0)\n");
            out.append("end\n");
            out.append("__callbacks.__hostCompleteAsync = function(label)\n");
            out.append("  error(\"an async host completion has no deterministic host "
                + "seam (the scenario host drives it)\", 0)\n");
            out.append("end\n");
            // The host-driven async-entry dispatch table (async
            // EXTERNAL_ENTRY records): one entry per recorded async
            // export keyed by module#export; shared across chunks in a
            // multi-module drive (never wiped by a later chunk).
            out.append("__asyncEntries = __asyncEntries or {}\n");
            // The per-module MODULE_INIT lifecycle records (E8): chunk-
            // global like the rest of the run state (a multi-module drive
            // runs several artifacts in one process and each module
            // initializes once after its dependencies).
            out.append("__moduleStates = __moduleStates or {}\n");
            out.append("\n__module = ").append(luaString(unit.moduleId().path()))
                .append("\n");
            // One env table carries every slot and cell (LuaJIT's upvalue
            // limit never binds the function bodies).
            out.append("local S = {}\n");
            // Hoisted shared temps (goto can never jump into a local's
            // scope; every check/return temp is a top-level assignment).
            // The module-init walk reads and writes these chunk-level
            // temps; every function factory re-declares the same names as
            // its own locals ({@link #emitBodyFunctionPreamble}), so a
            // body never captures 30+ chunk temps as upvalues (LuaJIT's
            // 60-upvalue limit binds a large body otherwise).
            out.append("local ").append(HOISTED_TEMPS).append("\n");

            // The one factory store (LuaJIT bounds one function at 200
            // locals): the function factories, the detached class-default
            // functions, and the adapter thunk re-executors are fields of
            // this single local table, never one pre-declared chunk local
            // per factory. A body emitted before a later factory's
            // assignment still references the field, which resolves at
            // call time from the one table (a per-factory pre-declaration
            // was only needed to keep such a reference off the globals);
            // the same table keeps every body's capture set at one
            // upvalue instead of one per referenced factory.
            out.append("local ").append(FACTORY_TABLE).append(" = {}\n");
            for (LoweredModuleUnit moduleUnit : units.values()) {
                for (LoweredFunction function : moduleUnit.functions().values()) {
                    emitFunctionFactory(function);

                    out.append("__fnModules[")
                        .append(function.functionId().id()).append("] = ")
                        .append(luaString(moduleUnit.moduleId().path()))
                        .append("\n");
                }
            }

            // The REEVALUATE_THUNK re-executors: one detached thunk
            // function per FUNCTION_ADAPT op with a thunk source. The
            // thunk ops are members only of the detached thunk block (the
            // lowerer's single-membership rule), so the module walk never
            // executes them; each invocation re-executes them here.
            for (LoweredModuleUnit moduleUnit : units.values()) {
                for (SemanticOp op : moduleUnit.ops()) {
                    if (op.kind() != SemanticOpKind.FUNCTION_ADAPT) {
                        continue;
                    }
                    KindPayload.FunctionAdaptPayload payload =
                        (KindPayload.FunctionAdaptPayload) op.payload();
                    if (payload.source() instanceof AdaptSourceRef.Thunk thunk) {
                        emitThunkFunction(op, thunk.blockId());
                    }
                }
            }

            // The detached class-default functions (E5): one per
            // CLASS_DEFAULT op — the default block's ops (the default op
            // itself skipped) re-execute per invocation, returning the
            // block's final producing value (the op's result slot), so
            // every triggering construction gets a fresh default
            // (mutable defaults allocate freshly per attempt). Each name
            // is a field of the chunk-level factory store declared above:
            // a construction inside a function body (or a re-executed
            // thunk body) emits its `pcall(__factories.D<opId>)` call into
            // the factory emitted before this block, so the field
            // assignment keeps that reference resolvable at call time
            // instead of a nil global.
            for (LoweredModuleUnit moduleUnit : units.values()) {
                for (SemanticOp op : moduleUnit.ops()) {
                    if (op.kind() == SemanticOpKind.CLASS_DEFAULT) {
                        emitClassDefaultFunction(op);
                    }
                }
            }

            for (ClassLayout layout : classLayouts.values()) {
                emitJsonPlan(layout);
            }

            // The module-init blocks (the entry delegation included) in
            // dependency order inside one deferred-main wrapper so uncaught
            // DEAL failures publish the single R|failure terminal. Each
            // module's walk runs inside its MODULE_INIT envelope when the
            // unit carries the lowerer-produced op (E8: the op's START,
            // the payload init block nested under it, the SUCCESS terminal
            // publishing state:INITIALIZED, or the FAILURE terminal
            // recording FAILED(error) with no export publication); a
            // hand-built unit without the op runs the bare init block,
            // exactly the pre-envelope behavior. The walk is exposed as
            // the deferred-main entry (the scenario host drives it
            // explicitly under the defer flag — module initialization
            // before an async-entry invocation, or the entry module's walk
            // in a multi-module drive); the conformance artifact skips the
            // walk under the callback-only drive flag (the semantic
            // oracle's invokeCallback surface never runs the module-init
            // block).
            out.append("__dealMain = function()\n");
            out.append("  local __mainOk, __mainErr = pcall(function()\n");
            for (LoweredModuleUnit moduleUnit : units.values()) {
                out.append("  __module = ")
                    .append(luaString(moduleUnit.moduleId().path())).append("\n");
                SemanticOp moduleInitOp = moduleInitOpOf(moduleUnit);
                if (moduleInitOp == null) {
                    emitBlockOps(moduleUnit.moduleInit().initBlock());
                } else {
                    emitModuleInit(moduleInitOp);
                }
            }
            out.append("  end)\n");
            out.append("  if __mainOk then return true, nil end\n");
            out.append("  return false, __mainErr\n");
            out.append("end\n");
            out.append("if os.getenv(\"DEAL_DEFER_MAIN\") ~= \"1\" and "
                + "os.getenv(\"DEAL_CALLBACK_ONLY\") ~= \"1\" then\n");
            out.append("local __mainOk, __mainErr = __dealMain()\n");
            if (trace) {
                out.append("if __mainOk then\n");
                out.append("  io.stderr:write(\"R|success|null\\n\")\n");
                out.append("else\n");
                out.append("  io.stderr:write(\"R|failure|\"..__errtext(__mainErr)..\"\\n\")\n");
                out.append("end\n");
                out.append("io.stderr:flush()\n");
            } else {
                // Production terminal: a DEAL failure publishes the
                // retained DEAL_ERROR_CODE line on stdout and exits 1; a
                // non-DEAL failure rethrows; success returns the exports.
                out.append("if not __mainOk then\n");
                out.append("  if type(__mainErr) == \"table\" and __mainErr.__d then\n");
                out.append("    print(\"DEAL_ERROR_CODE: \"..__mainErr.code)\n");
                out.append("  else\n");
                out.append("    error(__mainErr, 0)\n");
                out.append("  end\n");
                out.append("  os.exit(1)\n");
                out.append("end\n");
            }
            out.append("end\n");

            // The host-driven callback dispatch entries (CALLBACK_INVOKE):
            // one per-unit entry per recorded invocation, defined after the
            // module-init walk in both modes (the block walk never runs the
            // unattached records themselves).
            for (LoweredModuleUnit moduleUnit : units.values()) {
                for (SemanticOp op : moduleUnit.ops()) {
                    if (op.kind() == SemanticOpKind.CALLBACK_INVOKE) {
                        emitCallbackInvoke(op);
                    }
                }
            }

            for (LoweredModuleUnit moduleUnit : units.values()) {
                for (SemanticOp op : moduleUnit.ops()) {
                    if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                            && ((KindPayload.ExternalEntryPayload) op.payload()).async()) {
                        emitAsyncEntry(op, moduleUnit);
                    }
                }
            }
            if (!trace) {
                // The retained-caller ABI: the artifact returns its own
                // module's export surface (the entry module's surface in
                // project mode, the single module's surface otherwise).
                out.append("return __exportSurfaces[")
                    .append(luaString(unit.moduleId().path())).append("]\n");
            }
            return out.toString();
        }

        private boolean bindsHostRuntime() {
            return projectSession && (hasHostImports() || hasHostCellBoundaries());
        }

        /**
         * Whether the session's op walk carries at least one bytes op (K6
         * items 1/2/4/7): the allocation intrinsic (a direct
         * {@code INTRINSIC_CALL} or a call whose resolved binding is the
         * seeded bytes intrinsic, statically or through an adapter), a
         * function-typed materialization of the seeded bytes intrinsic, a
         * bytes normalize mode, a bytes element boundary, or a
         * bytes-receiver length read. A chunk that carries one binds the
         * deployed runtime and emits the bytes surface prelude in both
         * modes.
         */
        private boolean bindsBytesRuntime() {
            for (LoweredModuleUnit moduleUnit : units.values()) {
                for (SemanticOp op : moduleUnit.ops()) {
                    if (op.kind() == SemanticOpKind.INTRINSIC_CALL
                            && op.payload() instanceof KindPayload.IntrinsicCallPayload
                                intrinsic
                            && intrinsic.kind() == IntrinsicKind.BYTES_NEW) {
                        return true;
                    }
                    if (op.kind() == SemanticOpKind.CALL
                            && op.payload() instanceof KindPayload.CallPayload call
                            && intrinsicBytesCall(call)) {
                        return true;
                    }
                    if (bytesIntrinsicMaterialization(op)) {
                        return true;
                    }
                    if (op.payload() instanceof KindPayload.IndexNormalizePayload normalize
                            && (normalize.mode()
                                    == deal.semantic.ir.IndexMode.BYTES_READ
                                || normalize.mode()
                                    == deal.semantic.ir.IndexMode.BYTES_WRITE)) {
                        return true;
                    }
                    if (op.payload() instanceof KindPayload.BoundaryPayload boundary
                            && (boundary.kind() == BoundaryKind.BYTE_ELEMENT_READ
                                || boundary.kind() == BoundaryKind.BYTE_ELEMENT_ASSIGNMENT)) {
                        return true;
                    }
                    if (op.kind() == SemanticOpKind.ARRAY_LENGTH
                            && !op.operandTypes().isEmpty()
                            && op.operandTypes().get(0)
                                instanceof RuntimeDescriptor.Bytes) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Whether one call's resolved binding is the seeded bytes
         * intrinsic: the static callee's binding, or the runtime callee
         * value's registered binding for the value-carried callee shapes.
         */
        private boolean intrinsicBytesCall(KindPayload.CallPayload payload) {
            FunctionExecutionBinding binding = switch (payload.callee()) {
                case KindPayload.CallCallee.Static staticCallee -> staticCallee.binding();
                case KindPayload.CallCallee.Indirect indirect ->
                    bindingOfIdentity(indirect.callee());
                case KindPayload.CallCallee.Dynamic ignored -> null;
            };
            return namesBytesIntrinsic(binding);
        }

        /**
         * Whether one binding is the seeded bytes intrinsic or an adapter
         * over it. A null binding names no intrinsic.
         */
        private boolean namesBytesIntrinsic(FunctionExecutionBinding binding) {
            if (binding instanceof FunctionExecutionBinding.IntrinsicFunction intrinsic) {
                return intrinsic.kind() == IntrinsicKind.BYTES_NEW;
            }
            if (binding instanceof FunctionExecutionBinding.AdapterBinding adapter) {
                return adapterIntrinsicKind(adapter) == IntrinsicKind.BYTES_NEW;
            }
            return false;
        }

        /**
         * Whether one op materializes the seeded bytes intrinsic as a
         * function value: a function-typed load of its seeded identity, or
         * a {@code FUNCTION_ADAPT} over it.
         */
        private boolean bytesIntrinsicMaterialization(SemanticOp op) {
            if (op.kind() == SemanticOpKind.BINDING_LOAD
                    && op.result() instanceof ValueId result) {
                return registeredIntrinsicKindOf(result) == IntrinsicKind.BYTES_NEW;
            }
            if (op.kind() == SemanticOpKind.FUNCTION_ADAPT
                    && op.payload() instanceof KindPayload.FunctionAdaptPayload adapt) {
                return adaptsBytesIntrinsic(adapt);
            }
            return false;
        }

        /**
         * Whether one adapter's recorded source names the seeded bytes
         * intrinsic: a VALUE operand through its registration, a
         * SHARED_CELL through the cell's seed init, a thunk never (its
         * source is reevaluated at execution and carries no static kind
         * fact).
         */
        private boolean adaptsBytesIntrinsic(KindPayload.FunctionAdaptPayload payload) {
            return switch (payload.source()) {
                case AdaptSourceRef.Value value ->
                    intrinsicKindOf(value.value()) == IntrinsicKind.BYTES_NEW;
                case AdaptSourceRef.SharedCell shared ->
                    bindingCarriesBytesIntrinsic(shared.binding());
                case AdaptSourceRef.Thunk ignored -> false;
            };
        }

        /** Whether one binding's seed init carries the bytes intrinsic identity. */
        private boolean bindingCarriesBytesIntrinsic(BindingId binding) {
            for (LoweredModuleUnit moduleUnit : units.values()) {
                for (SemanticOp op : moduleUnit.ops()) {
                    if (op.kind() == SemanticOpKind.BINDING_INIT
                            && op.payload() instanceof KindPayload.BindingInitPayload init
                            && init.binding().equals(binding)
                            && registeredIntrinsicKindOf(init.value())
                                == IntrinsicKind.BYTES_NEW) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Whether the session's op walk carries at least one host-boundary cell
         * (the family the host prelude's parameter/return checks realize).
         */
        private boolean hasHostCellBoundaries() {
            for (LoweredModuleUnit moduleUnit : units.values()) {
                for (SemanticOp op : moduleUnit.ops()) {
                    if (op.kind() != SemanticOpKind.BOUNDARY
                            || !(op.payload()
                                instanceof KindPayload.BoundaryPayload boundary)) {
                        continue;
                    }
                    BoundaryKind kind = boundary.kind();
                    FailurePolicyId policy = op.failurePolicy();
                    if (kind == BoundaryKind.DEAL_TO_HOST || kind == BoundaryKind.HOST_TO_DEAL
                            || policy == FailurePolicyId.HOST_PARAMETER
                            || policy == FailurePolicyId.HOST_SYNC_RETURN) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Whether the session's op walk carries at least one HOST-kind
         * import (host or extern-C).
         */
        private boolean hasHostImports() {
            for (LoweredModuleUnit moduleUnit : units.values()) {
                for (SemanticOp op : moduleUnit.ops()) {
                    if (op.kind() == SemanticOpKind.MODULE_IMPORT
                            && ((KindPayload.ModuleImportPayload) op.payload()).kind()
                                == ModuleImportKind.HOST) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * The cataloged callable surfaces of the session's STDLIB imports
         * (M4/K15 item 1): one surface entry per catalog row of every
         * imported STDLIB module, in catalog order, created through the one
         * memoized accessor ({@code __stdlibEntry}) — the whole-surface
         * population and the read resolve the identical callable object per
         * row, and the surface is written only by this session-scoped
         * catalog memoization (never re-derived per read).
         */
        private void emitStdlibSurfacePopulation() {
            for (Map.Entry<ModuleId, ModuleImportKind> imported : importKinds.entrySet()) {
                if (imported.getValue() != ModuleImportKind.STDLIB) {
                    continue;
                }
                String modulePath = imported.getKey().path();
                List<StdlibFunctionCatalog.Entry> rows = stdlibRowsOf(modulePath);
                if (rows.isEmpty()) {
                    throw new IllegalStateException("the STDLIB import of module '"
                        + modulePath + "' resolves no closed catalog row (the catalog"
                        + " is the resolution authority — a producer defect)");
                }
                String key = luaString(modulePath);
                out.append("__exportSurfaces[").append(key).append("] = ")
                    .append("__exportSurfaces[").append(key).append("] or {}\n");
                out.append("__nsSurface(__exportSurfaces[").append(key)
                    .append("])\n");
                for (StdlibFunctionCatalog.Entry row : rows) {
                    out.append("__exportSurfaces[").append(key).append("][")
                        .append(luaString(row.exportName())).append("] = ")
                        .append("__stdlibEntry(")
                        .append(stdlibRowArgs(row)).append(")\n");
                    out.append("__orderAdd(__exportSurfaces[").append(key)
                        .append("], ").append(luaString(row.exportName()))
                        .append(")\n");
                }
            }
        }

        /**
         * The closed catalog rows of one imported stdlib module, in catalog
         * order (the pinned declaration order of the one catalog authority);
         * an unimported or unknown module has none.
         */
        private static List<StdlibFunctionCatalog.Entry> stdlibRowsOf(String modulePath) {
            List<StdlibFunctionCatalog.Entry> rows = new ArrayList<>();
            for (StdlibFunctionCatalog.Entry row : StdlibFunctionCatalog.entries()) {
                if (row.modulePath().equals(modulePath)) {
                    rows.add(row);
                }
            }
            return rows;
        }

        /**
         * The one catalog row of one {@code (module path, export name)} pair,
         * or a producer defect: the lowering's STDLIB guard already rejects an
         * out-of-catalog member, so an emitted read always names a row.
         */
        private static StdlibFunctionCatalog.Entry stdlibRowOf(String modulePath,
                                                              String exportName) {
            return StdlibFunctionCatalog.lookup(modulePath, exportName)
                .orElseThrow(() -> new IllegalStateException("the STDLIB export read '"
                    + modulePath + "#" + exportName + "' resolves no closed catalog row"
                    + " (the catalog is the STDLIB kind's resolution authority — a"
                    + " producer defect)"));
        }

        /**
         * The interning argument list of one cataloged callable
         * ({@code __stdlibEntry(module, name, rowTag, signature, spec)}): the
         * row's declared descriptor text ({@code __sig}) and its canonical
         * spec text ({@code __csig}) — the row's declared signature, never
         * the reading site's.
         */
        private static String stdlibRowArgs(StdlibFunctionCatalog.Entry row) {
            return luaString(row.modulePath()) + ", " + luaString(row.exportName())
                + ", " + luaString(row.function().name()) + ", "
                + luaString(descriptorText(row.declaredDescriptor())) + ", "
                + luaString(row.declaredDescriptor().canonicalSpecText());
        }

        /**
         * Emits one detached class-default function: the default block's
         * ops in order (the CLASS_DEFAULT op itself skipped — its own
         * events are the triggering CLASS_NEW/CLASS_FACTORY arm's) and
         * the final producing value returned. The name is a field of the
         * chunk-level factory store (see the preamble): the assignment
         * form keeps the function reachable from every factory/
         * construction body emitted before this statement.
         */
        private void emitClassDefaultFunction(SemanticOp defaultOp) {
            KindPayload.ClassDefaultPayload payload =
                (KindPayload.ClassDefaultPayload) defaultOp.payload();
            StructuredBodyTable ownerTable = blockTableOf.get(payload.defaultBlock());
            List<OpId> ops = ownerTable == null
                ? null : ownerTable.blockOps().get(payload.defaultBlock());
            if (ops == null) {
                throw new IllegalStateException("the class-default block "
                    + payload.defaultBlock() + " has no membership row (producer defect)");
            }
            if (!(defaultOp.result() instanceof ValueId resultId)) {
                throw new IllegalStateException("CLASS_DEFAULT " + defaultOp.opId()
                    + " publishes no ValueId result (producer defect)");
            }
            out.append(defaultFn(defaultOp.opId())).append(" = function()\n");
            for (OpId opId : ops) {
                if (opId.equals(defaultOp.opId()) || ownedChildren.contains(opId)) {
                    continue;
                }
                emitOp(opsById.get(opId));
            }
            out.append("  return ").append(slot(resultId)).append("\n");
            out.append("end\n");
        }

        /** One detached class-default function field of a CLASS_DEFAULT op. */
        private String defaultFn(OpId defaultOp) {
            return FACTORY_TABLE + ".D" + defaultOp.id();
        }

        /**
         * Emits one detached thunk re-executor for a FUNCTION_ADAPT op
         * with a REEVALUATE_THUNK source: the thunk block's ops run in
         * order (payload-owned children included through their owner
         * arms) and the function returns the final producing op's result
         * — the source value the invocation consumes.
         */
        private void emitThunkFunction(SemanticOp adaptOp, BlockId block) {
            StructuredBodyTable ownerTable = blockTableOf.get(block);
            List<OpId> ops = ownerTable == null ? null : ownerTable.blockOps().get(block);
            if (ops == null) {
                throw new IllegalStateException("the adapter thunk block " + block
                    + " has no membership row (producer defect)");
            }
            out.append(thunkFn(adaptOp.opId())).append(" = function()\n");
            emitBlockOps(block);
            ValueId produced = null;
            for (int i = ops.size() - 1; i >= 0; i--) {
                OpId opId = ops.get(i);
                if (ownedChildren.contains(opId)) {
                    continue;
                }
                SemanticOp op = opsById.get(opId);
                if (op != null && op.result() instanceof ValueId valueId) {
                    produced = valueId;
                }
                break;
            }
            if (produced == null) {
                throw new IllegalStateException("the adapter thunk block " + block
                    + " has no final producing value (producer defect)");
            }
            out.append("  return ").append(slot(produced)).append("\n");
            out.append("end\n");
        }

        /** One thunk re-executor field of an adapter op. */
        private String thunkFn(OpId adaptOp) {
            return FACTORY_TABLE + ".T" + adaptOp.id();
        }

        /**
         * The fixed preamble of every emitted body function: the argument
         * pack and the function's own copy of the hoisted scratch temps.
         * The temps stay function-local so a body's closure captures the
         * chunk's helpers, factories, and env table only — LuaJIT bounds a
         * closure at 60 upvalues, and the chunk-level temps alone would
         * consume a third of that budget on a large body.
         */
        private void emitBodyFunctionPreamble() {
            out.append("    local __args = {...}\n");
            out.append("    local ").append(HOISTED_TEMPS).append("\n");
        }

        /** Emits one function factory: a closure over the passed capture cells. */
        private void emitFunctionFactory(LoweredFunction function) {
            FunctionId functionId = function.functionId();
            List<BindingGeneration> captures = function.captures();
            StringBuilder params = new StringBuilder();
            for (BindingGeneration capture : captures) {
                if (params.length() > 0) {
                    params.append(", ");
                }
                params.append(captureCell(capture.binding()));
            }
            out.append(fnFactory(functionId)).append(" = function(")
                .append(params).append(")\n");
            out.append("  return function(...)\n");
            emitBodyFunctionPreamble();
            int argIndex = 1;
            List<OpId> bodyOps = tableOfFunction(function).blockOps().get(function.body());
            currentFunctionId = functionId;
            currentFunction = function;
            int paramCount = function.descriptor().paramTypes().size();
            for (int i = 0; i < paramCount && i < bodyOps.size(); i++) {
                SemanticOp op = opsById.get(bodyOps.get(i));
                if (op.kind() != SemanticOpKind.BINDING_ALLOC) {
                    break;
                }
                KindPayload.BindingAllocPayload payload =
                    (KindPayload.BindingAllocPayload) op.payload();
                BindingCellKind kind = cellKindOf(payload.binding(), payload.generation());
                out.append("    ").append(cell(payload.binding(), payload.generation())).append(" = ")
                    .append(kind == BindingCellKind.SHARED_CELL
                        ? "{__args[" + argIndex + "]}" : "__args[" + argIndex + "]")
                    .append("\n");
                argIndex++;
            }
            for (int i = paramCount; i < bodyOps.size(); i++) {
                if (ownedChildren.contains(bodyOps.get(i))) {
                    continue;
                }
                emitOp(opsById.get(bodyOps.get(i)));
            }
            // The function's return trampoline: every RETURN of the
            // body jumps here (a raw Lua return inside a structure block
            // would be followed by the structure's closing label, and a
            // trailing label after a raw top-level return is equally
            // invalid — one uniform trampoline keeps every shape valid).
            out.append("::").append(returnLabel(functionId)).append("::\n");
            out.append("  return __rvcT\n");
            out.append("  end\n");
            out.append("end\n");
            currentFunctionId = null;
            currentFunction = null;
        }

        /** The membership table of the unit owning one lowered function. */
        private StructuredBodyTable tableOfFunction(LoweredFunction function) {
            for (LoweredModuleUnit moduleUnit : units.values()) {
                if (moduleUnit.functions().containsKey(function.functionId())) {
                    return tables.get(moduleUnit.moduleId());
                }
            }
            return table;
        }

        /**
         * The {@link LoweredFunction} record of one function id across the
         * session's closure: a non-entry module's function body resolves
         * against its own module's function registry, never the entry
         * unit's (a cross-module invocation's capture list is the callee's
         * own — the factory parameters must carry exactly those cells).
         */
        private LoweredFunction functionOf(FunctionId functionId) {
            for (LoweredModuleUnit moduleUnit : units.values()) {
                LoweredFunction function = moduleUnit.functions().get(functionId);
                if (function != null) {
                    return function;
                }
            }
            return unit.functions().get(functionId);
        }

        /** Emits the ops of one block inline. */
        private void emitBlockOps(BlockId block) {
            StructuredBodyTable ownerTable = blockTableOf.get(block);
            if (ownerTable == null) {
                ownerTable = table;
            }
            for (OpId opId : ownerTable.blockOps().get(block)) {
                if (ownedChildren.contains(opId)) {
                    continue;
                }
                if (skippedOps.contains(opId)) {
                    continue;
                }
                emitOp(opsById.get(opId));
            }
        }

        /** Emits a block's ops under a label section. */
        private void emitLabeledBlock(BlockId block) {
            out.append("::").append(label(block)).append("::\n");
            emitBlockOps(block);
        }

        // -- per-op emission ---------------------------------------------------------

        private void emitOp(SemanticOp op) {
            switch (op.kind()) {
                case CONST -> emitConst(op);
                case UNARY -> emitUnary(op);
                case BINARY -> emitBinary(op);
                case STRING_CONCAT -> emitConcat(op);
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
                case FIELD_READ -> emitFieldRead(op);
                case FIELD_WRITE -> emitFieldWrite(op);
                case FIELD_DELETE -> emitFieldDelete(op);
                case BOUNDARY -> emitFreeBoundary(op);
                case BINDING_ALLOC -> emitBindingAlloc(op);
                case BINDING_INIT -> emitBindingInit(op);
                case BINDING_LOAD -> emitBindingLoad(op);
                case BINDING_STORE -> emitBindingStore(op);
                case CLOSURE_NEW -> emitClosureNew(op);
                case RECURSIVE_GROUP_INIT -> emitRecursiveGroupInit(op);
                case FUNCTION_ADAPT -> emitFunctionAdapt(op);
                case CALLBACK_INVOKE -> emitCallbackInvoke(op);
                case ASYNC_START -> emitAsyncStart(op);
                case AWAIT -> emitAwait(op);
                case ASSIGN -> emitAssign(op);
                case DELETE -> emitDelete(op);
                case CALL -> emitCall(op);
                case INTRINSIC_CALL -> emitIntrinsic(op);
                case STDLIB_CALL -> emitStdlib(op);
                case BRANCH -> emitBranch(op);
                case LOOP -> emitLoop(op);
                case FOR_EACH -> emitForEach(op);
                case TRY_CATCH -> emitTryCatch(op);
                case THROW -> emitThrow(op);
                case RETURN -> emitReturn(op);
                case BREAK -> emitBreak(op);
                case CONTINUE -> emitContinue(op);
                case DISCARD -> emitDiscard(op);
                case MODULE_IMPORT -> emitModuleImport(op);
                case EXPORT_READ -> emitExportRead(op);
                case EXPORT_PUBLISH -> emitExportPublish(op);
                case EXTERNAL_ENTRY -> emitExternalEntryRecord(op);
                case ENTRY_INVOKE -> emitEntryInvoke(op);
                case MODULE_INIT -> emitModuleInit(op);
                case JSON_FROM_CLASS -> emitJsonFromClass(op);
                case JSON_TO_CLASS -> emitJsonToClass(op);
                case CLASS_NEW -> emitClassNew(op);
                case CLASS_DEFAULT -> throw new IllegalStateException("a CLASS_DEFAULT "
                    + "executes only under its triggering CLASS_NEW/CLASS_FACTORY "
                    + "(the detached default block's ops are the class-default "
                    + "function's; the block walk never runs the op itself)");
                case CLASS_FACTORY -> throw new IllegalStateException("a CLASS_FACTORY "
                    + "is a detached owner-module entry executed only under the "
                    + "triggering caller's CLASS_NEW (cross-unit parent) — the "
                    + "block walk never runs it)");
                default -> throw new IllegalStateException("op kind " + op.kind()
                    + " has no shared-LuaJIT emission in this decomposition-tail "
                    + "domain");
            }
        }

        private String opKey(OpId id) {
            return id.module().path() + "#" + id.id();
        }

        private String parentKey(OpId parent) {
            return parent == null ? "-" : opKey(parent);
        }

        private void emitStart(SemanticOp op) {
            out.append("__ev(").append(luaString(opKey(op.opId()))).append(", \"START\", ")
                .append(luaString(op.kind().name())).append(", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId()))).append(", {");
            for (int i = 0; i < op.operands().size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append("__atom(")
                    .append(luaString(staticKind(op.operandTypes().get(i))))
                    .append(", ").append(slot(op.operands().get(i))).append(")");
            }
            out.append("}, nil, nil)\n");
        }

        /** A boundary START event with its input atom. */
        /** START with the closed slot atom for the second operand (slot ops). */
        private void emitSlotOperandStart(SemanticOp op) {
            out.append("__ev(").append(luaString(opKey(op.opId()))).append(", \"START\", ")
                .append(luaString(op.kind().name())).append(", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId()))).append(", {");
            out.append("__atom(").append(luaString(staticKind(op.operandTypes().get(0))))
                .append(", ").append(slot(op.operands().get(0))).append(")");
            out.append(", __slotAtom(").append(slot(op.operands().get(1))).append(")");
            for (int i = 2; i < op.operands().size(); i++) {
                out.append(", __atom(")
                    .append(luaString(staticKind(op.operandTypes().get(i))))
                    .append(", ").append(slot(op.operands().get(i))).append(")");
            }
            out.append("}, nil, nil)\n");
        }

        private void emitBoundaryStart(SemanticOp boundary, String inputExpr,
                                       RuntimeDescriptor inputDescriptor) {
            out.append("__ev(").append(luaString(opKey(boundary.opId())))
                .append(", \"START\", \"BOUNDARY\", ")
                .append(luaString(boundary.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(boundary.origin().parentOpId())))
                .append(", {__atom(").append(luaString(staticKind(inputDescriptor)))
                .append(", ").append(inputExpr).append(")}, nil, nil)\n");
        }

        private void emitBoundarySuccess(SemanticOp boundary, String valueExpr,
                                         RuntimeDescriptor descriptor) {
            out.append("__ev(").append(luaString(opKey(boundary.opId())))
                .append(", \"SUCCESS\", \"BOUNDARY\", ")
                .append(luaString(boundary.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(boundary.origin().parentOpId())))
                .append(", {}, __atom(").append(luaString(staticKind(descriptor)))
                .append(", ").append(valueExpr).append("), nil)\n");
        }

        /** SUCCESS with the produced result atom. */
        private void emitResultSuccess(SemanticOp op, String valueExpr,
                                       RuntimeDescriptor resultDescriptor) {
            out.append("__ev(").append(luaString(opKey(op.opId())))
                .append(", \"SUCCESS\", ").append(luaString(op.kind().name()))
                .append(", ").append(luaString(op.contract().canonicalDigest()))
                .append(", ").append(luaString(parentKey(op.origin().parentOpId())))
                .append(", {}, __atom(").append(luaString(staticKind(resultDescriptor)))
                .append(", ").append(valueExpr).append("), nil)\n");
        }

        private void emitPlainSuccess(SemanticOp op) {
            out.append("__ev(").append(luaString(opKey(op.opId())))
                .append(", \"SUCCESS\", ").append(luaString(op.kind().name()))
                .append(", ").append(luaString(op.contract().canonicalDigest()))
                .append(", ").append(luaString(parentKey(op.origin().parentOpId())))
                .append(", {}, nil, nil)\n");
        }

        /** The raw SUCCESS emission of a structure closed by a transfer. */
        private void emitClosedSuccess(SemanticOp op) {
            out.append("__ev(").append(luaString(opKey(op.opId())))
                .append(", \"SUCCESS\", ").append(luaString(op.kind().name()))
                .append(", ").append(luaString(op.contract().canonicalDigest()))
                .append(", ").append(luaString(parentKey(op.origin().parentOpId())))
                .append(", {}, nil, nil)\n");
        }

        /**
         * A result SUCCESS event atomized by the value's own kind (the
         * deferred composite read: the pass-through admits a value whose
         * runtime variant need not match the read's internal result kind,
         * and the oracle's publish atomizes the value, never the kind).
         */
        private void emitResultSuccessAtom(SemanticOp op, String valueExpr) {
            RuntimeDescriptor resultDescriptor = (RuntimeDescriptor) op.resultType();
            out.append("__ev(").append(luaString(opKey(op.opId())))
                .append(", \"SUCCESS\", ").append(luaString(op.kind().name()))
                .append(", ").append(luaString(op.contract().canonicalDigest()))
                .append(", ").append(luaString(parentKey(op.origin().parentOpId())))
                .append(", {}, __rawArgAtom(")
                .append(luaString(staticKind(resultDescriptor))).append(", ")
                .append(valueExpr).append("), nil)\n");
        }

        /**
         * Whether a transfer's target label is defined in the emitted Lua
         * function at the given op's emission level. The walk from the
         * emitting op's block outward reaches the target loop before any
         * TRY_CATCH ancestor exactly when the target's labels are emitted in
         * the same function: the closer targets are at the current level and
         * the farther ones lie outside the nearest
         * {@code pcall(function() … end)} boundary (a different function). A
         * target the walk never reaches is not an enclosing structure — it
         * lives inside a protected body of this level — and is equally
         * invisible. A RETURN's trampoline label lives at the enclosing
         * function's top level, outside every protected body, so it is
         * visible exactly when the level carries no enclosing boundary (the
         * {@code null} target).
         */
        private boolean transferTargetVisible(SemanticOp emittingOp,
                                              SemanticOp targetLoop) {
            if (targetLoop == null) {
                return tryDepth == 0;
            }
            for (SemanticOp ancestor
                    : structureAncestors(opBlock.get(emittingOp.opId()))) {
                if (ancestor.kind() == SemanticOpKind.TRY_CATCH) {
                    return false;
                }
                if (ancestor.opId().equals(targetLoop.opId())) {
                    return true;
                }
            }
            return false;
        }

        /** Emits the transfer closures of the ancestors down to (and including)
         *  the target loop; stops at an enclosing TRY_CATCH when requested
         *  (its dispatch closes it). */
        private void emitTransferClosures(SemanticOp transferOp, SemanticOp targetLoop,
                                          boolean stopAtTry) {
            BlockId block = opBlock.get(transferOp.opId());
            for (SemanticOp ancestor : structureAncestors(block)) {
                if (stopAtTry && ancestor.kind() == SemanticOpKind.TRY_CATCH) {
                    return;
                }
                // The target loop itself is NOT closed here: a BREAK/CONTINUE
                // exits or re-enters it and its normal success line runs at
                // the loop exit (the oracle's loop wrapper emits it once).
                if (targetLoop != null && ancestor.opId().equals(targetLoop.opId())) {
                    return;
                }
                emitClosedSuccess(ancestor);
            }
        }

        private void emitFailureEvent(OpId id, String kindName, SemanticOp op,
                                      String errExpr) {
            out.append("__ev(").append(luaString(opKey(id))).append(", \"FAILURE\", ")
                .append(luaString(kindName)).append(", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId())))
                .append(", {}, nil, ").append(errExpr).append(")\n");
        }

        private String originOf(SemanticOp op) {
            SourceSpan span = op.origin().span();
            if (span == null) {
                return "-";
            }
            return op.origin().sourceId() + ":" + span.startLine() + ":"
                + span.startColumn();
        }

        // -- ops ----------------------------------------------------------------

        private void emitConst(SemanticOp op) {
            KindPayload.ConstPayload payload = (KindPayload.ConstPayload) op.payload();
            String literal = switch (payload.value()) {
                case ScalarValue.Null ignored -> "nil";
                case ScalarValue.Boolean bool -> bool.value() ? "true" : "false";
                case ScalarValue.Int intValue -> String.valueOf(intValue.value());
                case ScalarValue.Number number -> luaNumber(number.value());
                case ScalarValue.String string -> luaString(string.value());
            };
            emitStart(op);
            out.append(slot((ValueId) op.result())).append(" = ").append(literal)
                .append("\n");
            emitResultSuccess(op, slot((ValueId) op.result()), op.resultType()
                instanceof RuntimeDescriptor descriptor ? descriptor : null);
        }

        private void emitUnary(SemanticOp op) {
            KindPayload.UnaryPayload payload = (KindPayload.UnaryPayload) op.payload();
            ValueId input = op.operands().get(0);
            emitStart(op);
            if (payload.selector() == deal.semantic.ir.UnarySelector.BOOL_NOT) {
                out.append(slot((ValueId) op.result())).append(" = not ")
                    .append(slot(input)).append("\n");
            } else {
                out.append(slot((ValueId) op.result())).append(" = __unary(")
                    .append(luaString(payload.selector().name())).append(", ")
                    .append(slot(input)).append(", ")
                    .append(luaString(opKey(op.opId()))).append(", ")
                    .append(luaString(op.contract().canonicalDigest())).append(", ")
                    .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(luaString(originOf(op))).append(")\n");
            }
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
        }

        private void emitBinary(SemanticOp op) {
            KindPayload.BinaryPayload payload = (KindPayload.BinaryPayload) op.payload();
            emitStart(op);
            boolean arithmetic = payload.selector().name().startsWith("INT32_")
                || payload.selector().name().startsWith("NUMBER_");
            boolean comparison = payload.selector().name().endsWith("_EQ")
                || payload.selector().name().endsWith("_NE")
                || payload.selector().name().endsWith("_LT")
                || payload.selector().name().endsWith("_LE")
                || payload.selector().name().endsWith("_GT")
                || payload.selector().name().endsWith("_GE");
            if (arithmetic && !comparison) {
                out.append(slot((ValueId) op.result())).append(" = __arith(")
                    .append(luaString(payload.selector().name())).append(", ")
                    .append(slot(op.operands().get(0))).append(", ")
                    .append(slot(op.operands().get(1))).append(", ")
                    .append(luaString(opKey(op.opId()))).append(", ")
                    .append(luaString(op.contract().canonicalDigest())).append(", ")
                    .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(luaString(originOf(op))).append(")\n");
                emitResultSuccess(op, slot((ValueId) op.result()),
                    (RuntimeDescriptor) op.resultType());
                return;
            }
            out.append(slot((ValueId) op.result())).append(" = __cmp(")
                .append(luaString(payload.selector().name())).append(", ")
                .append(luaString(staticKind(op.operandTypes().get(0)))).append(", ")
                .append(slot(op.operands().get(0))).append(", ")
                .append(luaString(staticKind(op.operandTypes().get(1)))).append(", ")
                .append(slot(op.operands().get(1))).append(", ")
                .append(payload.side() == null ? "nil"
                    : luaString(payload.side().name())).append(")\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
        }

        private void emitConcat(SemanticOp op) {
            KindPayload.StringConcatPayload payload =
                (KindPayload.StringConcatPayload) op.payload();
            emitStart(op);
            StringBuilder expr = new StringBuilder();
            for (ValueId fragment : payload.fragments()) {
                if (expr.length() > 0) {
                    expr.append("..");
                }
                expr.append("(").append(slot(fragment)).append(" or \"\")");
            }
            out.append(slot((ValueId) op.result())).append(" = ")
                .append(expr.length() == 0 ? "\"\"" : expr).append("\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
        }

        private void emitArrayNew(SemanticOp op) {
            KindPayload.ArrayNewPayload payload = (KindPayload.ArrayNewPayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            out.append(target).append(" = {__a = true, __n = ")
                .append(payload.values().size()).append("}\n");
            for (int i = 0; i < payload.values().size(); i++) {
                OpId boundaryId = payload.elementBoundaryOpIds().get(i);
                SemanticOp boundary = opsById.get(boundaryId);
                ValueId input = payload.values().get(i);
                emitBoundaryStart(boundary, slot(input), payload.elementDescriptor());
                out.append("__chk = ")
                    .append(bcheckExpr(payload.elementDescriptor(), slot(input)))
                    .append("\n");
                out.append(target).append("[").append(i + 1)
                    .append("] = __chk == nil and __NULL or __chk\n");
                out.append("__numKey(").append(target).append(", ")
                    .append(i + 1).append(", ").append(slot(input)).append(", ")
                    .append(isNumberKind(producerKind(input)) ? "true" : "false")
                    .append(")\n");
                emitBoundarySuccess(boundary, "__chk", payload.elementDescriptor());
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private void emitTableNew(SemanticOp op) {
            KindPayload.TableNewPayload payload = (KindPayload.TableNewPayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            out.append(target).append(" = {__t = true, __keys = {}, __order = {}}\n");
            for (KindPayload.TableEntry entry : payload.entries()) {
                out.append(target).append("[").append(luaString(entry.key()))
                    .append("] = ").append(slot(entry.value())).append("\n");
                out.append("__orderAdd(").append(target).append(", ")
                    .append(luaString(entry.key())).append(")\n");
                out.append("__numKey(").append(target).append(", ")
                    .append(luaString(entry.key())).append(", ")
                    .append(slot(entry.value())).append(", ")
                    .append(isNumberKind(producerKind(entry.value())) ? "true" : "false")
                    .append(")\n");
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private void emitArrayLength(SemanticOp op) {
            KindPayload.ArrayLengthPayload payload =
                (KindPayload.ArrayLengthPayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            if (isBytesValue(payload.arrayValue())) {
                // K6 item 7: b.length reads the bytes buffer's fixed
                // logical length through the runtime entry, at the read's
                // own origin.
                out.append(target).append(" = __bytesLength(")
                    .append(slot(payload.arrayValue())).append(", ")
                    .append(luaString(opKey(op.opId()))).append(", ")
                    .append(luaString(op.contract().canonicalDigest())).append(", ")
                    .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(luaString(originOf(op))).append(", ")
                    .append(spanTripletArgs(op)).append(")\n");
            } else {
                out.append(target).append(" = ")
                    .append(slot(payload.arrayValue())).append(".__n\n");
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        /**
         * Whether one value identity's producing op publishes a bytes
         * value (the {@code b.length} receiver selector, K6 item 7): an
         * {@code ARRAY_LENGTH} op carries no operands, so the receiver
         * producer's own result descriptor is the classification
         * authority.
         */
        private boolean isBytesValue(deal.semantic.ir.ValueId value) {
            for (SemanticOp candidate : opsById.values()) {
                if (value.equals(candidate.result())
                        && candidate.resultType() instanceof RuntimeDescriptor.Bytes) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The custom boundary START of a read admission: the input atom
         * renders the actual value kind (the oracle's atomOf — a
         * wrong-kind present value atomizes as its own kind).
         */
        private void emitBoundaryCheckStartAtom(SemanticOp boundary, String target) {
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) boundary.payload();
            out.append("__ev(").append(luaString(opKey(boundary.opId())))
                .append(", \"START\", \"BOUNDARY\", ")
                .append(luaString(boundary.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(boundary.origin().parentOpId())))
                .append(", {__rawArgAtom(")
                .append(luaString(staticKind(boundaryPayload.descriptor())))
                .append(", ").append(target).append(")}, nil, nil)\n");
        }

        /**
         * The admission of one read boundary: the pcall check into the
         * shared {@code __okB}/{@code __chkB} pair (a deferred composite
         * descriptor passes through unchecked), the boundary and owner
         * FAILURE events at the boundary origin, and the checked value
         * written back into the target.
         */
        private void emitBoundaryCheckBlock(SemanticOp op, SemanticOp boundary,
                                            String target) {
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) boundary.payload();
            out.append("__okB, __chkB = ")
                .append(defersContextualCheck(boundaryPayload.descriptor())
                    ? "true, " + target
                    : "pcall(__bcheck, "
                        + luaString(descriptorText(boundaryPayload.descriptor()))
                        + ", "
                        + luaString(staticKind(boundaryPayload.descriptor()))
                        + ", " + target + ")")
                .append("\n");
            out.append("if not __okB then\n");
            out.append("  __chkB.o = ").append(luaString(originOf(boundary)))
                .append("\n");
            emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                "__errtext(__chkB)");
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "__errtext(__chkB)");
            out.append("  error(__chkB, 0)\n");
            out.append("end\n");
            out.append(target).append(" = __chkB\n");
        }

        /** The boundary SUCCESS and the op SUCCESS terminal of a read admission. */
        private void emitBoundarySuccessTail(SemanticOp op, SemanticOp boundary,
                                             String target) {
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) boundary.payload();
            if (defersContextualCheck(boundaryPayload.descriptor())) {
                emitBoundarySuccessAtom(boundary, "__rawArgAtom("
                    + luaString(staticKind(boundaryPayload.descriptor()))
                    + ", " + target + ")");
                emitResultSuccessAtom(op, target);
            } else {
                emitBoundarySuccess(boundary, target,
                    boundaryPayload.descriptor());
                emitResultSuccess(op, target,
                    (RuntimeDescriptor) op.resultType());
            }
        }

        private void emitMemberRead(SemanticOp op) {
            KindPayload.MemberReadPayload payload = (KindPayload.MemberReadPayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            out.append(target).append(" = __member(").append(slot(payload.table()))
                .append(", ").append(luaString(payload.key())).append(")\n");
            SemanticOp boundary = boundaryChildOf(op);
            // The read-side variant materialization: the raw read's
            // declared descriptor decides whether an int-variant slot
            // value becomes a carrier (a number-typed read position) and
            // a number-variant slot value becomes a carrier (its slot
            // mark) — the closed value model's admitted variant. A
            // missing-capable read carries its contextual inner
            // descriptor on the consuming OPTIONAL_READ (the raw read's
            // own result type is the internal-missing marker), so the
            // envelope's present branch keeps the admitted variant too.
            SemanticOp optionalRead = optionalReadOf((ValueId) op.result());
            RuntimeDescriptor readDescriptor = boundary != null
                ? ((KindPayload.BoundaryPayload) boundary.payload()).descriptor()
                : (optionalRead != null
                    ? ((KindPayload.OptionalReadPayload) optionalRead.payload())
                        .descriptor()
                    : (op.resultType() instanceof RuntimeDescriptor descriptor
                        ? descriptor : null));
            out.append(target).append(" = __readVar(").append(slot(payload.table()))
                .append(", ").append(luaString(payload.key())).append(", ")
                .append(target).append(", ")
                .append(readDescriptor != null
                    && isNumberKind(staticKind(readDescriptor)) ? "true" : "false")
                .append(")\n");
            if (boundary != null) {

                emitBoundaryCheckStartAtom(boundary, target);
                emitBoundaryCheckBlock(op, boundary, target);
                // The deferred composite read's SUCCESS atom renders
                // the value's own kind (the pass-through left a
                // possibly wrong-kind value in place; the oracle's
                // atomOf uses the value's own kind too), and the op's
                // own result SUCCESS renders the same value-aware atom
                // (the read's internal result kind is not the value).
                emitBoundarySuccessTail(op, boundary, target);
                return;
            }
            // The OPTIONAL_READ envelope shape: the raw read publishes the
            // internal missing or the present value (present null included)
            // and its SUCCESS atom renders the actual value kind —
            // missing → "missing", null → "null", else the actual-kind
            // atom, exactly the oracle's publish (a wrong-kind present
            // value atomizes as its own kind, never the declared kind).
            RuntimeDescriptor inner = optionalRead == null
                ? null
                : ((KindPayload.OptionalReadPayload) optionalRead.payload()).descriptor();
            out.append("__ev(").append(luaString(opKey(op.opId())))
                .append(", \"SUCCESS\", ").append(luaString(op.kind().name()))
                .append(", ").append(luaString(op.contract().canonicalDigest()))
                .append(", ").append(luaString(parentKey(op.origin().parentOpId())))
                .append(", {}, __rawAtom(")
                .append(luaString(inner == null ? "ref" : "nullable:" + staticKind(inner)))
                .append(", ").append(target).append("), nil)\n");
        }

        /**
         * OPTIONAL_READ: the internal missing pre-maps to language null
         * before the present branch is validated (the closed table's
         * optional-read rule); the CONTEXTUAL_TABLE_READ boundary child
         * validates the pre-mapped result.
         */
        private void emitOptionalRead(SemanticOp op) {
            KindPayload.OptionalReadPayload payload =
                (KindPayload.OptionalReadPayload) op.payload();
            if (payload.value() == null) {
                emitStart(op);
            } else {
                // Custom START: the raw operand atom renders the actual
                // value kind (a wrong-kind present value atomizes as its
                // own kind, exactly the oracle's publish).
                out.append("__ev(").append(luaString(opKey(op.opId())))
                    .append(", \"START\", ").append(luaString(op.kind().name()))
                    .append(", ").append(luaString(op.contract().canonicalDigest()))
                    .append(", ").append(luaString(parentKey(op.origin().parentOpId())))
                    .append(", {__rawAtom(")
                    .append(luaString("nullable:" + staticKind(payload.descriptor())))
                    .append(", ").append(slot(payload.value()))
                    .append(")}, nil, nil)\n");
            }
            String target = slot((ValueId) op.result());
            if (payload.value() == null) {
                out.append(target).append(" = nil\n");
            } else {
                String source = slot(payload.value());
                out.append("if ").append(source).append(" == __MISSING then\n");
                out.append("  ").append(target).append(" = nil\n");
                out.append("else\n");
                out.append("  ").append(target).append(" = ").append(source).append("\n");
                out.append("end\n");
            }
            SemanticOp boundary = boundaryChildOf(op);
            if (boundary != null) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                // Custom boundary START: the input atom renders the
                // actual value kind (identical for a passing value,
                // actual-kind for a wrong-kind present value).
                out.append("__ev(").append(luaString(opKey(boundary.opId())))
                    .append(", \"START\", \"BOUNDARY\", ")
                    .append(luaString(boundary.contract().canonicalDigest())).append(", ")
                    .append(luaString(parentKey(boundary.origin().parentOpId())))
                    .append(", {__rawAtom(")
                    .append(luaString(staticKind(boundaryPayload.descriptor())))
                    .append(", ").append(target).append(")}, nil, nil)\n");

                emitBoundaryCheckBlock(op, boundary, target);
                // The op's own result SUCCESS renders the value-aware
                // atom too (the oracle's publish atomizes the value).
                emitBoundarySuccessTail(op, boundary, target);
            } else {
                emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
            }
        }

        /** Whether one boundary descriptor is a function type (nullable unwrapped). */
        private boolean isFunctionDescriptor(RuntimeDescriptor descriptor) {
            RuntimeDescriptor inner = descriptor instanceof RuntimeDescriptor.Nullable nullable
                ? nullable.inner() : descriptor;
            return inner instanceof RuntimeDescriptor.Func;
        }

        /** Whether one boundary descriptor is an array type (nullable unwrapped). */
        private boolean isArrayDescriptor(RuntimeDescriptor descriptor) {
            RuntimeDescriptor inner = descriptor instanceof RuntimeDescriptor.Nullable nullable
                ? nullable.inner() : descriptor;
            return inner instanceof RuntimeDescriptor.Array;
        }

        private boolean defersContextualCheck(RuntimeDescriptor descriptor) {
            // The composite carriers (functions, arrays, and bytes — a bytes
            // contextual read defers its shape check to the consuming
            // declared cell, whose bytes projection carries the pinned
            // "expected bytes" text at the declaration's own origin).
            return isFunctionDescriptor(descriptor) || isArrayDescriptor(descriptor)
                || isBytesDescriptor(descriptor);
        }

        /** Whether one boundary descriptor is the bytes carrier (nullable unwrapped). */
        private boolean isBytesDescriptor(RuntimeDescriptor descriptor) {
            RuntimeDescriptor inner = descriptor instanceof RuntimeDescriptor.Nullable nullable
                ? nullable.inner() : descriptor;
            return inner instanceof RuntimeDescriptor.Bytes;
        }

        /**
         * HAS_FIELD: the presence boolean over one checked receiver key —
         * the member-read helper's present/absent split (present null
         * included is present; the class-instance half reads the
         * presence map of the E5 instances).
         */
        private void emitHasField(SemanticOp op) {
            KindPayload.HasFieldPayload payload =
                (KindPayload.HasFieldPayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            if (isHostClassReceiver(op)) {

                out.append(target).append(" = (")
                    .append(slot(payload.receiver())).append("[")
                    .append(luaString(payload.key())).append("] ~= nil)\n");
                emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
                return;
            }
            out.append(target).append(" = (__member(").append(slot(payload.receiver()))
                .append(", ").append(luaString(payload.key())).append(") ~= __MISSING)\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private boolean isHostClassReceiver(SemanticOp op) {
            RuntimeDescriptor descriptor = null;
            if (!op.operandTypes().isEmpty()) {
                descriptor = op.operandTypes().get(0);
            } else if (op.payload() instanceof KindPayload.HasFieldPayload payload) {
                for (SemanticOp producer : opsById.values()) {
                    if (payload.receiver().equals(producer.result())
                            && producer.resultType() instanceof RuntimeDescriptor
                                produced) {
                        descriptor = produced;
                        break;
                    }
                }
            }
            if (descriptor instanceof RuntimeDescriptor.Nullable nullable) {
                descriptor = nullable.inner();
            }
            return descriptor instanceof RuntimeDescriptor.Class classDescriptor
                && isHostClass(classDescriptor.classId());
        }

        // =====================================================================
        // The class field ops (CLASSES step 8, E5): FIELD_READ is
        // presence-aware (missing → the OPTIONAL_FIELD_READ boundary's
        // pre-mapped language null; present null stays distinguishable
        // through the instance's presence map); FIELD_WRITE/FIELD_DELETE
        // are commit ops consuming the ASSIGN/DELETE chain's resolved
        // receiver and stored value — exactly one mutation, never a
        // re-evaluated source expression.
        // =====================================================================

        private void emitFieldRead(SemanticOp op) {
            KindPayload.FieldReadPayload payload =
                (KindPayload.FieldReadPayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            SemanticOp receiverBoundary =
                boundaryChildOfKind(op, BoundaryKind.UNTYPED_CLASS_INPUT);
            emitFieldBoundaryCheck(op, receiverBoundary, slot(payload.classValue()));
            out.append("__instT = __chkB\n");
            if (ClassId.ERROR.equals(payload.classId())) {

                out.append("__rvT = __instT.")
                    .append(errorCarrierField(op, payload.field())).append("\n");
            } else if (isHostClass(payload.classId())) {

                out.append("__rvT = __instT[").append(luaString(payload.field()))
                    .append("]\n");
                out.append("if __rvT == __NULL or __rvT == __rt.__NULL then "
                    + "__rvT = nil end\n");
            } else {
                // The presence-aware read: missing → nil before the boundary;
                // present (present null included) → the stored value.
                out.append("__rvT = nil\n");
                out.append("if __instT.__p[").append(luaString(payload.field()))
                    .append("] then\n");
                out.append("  __rvT = __instT.__f[").append(luaString(payload.field()))
                    .append("]\n");
                out.append("  if __rvT == __NULL then __rvT = nil end\n");
                out.append("end\n");
            }
            SemanticOp fieldBoundary =
                boundaryChildOfKind(op, BoundaryKind.OPTIONAL_FIELD_READ);
            emitFieldBoundaryCheck(op, fieldBoundary, "__rvT");
            out.append(target).append(" = __chkB\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private void emitFieldWrite(SemanticOp op) {
            KindPayload.FieldWritePayload payload =
                (KindPayload.FieldWritePayload) op.payload();
            emitStart(op);
            SemanticOp receiverBoundary =
                boundaryChildOfKind(op, BoundaryKind.UNTYPED_CLASS_INPUT);
            emitFieldBoundaryCheck(op, receiverBoundary, slot(payload.classValue()));
            out.append("__instT = __chkB\n");
            SemanticOp fieldBoundary =
                boundaryChildOfKind(op, BoundaryKind.CLASS_FIELD_ASSIGNMENT);
            emitFieldBoundaryCheck(op, fieldBoundary, slot(payload.value()));
            if (ClassId.ERROR.equals(payload.classId())) {
                // The builtin Error field write commits into the carrier's
                // own field (K13 item 6): the field boundary already ran the
                // declared string descriptor, so the committed value is the
                // written string.
                out.append("__instT.")
                    .append(errorCarrierField(op, payload.field()))
                    .append(" = __chkB\n");
            } else if (isHostClass(payload.classId())) {

                out.append("__instT[").append(luaString(payload.field()))
                    .append("] = (__chkB == nil) and __rt.__NULL or __chkB\n");
            } else {
                out.append("__instT.__f[").append(luaString(payload.field()))
                    .append("] = (__chkB == nil) and __NULL or __chkB\n");
                out.append("__instT.__p[").append(luaString(payload.field()))
                    .append("] = true\n");
            }
            emitPlainSuccess(op);
        }

        private void emitFieldDelete(SemanticOp op) {
            KindPayload.FieldDeletePayload payload =
                (KindPayload.FieldDeletePayload) op.payload();
            emitStart(op);
            if (ClassId.ERROR.equals(payload.classId())) {
                // A builtin Error field delete never reaches the IR: every
                // builtin Error field is required-present, and the checker
                // rejects {@code delete e.f} with E4004 — the arm stays a
                // fail-closed producer defect (K13 item 8).
                throw new IllegalStateException("FIELD_DELETE " + op.opId()
                    + " targets the builtin Error field '" + payload.field()
                    + "': every builtin Error field is required-present and the"
                    + " checker rejects the delete with E4004 — a fail-closed"
                    + " producer defect, never emitted");
            }
            SemanticOp receiverBoundary =
                boundaryChildOfKind(op, BoundaryKind.UNTYPED_CLASS_INPUT);
            emitFieldBoundaryCheck(op, receiverBoundary, slot(payload.classValue()));
            if (isHostClass(payload.classId())) {

                out.append("__chkB[").append(luaString(payload.field()))
                    .append("] = nil\n");
                emitPlainSuccess(op);
                return;
            }
            out.append("__chkB.__p[").append(luaString(payload.field()))
                .append("] = nil\n");
            out.append("__chkB.__f[").append(luaString(payload.field()))
                .append("] = nil\n");
            emitPlainSuccess(op);
        }

        /**
         * The builtin Error carrier's field name for one declared field
         * (K13 item 6): {@code code} maps to the carrier's {@code code}
         * slot and {@code message} to its {@code m} slot; any other name is
         * a fail-closed producer defect (the declared field set is exactly
         * those two).
         */
        private static String errorCarrierField(SemanticOp op, String field) {
            return switch (field) {
                case "code" -> "code";
                case "message" -> "m";
                default -> throw new IllegalStateException(op.kind() + " " + op.opId()
                    + " names the builtin Error field '" + field + "': the builtin"
                    + " class declares exactly code and message — a fail-closed"
                    + " producer defect, never emitted");
            };
        }

        /**
         * One field-op boundary child: the START carries the input's
         * actual runtime atom (the raw atom — a null receiver renders
         * "null", never a heap index), the check runs, and the terminal
         * is the boundary SUCCESS with its published value or the two
         * FAILURE events (boundary then owner) with the boundary's own
         * origin.
         */
        private void emitFieldBoundaryCheck(SemanticOp owner, SemanticOp boundary,
                                            String inputExpr) {
            KindPayload.BoundaryPayload payload =
                (KindPayload.BoundaryPayload) boundary.payload();
            out.append("__ev(").append(luaString(opKey(boundary.opId())))
                .append(", \"START\", \"BOUNDARY\", ")
                .append(luaString(boundary.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(boundary.origin().parentOpId())))
                .append(", {__rawAtom(")
                .append(luaString(staticKind(payload.descriptor()))).append(", ")
                .append(inputExpr).append(")}, nil, nil)\n");
            out.append("__okB, __chkB = pcall(__bcheck, ")
                .append(luaString(descriptorText(payload.descriptor()))).append(", ")
                .append(luaString(staticKind(payload.descriptor()))).append(", ")
                .append(inputExpr).append(")\n");
            out.append("if not __okB then\n");
            out.append("  __chkB.o = ").append(luaString(originOf(boundary)))
                .append("\n");
            emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                "__errtext(__chkB)");
            emitFailureEvent(owner.opId(), owner.kind().name(), owner,
                "__errtext(__chkB)");
            out.append("  error(__chkB, 0)\n");
            out.append("end\n");
            emitBoundarySuccess(boundary, "__chkB", payload.descriptor());
        }

        private void emitMemberWrite(SemanticOp op) {
            KindPayload.MemberWritePayload payload =
                (KindPayload.MemberWritePayload) op.payload();
            emitStart(op);
            out.append(slot(payload.table())).append("[")
                .append(luaString(payload.key())).append("] = ")
                .append(slot(payload.value())).append("\n");
            out.append("__orderAdd(").append(slot(payload.table())).append(", ")
                .append(luaString(payload.key())).append(")\n");
            out.append("__numKey(").append(slot(payload.table())).append(", ")
                .append(luaString(payload.key())).append(", ")
                .append(slot(payload.value())).append(", ")
                .append(isNumberKind(producerKind(payload.value())) ? "true" : "false")
                .append(")\n");
            emitPlainSuccess(op);
        }

        private void emitMemberDelete(SemanticOp op) {
            KindPayload.MemberDeletePayload payload =
                (KindPayload.MemberDeletePayload) op.payload();
            emitStart(op);
            out.append(slot(payload.table())).append("[")
                .append(luaString(payload.key())).append("] = nil\n");
            out.append("__orderRemove(").append(slot(payload.table())).append(", ")
                .append(luaString(payload.key())).append(")\n");
            emitPlainSuccess(op);
        }

        private void emitNormalize(SemanticOp op) {
            KindPayload.IndexNormalizePayload payload =
                (KindPayload.IndexNormalizePayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            switch (payload.mode()) {
                case ARRAY_READ, ARRAY_WRITE -> {
                    boolean write = payload.mode() == deal.semantic.ir.IndexMode.ARRAY_WRITE;
                    // The normalized index/length are numeric views: a
                    // runtime number carrier (the value model's variant
                    // representation) unwraps, a plain number passes
                    // through.
                    out.append(target).append(" = {slot = \"a\", i = __num(")
                        .append(slot(payload.rawKey())).append("), n = __num(")
                        .append(slot(payload.currentLength())).append("), a = ")
                        .append(write).append("}\n");
                }
                case BYTES_READ, BYTES_WRITE ->
                    // The bytes slot (K6 item 5): the index against the
                    // receiver's length read; no append decision exists.
                    out.append(target).append(" = {slot = \"b\", i = __num(")
                        .append(slot(payload.rawKey())).append("), n = __num(")
                        .append(slot(payload.currentLength())).append(")}\n");
                case TABLE_READ, TABLE_WRITE ->
                    out.append(target).append(" = {slot = \"t\", k = __num(")
                        .append(slot(payload.rawKey())).append(")}\n");
            }
            out.append("__normalizeEvent(")
                .append(luaString(opKey(op.opId()))).append(", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                .append(target).append(")\n");
        }

        private void emitIndexRead(SemanticOp op) {
            KindPayload.IndexReadPayload payload = (KindPayload.IndexReadPayload) op.payload();
            SemanticOp boundary = opsById.get(payload.elementBoundaryOpId());
            RuntimeDescriptor descriptor =
                ((KindPayload.BoundaryPayload) boundary.payload()).descriptor();
            boolean nullable = descriptor instanceof RuntimeDescriptor.Nullable;
            RuntimeDescriptor inner = nullable
                ? ((RuntimeDescriptor.Nullable) descriptor).inner() : descriptor;
            emitSlotOperandStart(op);
            if (!op.operandTypes().isEmpty()
                    && op.operandTypes().get(0) instanceof RuntimeDescriptor.Bytes) {
                // K6 item 2: the bytes element read — the length read's
                // result is the normalize's currentLength and the
                // BYTE_ELEMENT_READ cell consumes {index, length} from the
                // same slot.
                out.append(slot((ValueId) op.result())).append(" = __bytesRead(")
                    .append(luaString(opKey(op.opId()))).append(", ")
                    .append(luaString(op.contract().canonicalDigest())).append(", ")
                    .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(luaString(opKey(boundary.opId()))).append(", ")
                    .append(luaString(boundary.contract().canonicalDigest())).append(", ")
                    .append(luaString(parentKey(boundary.origin().parentOpId()))).append(", ")
                    .append(slot(payload.container())).append(", ")
                    .append(slot(payload.slot())).append(", ")
                    .append(luaString(originOf(boundary))).append(", ")
                    .append(spanTripletArgs(boundary)).append(")\n");
                emitResultSuccess(op, slot((ValueId) op.result()),
                    (RuntimeDescriptor) op.resultType());
                return;
            }
            String target = slot((ValueId) op.result());
            out.append(target).append(" = __arrayRead(")
                .append(luaString(opKey(op.opId()))).append(", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                .append(luaString(opKey(boundary.opId()))).append(", ")
                .append(luaString(boundary.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(boundary.origin().parentOpId()))).append(", ")
                .append(luaString(descriptorText(descriptor))).append(", ")
                .append(luaString(staticKind(inner))).append(", ")
                .append(slot(payload.container())).append(", ")
                .append(slot(payload.slot())).append(", ")
                .append(nullable ? "true" : "false").append(", ")
                .append(isNumberKind(staticKind(inner)) ? "true" : "false")
                .append(", ")
                .append(luaString(originOf(op))).append(")\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private void emitIndexWrite(SemanticOp op) {
            KindPayload.IndexWritePayload payload =
                (KindPayload.IndexWritePayload) op.payload();
            emitStart(op);
            String container = slot(payload.container());
            String slotName = slot(payload.slot());
            String value = slot(payload.value());
            String numberWriteFlag = isNumberKind(producerKind(payload.value()))
                ? "true" : "false";
            out.append("if ").append(slotName).append(".slot == \"b\" then\n");
            // The bytes commit (K6 item 4): the E8013 value-range check at
            // the assignment expression and the single in-place mutation
            // through the runtime entry; the bounds already passed at the
            // chain's BYTE_ELEMENT_ASSIGNMENT boundary.
            out.append("  __bytesCommit(")
                .append(luaString(opKey(op.opId()))).append(", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                .append(container).append(", ").append(slotName).append(", ")
                .append(value).append(", ").append(luaString(originOf(op))).append(", ")
                .append(spanTripletArgs(op)).append(")\n");
            out.append("elseif ").append(slotName).append(".slot == \"a\" then\n");
            out.append("  if ").append(slotName).append(".i == ").append(slotName)
                .append(".n then\n");
            out.append("    ").append(container).append(".__n = ").append(container)
                .append(".__n + 1\n");
            out.append("  end\n");
            out.append("  ").append(container).append("[").append(slotName)
                .append(".i + 1] = ").append(value)
                .append(" == nil and __NULL or ").append(value).append("\n");
            out.append("  __numKey(").append(container).append(", ").append(slotName)
                .append(".i + 1, ").append(value).append(", ").append(numberWriteFlag)
                .append(")\n");
            out.append("else\n");
            out.append("  ").append(container).append("[").append(slotName)
                .append(".k] = ").append(value).append("\n");
            out.append("  __orderAdd(").append(container).append(", ").append(slotName)
                .append(".k)\n");
            out.append("  __numKey(").append(container).append(", ").append(slotName)
                .append(".k, ").append(value).append(", ").append(numberWriteFlag)
                .append(")\n");
            out.append("end\n");
            emitPlainSuccess(op);
        }

        private void emitIndexDelete(SemanticOp op) {
            KindPayload.IndexDeletePayload payload =
                (KindPayload.IndexDeletePayload) op.payload();
            emitStart(op);
            String container = slot(payload.container());
            String slotName = slot(payload.slot());
            out.append("if ").append(slotName).append(".slot == \"a\" then\n");
            out.append("  if ").append(slotName).append(".i < ").append(container)
                .append(".__n then\n");
            out.append("    ").append(container).append("[").append(slotName)
                .append(".i + 1] = nil\n");
            out.append("  end\n");
            out.append("else\n");
            out.append("  ").append(container).append("[").append(slotName)
                .append(".k] = nil\n");
            out.append("  __orderRemove(").append(container).append(", ")
                .append(slotName).append(".k)\n");
            out.append("end\n");
            emitPlainSuccess(op);
        }

        /**
         * A free BOUNDARY op: the prelude check over the payload input,
         * run under the boundary's own origin with the boundary's own
         * single FAILURE terminal — the parented arms' contract (no owner
         * terminal exists for an unparented boundary); the success
         * terminal is unchanged.
         */
        private void emitFreeBoundary(SemanticOp op) {
            KindPayload.BoundaryPayload payload = (KindPayload.BoundaryPayload) op.payload();
            out.append("__ev(").append(luaString(opKey(op.opId())))
                .append(", \"START\", \"BOUNDARY\", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId())))
                .append(", {}, nil, nil)\n");
            out.append("__okB, __chk = pcall(__bcheck, ")
                .append(bcheckArgs(payload.descriptor(), slot(payload.input())))
                .append(")\n");
            out.append("if not __okB then\n");
            out.append("  __chk.o = ").append(luaString(originOf(op))).append("\n");
            emitFailureEvent(op.opId(), "BOUNDARY", op, "__errtext(__chk)");
            out.append("  error(__chk, 0)\n");
            out.append("end\n");
            if (op.result() instanceof ValueId valueId) {
                out.append(slot(valueId)).append(" = __chk\n");
                emitBoundarySuccess(op, slot(valueId), payload.descriptor());
            } else {
                emitBoundarySuccess(op, "__chk", payload.descriptor());
            }
        }

        private void emitBindingAlloc(SemanticOp op) {
            KindPayload.BindingAllocPayload payload =
                (KindPayload.BindingAllocPayload) op.payload();
            emitStart(op);
            if (isCatchBinding(payload.binding())) {

                emitPlainSuccess(op);
                return;
            }
            switch (cellKindOf(payload.binding(), payload.generation())) {
                case DIRECT -> out.append(cell(payload.binding(), payload.generation())).append(" = nil\n");
                case SHARED_CELL -> out.append(cell(payload.binding(), payload.generation())).append(" = {}\n");
            }
            emitPlainSuccess(op);
        }

        private void emitBindingInit(SemanticOp op) {
            KindPayload.BindingInitPayload payload =
                (KindPayload.BindingInitPayload) op.payload();
            emitStart(op);
            BindingCellKind kind = cellKindOf(payload.binding(), payload.generation());
            String valueExpr = intrinsicCarrierExpr(payload.value());
            if (valueExpr == null) {
                valueExpr = hasProducer(payload.value())
                    ? slot(payload.value()) : exportPlaceholderCarrier();
            }
            if (kind == BindingCellKind.SHARED_CELL) {
                // In-place publication: the cell table's identity is held
                // by captures and adapters, so the commit writes the cell
                // slot (never a replacement — the oracle's Cell.value
                // mutation).
                out.append(cellSource(payload.binding(), payload.generation()))
                    .append("[1] = ").append(valueExpr).append("\n");
            } else {
                out.append(cellSource(payload.binding(), payload.generation()))
                    .append(" = ").append(valueExpr).append("\n");
            }
            emitPlainSuccess(op);
        }

        private String intrinsicCarrierExpr(ValueId valueId) {
            IntrinsicKind kind = intrinsicKindOf(valueId);
            return kind == null ? null : intrinsicAccessor(kind);
        }

        /**
         * The memoized intrinsic carrier expression of one identity whose
         * registration — the kind's only authority — resolves an intrinsic
         * kind (the residual export-read kind arm's rule), or {@code null}.
         */
        private String registeredIntrinsicCarrierExpr(ValueId valueId) {
            IntrinsicKind kind = registeredIntrinsicKindOf(valueId);
            return kind == null ? null : intrinsicAccessor(kind);
        }

        /** The registered intrinsic kind of one value identity, or null. */
        private IntrinsicKind registeredIntrinsicKindOf(ValueId valueId) {
            for (LoweredModuleUnit moduleUnit : units.values()) {
                FunctionExecutionBinding found = moduleUnit.functionBindings().get(
                    new FunctionAllocationIdentity(valueId.id()));
                if (found instanceof FunctionExecutionBinding.IntrinsicFunction
                        intrinsic) {
                    return intrinsic.kind();
                }
                if (found != null) {
                    return null;
                }
            }
            return null;
        }

        private static String exportPlaceholderCarrier() {
            return "__intrinsicExport()";
        }

        /**
         * The memoized carrier accessor of one intrinsic kind: the kind
         * tag and the intrinsic's declared descriptor text and canonical
         * spec text — the only descriptor source (never a call site's or
         * an adapter target's).
         */
        private static String intrinsicAccessor(IntrinsicKind kind) {
            RuntimeDescriptor.Func declared = kind.declaredSignature();
            return "__intrinsicFn(" + luaString(kind.name()) + ", "
                + luaString(descriptorText(declared)) + ", "
                + luaString(declared.canonicalSpecText()) + ")";
        }

        private void emitBindingLoad(SemanticOp op) {
            KindPayload.BindingLoadPayload payload =
                (KindPayload.BindingLoadPayload) op.payload();
            emitStart(op);
            BindingCellKind kind = cellKindOf(payload.binding(), payload.generation());
            out.append(slot((ValueId) op.result())).append(" = ")
                .append(kind == BindingCellKind.SHARED_CELL
                    ? cellSource(payload.binding(), payload.generation()) + "[1]"
                    : cellSource(payload.binding(), payload.generation()))
                .append("\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
        }

        private void emitBindingStore(SemanticOp op) {
            KindPayload.BindingStorePayload payload =
                (KindPayload.BindingStorePayload) op.payload();
            emitStart(op);
            BindingCellKind kind = cellKindOf(payload.binding(), payload.generation());
            if (kind == BindingCellKind.SHARED_CELL) {
                // In-place publication: captures and adapters hold the
                // cell table by identity (the oracle's Cell.value
                // mutation — never a replacement).
                out.append(cellSource(payload.binding(), payload.generation()))
                    .append("[1] = ").append(slot(payload.value())).append("\n");
            } else {
                out.append(cellSource(payload.binding(), payload.generation()))
                    .append(" = ").append(slot(payload.value())).append("\n");
            }
            emitPlainSuccess(op);
        }

        private void emitClosureNew(SemanticOp op) {
            KindPayload.ClosureNewPayload payload =
                (KindPayload.ClosureNewPayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            StringBuilder args = new StringBuilder();
            for (BindingGeneration capture : payload.captures()) {
                if (args.length() > 0) {
                    args.append(", ");
                }
                args.append(captureArg(capture));
            }
            out.append(target).append(" = {__fn = ").append(fnFactory(payload.function()))
                .append("(").append(args).append("), __sig = ")
                .append(luaString(descriptorText(payload.signature())))
                .append(", __csig = ")
                .append(luaString(payload.signature().canonicalSpecText()))
                .append(", __fid = ")
                .append(payload.function().id()).append("}\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        /**
         * FUNCTION_ADAPT (E6, D15 creation): a wrapper function value
         * carrying the closed capture mode — VALUE retains the
         * creation-time source identity; SHARED_CELL records the
         * generation cell re-read per invocation (live reassignment
         * observed); REEVALUATE_THUNK records the detached thunk
         * re-executor. Creation evaluates no thunk and reads no binding
         * (VALUE's single operand already completed); the wrapper's
         * invocation (the {@code __fn} protocol) runs the D15 sequence at
         * every call site with the invoking op's origin.
         */
        private void emitFunctionAdapt(SemanticOp op) {
            KindPayload.FunctionAdaptPayload payload =
                (KindPayload.FunctionAdaptPayload) op.payload();
            // The VALUE operand's carrier expression: for the seeded
            // intrinsic identity no slot holds it before this creation, so
            // the START's operand atom and the wrapper's own field read the
            // one carrier expression (never a nil slot read).
            String valueExpr = payload.source() instanceof AdaptSourceRef.Value value
                ? adaptValueExpr(value) : null;
            emitAdaptStart(op, payload, valueExpr);
            String target = slot((ValueId) op.result());
            out.append(target).append(" = {__mode = ");
            switch (payload.mode()) {
                case VALUE -> out.append("0");
                case SHARED_CELL -> out.append("1");
                case REEVALUATE_THUNK -> out.append("2");
            }
            out.append(", __m = ").append(payload.sourceSignature().paramTypes().size())
                .append(", __csrc = ")
                .append(luaString(payload.sourceSignature().canonicalSpecText()))
                .append(", __sig = ")
                .append(luaString(descriptorText(payload.targetSignature())))
                .append(", __csig = ")
                .append(luaString(payload.targetSignature().canonicalSpecText()))
                .append(", __fid = nil");
            switch (payload.source()) {
                case AdaptSourceRef.Value ignored ->
                    out.append(", __value = ").append(valueExpr);
                case AdaptSourceRef.SharedCell cell ->
                    out.append(", __cell = ")
                        .append(cellSource(cell.binding(), cell.generation()));
                case AdaptSourceRef.Thunk thunk ->
                    out.append(", __thunk = ").append(thunkFn(op.opId()));
            }
            out.append("}\n");
            out.append(target).append(".__fn = __adaptInvoke\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private String adaptValueExpr(AdaptSourceRef.Value value) {
            String sourceExpr = intrinsicCarrierExpr(value.value());
            if (sourceExpr == null) {
                sourceExpr = hasProducer(value.value())
                    ? slot(value.value()) : exportPlaceholderCarrier();
            }
            return sourceExpr;
        }

        /**
         * The FUNCTION_ADAPT START (E6): the VALUE operand's atom is the
         * operand's own carrier expression ({@code valueExpr}) exactly when
         * the op records that operand (the oracle atomizes its operand list);
         * the SHARED_CELL and REEVALUATE_THUNK modes carry zero operand atoms
         * (their operands are empty by construction).
         */
        private void emitAdaptStart(SemanticOp op,
                KindPayload.FunctionAdaptPayload payload, String valueExpr) {
            out.append("__ev(").append(luaString(opKey(op.opId()))).append(", \"START\", ")
                .append(luaString(op.kind().name())).append(", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId()))).append(", {");
            if (valueExpr != null && !op.operands().isEmpty()) {
                out.append("__atom(")
                    .append(luaString(staticKind(payload.sourceSignature())))
                    .append(", ").append(valueExpr).append(")");
            }
            out.append("}, nil, nil)\n");
        }

        /**
         * RECURSIVE_GROUP_INIT (E8, atomic publication): phase 1
         * allocates every member's SHARED_CELL (a fresh table per
         * execution); phase 2 allocates every member identity into a
         * temporary — the per-member factory invocation over the member's
         * capture cells (the {@code CLOSURE_NEW} wrapper shape carrying
         * the exact signature); phase 3 publishes the member wrappers to
         * the member cells in declaration order in one ordered sequence.
         * The publication writes into the already-allocated cell tables
         * (never a replacement — a sibling's capture holds the cell
         * table by identity), and no member observes a partially
         * initialized group: no member body runs at group execution and
         * every identity is allocated before the first publication.
         */
        private void emitRecursiveGroupInit(SemanticOp op) {
            KindPayload.RecursiveGroupInitPayload payload =
                (KindPayload.RecursiveGroupInitPayload) op.payload();
            emitStart(op);
            for (BindingId binding : payload.bindings()) {
                out.append(cell(binding, 0)).append(" = {}\n");
            }
            for (int i = 0; i < payload.functions().size(); i++) {
                FunctionId functionId = payload.functions().get(i);
                LoweredFunction function = functionOf(functionId);
                if (function == null) {
                    throw new IllegalStateException("group member " + functionId
                        + " has no LoweredFunction record (producer defect)");
                }
                StringBuilder args = new StringBuilder();
                for (BindingGeneration capture : function.captures()) {
                    if (args.length() > 0) {
                        args.append(", ");
                    }
                    args.append(captureArg(capture));
                }
                out.append(groupTemp(op, i)).append(" = {__fn = ")
                    .append(fnFactory(functionId)).append("(").append(args)
                    .append("), __sig = ")
                    .append(luaString(descriptorText(function.descriptor())))
                    .append(", __csig = ")
                    .append(luaString(function.descriptor().canonicalSpecText()))
                    .append(", __fid = ")
                    .append(functionId.id()).append("}\n");
            }
            for (int i = 0; i < payload.bindings().size(); i++) {
                out.append(cell(payload.bindings().get(i), 0)).append("[1] = ")
                    .append(groupTemp(op, i)).append("\n");
            }
            emitPlainSuccess(op);
        }

        /** One member identity temporary of the group op (S-table held). */
        private String groupTemp(SemanticOp op, int member) {
            return "S.__gv" + op.opId().id() + "_" + member;
        }

        private void emitAssign(SemanticOp op) {
            KindPayload.AssignPayload payload = (KindPayload.AssignPayload) op.payload();
            emitStart(op);
            out.append("__okT, __resT = pcall(function()\n");
            for (OpId childId : payload.childOps()) {
                SemanticOp child = opsById.get(childId);
                emitChainOperandProducers(child);
                if (child.kind() == SemanticOpKind.BOUNDARY) {
                    emitChainBoundary(child, (KindPayload.BoundaryPayload) child.payload(),
                        op);
                } else {
                    emitOp(child);
                }
            }
            out.append("end)\n");
            out.append("if not __okT then\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__resT)");
            out.append("  error(__resT, 0)\n");
            out.append("end\n");
            RuntimeDescriptor committedKind = producerResultType(op.result());
            emitResultSuccess(op, slot((ValueId) op.result()), committedKind);
        }

        /**
         * The static kind of one written value: its producing op's result
         * type (the same producer lookup {@link #producerResultType} uses,
         * ASSIGN/DELETE republishes skipped) — the exact typing rule the
         * oracle and the shared JVM runtime store for the value. Feeds the
         * container's {@code __numKey} mark so the JSON_STRINGIFY walker
         * spells a number-typed slot through the closed decimal spelling.
         */
        private String producerKind(ValueId value) {
            return staticKind(producerResultType(value));
        }

        /** A number-typed static kind (a nullable number included). */
        private static boolean isNumberKind(String kind) {
            return "number".equals(kind) || "nullable:number".equals(kind);
        }

        private void emitDelete(SemanticOp op) {
            KindPayload.DeletePayload payload = (KindPayload.DeletePayload) op.payload();
            emitStart(op);
            out.append("__okT, __resT = pcall(function()\n");
            for (OpId childId : payload.childOps()) {
                SemanticOp child = opsById.get(childId);
                emitChainOperandProducers(child);
                if (child.kind() == SemanticOpKind.BOUNDARY) {
                    emitChainBoundary(child, (KindPayload.BoundaryPayload) child.payload(),
                        op);
                } else {
                    emitOp(child);
                }
            }
            out.append("end)\n");
            out.append("if not __okT then\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__resT)");
            out.append("  error(__resT, 0)\n");
            out.append("end\n");
            emitPlainSuccess(op);
        }

        /**
         * A-D2 ("each child's operands complete before that child's
         * START"): the child's transitive operand-producing closure is
         * emitted at the child's position inside the chain — the
         * receiver/key/RHS operand effects interleave with the children
         * exactly as the hoisted-operand parity fixtures pin (an
         * operand's nested side-effecting argument completes before the
         * operand call's own effect, and the RHS operand effects run
         * only after the key child completed). The block walk skips
         * these ops (they are registered in the chain-owned set), so
         * each is emitted exactly once, here.
         */
        private void emitChainOperandProducers(SemanticOp child) {
            for (SemanticOp producer : ChainOperandCompletion.operandProducersOf(
                    child, unitOwning(child), structuralOwned)) {
                emitOp(producer);
            }
        }

        /**
         * The lowered unit that owns one op (the chain-operand completion's
         * membership authority): a chain inside a non-entry module's
         * factory resolves its operand producers against that module's own
         * ops, never against the session's entry unit — a cross-module
         * closure body's chain (the returned-closure drive) would otherwise
         * lose its operand producers (their value identities live in the
         * owning module) and execute with unset slots.
         */
        private LoweredModuleUnit unitOwning(SemanticOp op) {
            ModuleId owner = opModule.get(op.opId());
            LoweredModuleUnit owning = owner == null ? null : units.get(owner);
            return owning == null ? unit : owning;
        }

        /** A chain boundary child (bounds check only from its projection, A-D8). */
        private void emitChainBoundary(SemanticOp boundary,
                                       KindPayload.BoundaryPayload payload,
                                       SemanticOp chain) {
            String input = slot(payload.input());
            switch (payload.kind()) {
                case BYTE_ELEMENT_ASSIGNMENT -> {
                    // K6 item 4: the E8012 bounds cell (index-expression
                    // origin), then the element descriptor check; the
                    // commit owns the E8013 range check.
                    out.append("__bytesBounds(")
                        .append(luaString(opKey(boundary.opId()))).append(", ")
                        .append(luaString(boundary.contract().canonicalDigest()))
                        .append(", ")
                        .append(luaString(parentKey(boundary.origin().parentOpId())))
                        .append(", ").append(input).append(", ")
                        .append(chainSlotExpr(chain)).append(", ")
                        .append(luaString(descriptorText(payload.descriptor())))
                        .append(", ")
                        .append(luaString(staticKind(payload.descriptor()))).append(", ")
                        .append(luaString(originOf(boundary))).append(")\n");
                }
                case ARRAY_ELEMENT_ASSIGNMENT, ARRAY_ELEMENT_DELETE -> {
                    // __arrayBounds emits the boundary START/terminal events
                    // (the bounds check runs only from this projection); the
                    // delete boundary's input operand is the normalized slot.
                    if (payload.kind() == BoundaryKind.ARRAY_ELEMENT_DELETE
                            && slotEqualsChainSlot(payload.input(), chain)) {
                        out.append("__arrayBoundsSlot(")
                            .append(luaString(opKey(boundary.opId()))).append(", ")
                            .append(luaString(boundary.contract().canonicalDigest()))
                            .append(", ")
                            .append(luaString(parentKey(boundary.origin().parentOpId())))
                            .append(", ").append(chainSlotExpr(chain)).append(", ")
                            .append(chainLengthExpr(chain)).append(", ")
                            .append(payload.kind() == BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT
                                ? "true" : "false").append(", ")
                            .append(luaString(originOf(boundary))).append(")\n");
                    } else {
                    out.append("__arrayBounds(")
                        .append(luaString(opKey(boundary.opId()))).append(", ")
                        .append(luaString(boundary.contract().canonicalDigest()))
                        .append(", ")
                        .append(luaString(parentKey(boundary.origin().parentOpId())))
                        .append(", ").append(input).append(", ")
                        .append(chainSlotExpr(chain)).append(", ")
                        .append(chainLengthExpr(chain)).append(", ")
                        .append(luaString(descriptorText(payload.descriptor()))).append(", ")
                        .append(luaString(staticKind(payload.descriptor()))).append(", ")
                        .append(payload.kind() == BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT
                            ? "true" : "false").append(", ")
                        .append(luaString(originOf(boundary))).append(")\n");
                    }
                }
                default -> {
                    emitBoundaryStart(boundary, input, payload.descriptor());
                    out.append("__chk = ")
                        .append(bcheckExpr(payload.descriptor(), input)).append("\n");
                    emitBoundarySuccess(boundary, "__chk", payload.descriptor());
                }
            }
        }

        /** The chain's normalize-slot local (found among its children). */
        private String chainSlotExpr(SemanticOp chain) {
            for (OpId childId : chainChildOps(chain)) {
                SemanticOp child = opsById.get(childId);
                if (child.kind() == SemanticOpKind.INDEX_NORMALIZE
                        && child.result() instanceof ValueId valueId) {
                    return slot(valueId);
                }
            }
            return "nil";
        }

        private String chainLengthExpr(SemanticOp chain) {
            for (OpId childId : chainChildOps(chain)) {
                SemanticOp child = opsById.get(childId);
                if (child.kind() == SemanticOpKind.ARRAY_LENGTH
                        && child.result() instanceof ValueId valueId) {
                    return slot(valueId);
                }
            }
            return "nil";
        }

        /** The single BOUNDARY child parented to the given op, or null. */
        private SemanticOp boundaryChildOf(SemanticOp op) {
            for (SemanticOp candidate : opsById.values()) {
                if (candidate.kind() == SemanticOpKind.BOUNDARY
                        && op.opId().equals(candidate.origin().parentOpId())) {
                    return candidate;
                }
            }
            return null;
        }

        private void emitCall(SemanticOp op) {
            KindPayload.CallPayload payload = (KindPayload.CallPayload) op.payload();

            FunctionExecutionBinding binding = payload.callee()
                    instanceof KindPayload.CallCallee.Dynamic
                ? null : callBinding(payload);
            emitStart(op);

            if (binding instanceof FunctionExecutionBinding.HostFunction host) {
                emitHostCall(op, payload, host.hostModuleId(), host.exportName(),
                    true);
                return;
            }
            if (binding instanceof FunctionExecutionBinding.HostFunctionValue hostValue) {
                emitHostValueCall(op, payload, hostValue);
                return;
            }

            if (binding instanceof FunctionExecutionBinding.IntrinsicFunction intrinsic) {
                emitIntrinsicValueCall(op, payload, intrinsic);
                return;
            }
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                SemanticOp boundary = opsById.get(boundaryId);
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                emitBoundaryStart(boundary, slot(boundaryPayload.input()),
                    boundaryPayload.descriptor());
                // The cell runs under the recorded call origin: a failing
                // declared parameter reports the callee's parameter
                // declaration (the pinned corpus span), and the boundary
                // and the CALL each publish their own FAILURE terminal
                // (the oracle's exact sequence).
                out.append("__okB, __chk = pcall(__bcheck, ")
                    .append(bcheckArgs(boundaryPayload.descriptor(),
                        slot(boundaryPayload.input())))
                    .append(")\n");
                out.append("if not __okB then\n");
                out.append("  __chk.o = ").append(luaString(originOf(boundary)))
                    .append("\n");
                emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                    "__errtext(__chk)");
                emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__chk)");
                out.append("  error(__chk, 0)\n");
                out.append("end\n");
                emitBoundarySuccess(boundary, "__chk", boundaryPayload.descriptor());
            }
            if (payload.callee() instanceof KindPayload.CallCallee.Dynamic dynamic) {

                emitDynamicCall(op, payload, dynamic);
                String dynamicResult = slot((ValueId) op.result());
                out.append(dynamicResult).append(" = __resT\n");
                emitResultSuccess(op, dynamicResult,
                    (RuntimeDescriptor) op.resultType());
                return;
            }
            switch (binding) {
                case FunctionExecutionBinding.LoweredBody body -> {
                    FunctionId callee = body.functionId();
                    String savedState = emitInvocationStateSave(callee, op.opId());
                    out.append("table.insert(__frames, 1, ")
                        .append(luaString(String.valueOf(callee.id()))).append(")\n");
                    // The invoking call's origin: a failure inside the body
                    // whose arm renders the call origin (the @jsonable
                    // toJson walk) reads the innermost active call.
                    out.append("__callOrigins[#__callOrigins + 1] = ")
                        .append(luaString(originOf(op))).append("\n");
                    out.append("__okT, __resT = pcall(");
                    if (payload.callee()
                            instanceof KindPayload.CallCallee.Indirect indirect) {
                        // The value-carried invocation (a body with
                        // creation-site captures): the closure value the
                        // binding holds runs its own invoker, whose
                        // captured cells are the ones its creation
                        // published — never a call-site re-resolution of
                        // a per-iteration incarnation.
                        out.append("__unfn(").append(slot(indirect.callee()))
                            .append(")");
                    } else {
                        out.append(fnFactory(callee)).append("(");
                        LoweredFunction calleeFunction = functionOf(callee);
                        List<BindingGeneration> captures = calleeFunction == null
                            ? List.of() : calleeFunction.captures();
                        for (int i = 0; i < captures.size(); i++) {
                            if (i > 0) {
                                out.append(", ");
                            }
                            out.append(captureArg(captures.get(i)));
                        }
                        out.append(")");
                    }
                    for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                        out.append(", ");
                        SemanticOp boundary =
                            opsById.get(payload.parameterBoundaryOpIds().get(i));
                        out.append(slot(((KindPayload.BoundaryPayload) boundary.payload())
                            .input()));
                    }
                    out.append(")\n");
                    out.append("__callOrigins[#__callOrigins] = nil\n");
                    out.append("table.remove(__frames, 1)\n");
                    emitInvocationStateRestore(callee, savedState);
                    out.append("if not __okT then\n");
                    emitFailureEvent(op.opId(), op.kind().name(), op,
                        "__errtext(__resT)");
                    out.append("  error(__resT, 0)\n");
                    out.append("end\n");
                }
                case FunctionExecutionBinding.AdapterBinding adapter -> {

                    if (adapterIntrinsicKind(adapter) != null) {
                        emitAdapterOverIntrinsicRun(op, payload, adapter);
                        break;
                    }
                    String adapterSlot =
                        slot((ValueId) opsById.get(adapter.adaptOpId()).result());
                    StringBuilder args = new StringBuilder();
                    for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                        if (args.length() > 0) {
                            args.append(", ");
                        }
                        SemanticOp boundary = opsById.get(boundaryId);
                        args.append(slot(((KindPayload.BoundaryPayload) boundary.payload())
                            .input()));
                    }
                    out.append("__okT, __resT = pcall(").append(adapterSlot)
                        .append(".__fn, ").append(adapterSlot).append(", ")
                        .append(luaString(originOf(op)));
                    if (args.length() > 0) {
                        out.append(", ").append(args);
                    }
                    out.append(")\n");
                    out.append("if not __okT then\n");
                    emitFailureEvent(op.opId(), op.kind().name(), op,
                        "__errtext(__resT)");
                    out.append("  error(__resT, 0)\n");
                    out.append("end\n");
                }
                case FunctionExecutionBinding.ExternalFunction external ->
                    emitExternalCall(op, payload, external);
                default -> throw new IllegalStateException("CALL " + op.opId()
                    + " resolves a binding outside the statically-resolved slice: "
                    + binding);
            }
            out.append(slot((ValueId) op.result())).append(" = __resT\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
        }

        private void emitExternalCall(SemanticOp op, KindPayload.CallPayload payload,
                                      FunctionExecutionBinding.ExternalFunction external) {
            if (external.executionOwner()
                    != deal.semantic.ir.ExternalExecutionOwner.SHARED_BODY) {
                throw new IllegalStateException("CALL " + op.opId()
                    + " resolves the external binding " + external.moduleId() + "."
                    + external.exportName() + " with execution owner "
                    + external.executionOwner() + " (a RETAINED_ABI external has no"
                    + " production emission arm — producer defect)");
            }
            LoweredModuleUnit calleeUnit = units.get(external.moduleId());
            if (calleeUnit == null) {
                throw new IllegalStateException("CALL " + op.opId()
                    + " resolves the external callee module " + external.moduleId()
                    + " outside the session's closure (producer defect)");
            }
            SemanticOp entry = resolveExternalEntry(op, payload, external, calleeUnit);
            KindPayload.ExternalEntryPayload entryPayload =
                (KindPayload.ExternalEntryPayload) entry.payload();
            LoweredFunction calleeFunction =
                calleeUnit.functions().get(entryPayload.function());
            if (calleeFunction == null) {
                throw new IllegalStateException("EXTERNAL_ENTRY " + entry.opId()
                    + " resolves the missing lowered function "
                    + entryPayload.function().id() + " (producer defect)");
            }
            String calleePath = calleeUnit.moduleId().path();
            emitModulePush();
            out.append("__module = ").append(luaString(calleePath)).append("\n");
            if (trace) {
                // The entry record's START parented to the triggering
                // caller CALL (the oracle's emitStartParented): no inputs
                // (the entry runs no parameter boundaries).
                out.append("__ev(").append(luaString(opKey(entry.opId())))
                    .append(", \"START\", \"EXTERNAL_ENTRY\", ")
                    .append(luaString(entry.contract().canonicalDigest()))
                    .append(", ").append(luaString(opKey(op.opId())))
                    .append(", {}, nil, nil)\n");
            }
            String savedState = emitInvocationStateSave(entryPayload.function(),
                op.opId());
            out.append("table.insert(__frames, 1, ")
                .append(luaString(String.valueOf(entryPayload.function().id())))
                .append(")\n");
            out.append("__callOrigins[#__callOrigins + 1] = ")
                .append(luaString(originOf(op))).append("\n");
            out.append("__okT, __resT = pcall(")
                .append(fnFactory(entryPayload.function())).append("(");
            List<BindingGeneration> captures = calleeFunction.captures();
            for (int i = 0; i < captures.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(captureArg(captures.get(i)));
            }
            out.append(")");
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                SemanticOp boundary = opsById.get(boundaryId);
                out.append(", ").append(slot(
                    ((KindPayload.BoundaryPayload) boundary.payload()).input()));
            }
            out.append(")\n");
            out.append("__callOrigins[#__callOrigins] = nil\n");
            out.append("table.remove(__frames, 1)\n");
            emitInvocationStateRestore(entryPayload.function(), savedState);
            out.append("if not __okT then\n");
            if (trace) {
                // The entry record's FAILURE under the callee module,
                // parented to the caller CALL (the oracle's
                // emitFailureParented).
                out.append("  __ev(").append(luaString(opKey(entry.opId())))
                    .append(", \"FAILURE\", \"EXTERNAL_ENTRY\", ")
                    .append(luaString(entry.contract().canonicalDigest()))
                    .append(", ").append(luaString(opKey(op.opId())))
                    .append(", {}, nil, __errtext(__resT))\n");
            }
            // Restore the caller's module before the caller's own
            // terminal: the entry events above carry the callee's module,
            // the caller's CALL FAILURE carries the caller's (the oracle's
            // own tagging).
            emitModulePop();
            if (trace) {
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "__errtext(__resT)");
            }
            out.append("  error(__resT, 0)\n");
            out.append("end\n");
            if (trace) {
                out.append("__ev(").append(luaString(opKey(entry.opId())))
                    .append(", \"SUCCESS\", \"EXTERNAL_ENTRY\", ")
                    .append(luaString(entry.contract().canonicalDigest()))
                    .append(", ").append(luaString(opKey(op.opId())))
                    .append(", {}, __atom(")
                    .append(luaString(staticKind(entryPayload.signature().returnType())))
                    .append(", __resT), nil)\n");
            }
            emitModulePop();
        }

        /**
         * The synchronous invocation's private-state save, or {@code null}
         * when the callee body has no private state. The frame it pushes
         * carries the previous active marker and the saved state; the
         * matching restore is <em>unconditional</em> (like the async body
         * task's): the enclosing invocation may have been started by a
         * route this call site cannot name at compile time (a dynamic
         * dispatch of an escaped carrier, an entry of another unit), so
         * the active marker cannot gate the restore — the copied values
         * are the enclosing invocation's private state whatever started
         * it. The marker itself is still maintained so a re-entrant
         * invocation leaves it in place and only the outermost clears it.
         */
        private String emitInvocationStateSave(FunctionId callee, OpId invocation) {
            List<String> keys = bodyStateKeys(callee);
            if (keys.isEmpty()) {
                return null;
            }
            long functionId = callee.id();
            out.append("__svStack[#__svStack + 1] = {__bodyActive[")
                .append(functionId).append("]}\n");
            // The frame's slots are filled in bounded chunks: one
            // multi-assignment carrying every key of a large body would
            // exceed LuaJIT's per-statement variable-name and
            // expression-complexity limits (the bytes corpus' allocation
            // and closure fixtures carry hundreds of slots per body).
            for (int start = 0; start < keys.size(); start += STATE_CHUNK) {
                int end = Math.min(start + STATE_CHUNK, keys.size());
                StringBuilder targets = new StringBuilder();
                StringBuilder values = new StringBuilder();
                for (int i = start; i < end; i++) {
                    if (i > start) {
                        targets.append(", ");
                        values.append(", ");
                    }
                    targets.append("__svStack[#__svStack][").append(i + 2)
                        .append("]");
                    values.append(keys.get(i));
                }
                out.append(targets).append(" = ").append(values).append("\n");
            }
            out.append("__bodyActive[").append(functionId).append("] = true\n");
            return "__svStack[#__svStack]";
        }

        /**
         * The matching unconditional restore and pop, on every success and
         * failure path: the body's own slots and cell references return to
         * the enclosing invocation's state whichever route started it.
         */
        private void emitInvocationStateRestore(FunctionId callee, String frame) {
            if (frame == null) {
                return;
            }
            List<String> keys = bodyStateKeys(callee);
            for (int start = 0; start < keys.size(); start += STATE_CHUNK) {
                int end = Math.min(start + STATE_CHUNK, keys.size());
                StringBuilder targets = new StringBuilder();
                StringBuilder values = new StringBuilder();
                for (int i = start; i < end; i++) {
                    if (i > start) {
                        targets.append(", ");
                        values.append(", ");
                    }
                    targets.append(keys.get(i));
                    values.append(frame).append("[").append(i + 2).append("]");
                }
                out.append(targets).append(" = ").append(values).append("\n");
            }
            out.append("__bodyActive[").append(callee.id()).append("] = ")
                .append(frame).append("[1]\n");
            out.append("__svStack[#__svStack] = nil\n");
        }

        /**
         * The async body-task invocation's private-state save. The frame it
         * pushes is restored <em>unconditionally</em> by {@link
         * #emitAsyncInvocationStateRestore}, exactly like the synchronous
         * arm's: the task's body executes at the enclosing invocation's
         * drain, and that enclosing invocation may have been started by a
         * callee this arm cannot name at compile time (a dynamic-dispatch
         * body task) or by an entry of another unit, so the active marker
         * cannot gate the restore — the copied values are the enclosing
         * invocation's private state whatever started it. The marker itself
         * is still maintained, so a re-entrant invocation leaves it in place
         * and only the outermost clears it.
         */
        private String emitAsyncInvocationStateSave(FunctionId callee, OpId invocation,
                                                    String pad) {
            List<String> keys = bodyStateKeys(callee);
            if (keys.isEmpty()) {
                return null;
            }
            long functionId = callee.id();
            out.append(pad).append("__svStack[#__svStack + 1] = {__bodyActive[")
                .append(functionId).append("]}\n");
            // The frame's slots are filled in bounded chunks: one
            // multi-assignment carrying every key of a large body would
            // exceed LuaJIT's per-statement variable-name and
            // expression-complexity limits (the bytes corpus' allocation
            // and closure fixtures carry hundreds of slots per body).
            for (int start = 0; start < keys.size(); start += STATE_CHUNK) {
                int end = Math.min(start + STATE_CHUNK, keys.size());
                StringBuilder targets = new StringBuilder();
                StringBuilder values = new StringBuilder();
                for (int i = start; i < end; i++) {
                    if (i > start) {
                        targets.append(", ");
                        values.append(", ");
                    }
                    targets.append("__svStack[#__svStack][").append(i + 2)
                        .append("]");
                    values.append(keys.get(i));
                }
                out.append(pad).append(targets).append(" = ").append(values)
                    .append("\n");
            }
            out.append(pad).append("__bodyActive[").append(functionId)
                .append("] = true\n");
            return "__svStack[#__svStack]";
        }

        /**
         * The matching unconditional restore and pop, on every success and
         * failure path of the async body task.
         */
        private void emitAsyncInvocationStateRestore(FunctionId callee, String frame,
                                                     String pad) {
            if (frame == null) {
                return;
            }
            List<String> keys = bodyStateKeys(callee);
            for (int start = 0; start < keys.size(); start += STATE_CHUNK) {
                int end = Math.min(start + STATE_CHUNK, keys.size());
                StringBuilder targets = new StringBuilder();
                StringBuilder values = new StringBuilder();
                for (int i = start; i < end; i++) {
                    if (i > start) {
                        targets.append(", ");
                        values.append(", ");
                    }
                    targets.append(keys.get(i));
                    values.append(frame).append("[").append(i + 2).append("]");
                }
                out.append(pad).append(targets).append(" = ").append(values)
                    .append("\n");
            }
            out.append(pad).append("__bodyActive[").append(callee.id()).append("] = ")
                .append(frame).append("[1]\n");
            out.append(pad).append("__svStack[#__svStack] = nil\n");
        }

        /**
         * The registered execution binding of one allocation identity. The
         * project session's units share one identity space (every semantic
         * id is globally unique within the closure), so the registration is
         * resolved across the closure's units: an {@code Indirect} callee
         * value of one unit may name an identity whose producing
         * {@code CLOSURE_NEW} sits in a dependency module (the same
         * resolution the semantic oracle's {@code bindingOf} performs).
         */
        private FunctionExecutionBinding bindingOfIdentity(ValueId identity) {
            for (LoweredModuleUnit moduleUnit : units.values()) {
                FunctionExecutionBinding found = moduleUnit.functionBindings().get(
                    new FunctionAllocationIdentity(identity.id()));
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        private FunctionExecutionBinding callBinding(KindPayload.CallPayload payload) {
            FunctionExecutionBinding binding = switch (payload.callee()) {
                case KindPayload.CallCallee.Static staticCallee -> staticCallee.binding();
                case KindPayload.CallCallee.Indirect indirect ->
                    bindingOfIdentity(indirect.callee());
                case KindPayload.CallCallee.Dynamic ignored -> null;
            };
            if (binding == null
                    && !(payload.callee() instanceof KindPayload.CallCallee.Dynamic)) {
                throw new IllegalStateException("CALL " + payload
                    + " resolves no FunctionExecutionBinding (producer defect)");
            }
            return binding;
        }

        private void emitHostCall(SemanticOp op, KindPayload.CallPayload payload,
                ModuleId hostModuleId, String exportName, boolean moduleEntry) {
            emitHostInvocation(op, payload,
                "__exportSurfaces[" + luaString(hostModuleId.path()) + "]["
                    + luaString(exportName) + "]",
                StdlibFunctionCatalog.lookup(hostModuleId.path(), exportName).orElse(null));
        }

        /**
         * The {@code HostFunctionValue} indirect call arm: the value
         * materialized at its producing host crossing is invoked through
         * the host-facing wrapper the crossing published — the loaded
         * surface's own calling convention ({@code .f} with the span
         * triplet), so the declared cells of the materialized function
         * type run exactly as the direct host call's do.
         */
        private void emitHostValueCall(SemanticOp op, KindPayload.CallPayload payload,
                FunctionExecutionBinding.HostFunctionValue hostValue) {
            SemanticOp crossing = opsById.get(hostValue.materializingBoundaryOpId());
            if (crossing == null
                    || !(crossing.payload() instanceof KindPayload.BoundaryPayload boundary)) {
                throw new IllegalStateException("CALL " + op.opId()
                    + " names the materializing host crossing "
                    + hostValue.materializingBoundaryOpId()
                    + ", which is not a boundary op of the emitted closure"
                    + " (a producer defect)");
            }
            emitHostInvocation(op, payload, slot(boundary.input()));
        }

        /**
         * The invocation half of one host arm: the declared parameter
         * cells in one-based order (the loaded wrapper's own parameter
         * rule, so the pinned E8010 {@code parameter {i} type mismatch}
         * texts surface at the call origin), the host-facing projection of
         * each checked parameter (H7), the {@code .f} call with the span
         * triplet, and the declared return cell — every boundary child's
         * trace events (START, SUCCESS, FAILURE with the pinned projection)
         * surround the checks so the differential comparison against the
         * oracle stays event-for-event; production mode suppresses them
         * ({@code __ev} is a no-op).
         */
        private void emitHostInvocation(SemanticOp op, KindPayload.CallPayload payload,
                String target) {
            emitHostInvocation(op, payload, target, null);
        }

        private void emitHostInvocation(SemanticOp op, KindPayload.CallPayload payload,
                String target, StdlibFunctionCatalog.Entry catalogRow) {
            int argCount = emitHostParameterCells(op, payload);
            SemanticOp returnBoundary = payload.returnBoundaryOpId() == null
                ? null : opsById.get(payload.returnBoundaryOpId());
            if (catalogRow == null) {
                emitHostInvocationTail(op, target, argCount, returnBoundary);
            } else {
                emitStdlibCalleeInvocation(op, payload, target, catalogRow, argCount,
                    returnBoundary);
            }
            String result = slot((ValueId) op.result());
            out.append(result).append(" = __resT\n");
            emitResultSuccess(op, result, (RuntimeDescriptor) op.resultType());
        }

        /**
         * The recorded host parameter cells of one host-shaped call (the
         * {@code DEAL_TO_HOST} + {@code HOST_PARAMETER} family): the loaded
         * wrapper's own parameter rule runs per declared position in one-based
         * order, so the pinned E8010 {@code parameter {i} type mismatch} texts
         * surface at the call origin, and each host-facing projection (H7)
         * lands in {@code __hbT[i]}. Returns the position count.
         */
        private int emitHostParameterCells(SemanticOp op, KindPayload.CallPayload payload) {
            out.append("__hbT = {}\n");
            int index = 1;
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                SemanticOp boundary = opsById.get(boundaryId);
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                String input = slot(boundaryPayload.input());
                String desc = boundaryPayload.descriptor().canonicalSpecText();
                String origin = luaString(originOf(boundary));
                emitBoundaryStart(boundary, input, boundaryPayload.descriptor());
                if (isFunctionPosition(boundaryPayload.descriptor())) {
                    out.append("__okB, __chkB = pcall(__hostFnParam, ")
                        .append(luaString(desc)).append(", ")
                        .append(luaString(innerFunctionText(boundaryPayload.descriptor())))
                        .append(", ").append(index).append(", ").append(input)
                        .append(", ").append(origin).append(")\n");
                } else {
                    out.append("__okB, __chkB = pcall(__hostParamCell, ")
                        .append(luaString(desc)).append(", ").append(index)
                        .append(", ").append(input).append(", ").append(origin)
                        .append(")\n");
                }
                out.append("if not __okB then\n");
                emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                    "__errtext(__chkB)");
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "__errtext(__chkB)");
                out.append("  error(__chkB, 0)\n");
                out.append("end\n");
                emitBoundarySuccessAtom(boundary, "__hostCellAtom("
                    + luaString(staticKind(boundaryPayload.descriptor()))
                    + ", __chkB)");
                out.append("__hbT[").append(index).append("] = __hostProjectArg(")
                    .append(luaString(desc)).append(", ")
                    .append(sourceTextOf(boundaryPayload.descriptor())).append(", __chkB)\n");
                index++;
            }
            return index - 1;
        }

        private void emitIntrinsicValueCall(SemanticOp op, KindPayload.CallPayload payload,
                FunctionExecutionBinding.IntrinsicFunction intrinsic) {
            int argCount = emitHostParameterCells(op, payload);
            if (argCount != intrinsic.descriptor().paramTypes().size()) {
                throw new IllegalStateException("the conversion intrinsic call "
                    + op.opId() + " records " + argCount + " parameter cell(s) for the '"
                    + intrinsic.kind() + "' intrinsic's "
                    + intrinsic.descriptor().paramTypes().size() + " declared "
                    + "parameter(s) (a producer defect)");
            }
            String helper = intrinsicHelper(intrinsic.kind());
            out.append("__resT = ").append(helper).append("(__hbT[1], ")
                .append(intrinsicInvocationArgs(op, intrinsic.kind())).append(")\n");
            SemanticOp returnBoundary = payload.returnBoundaryOpId() == null
                ? null : opsById.get(payload.returnBoundaryOpId());
            // The produced value is a plain chunk value (a DEAL
            // int/number/bytes, never a host carrier), admitted by the same
            // general check the direct arm's and the cataloged callable's
            // recorded cells run.
            emitHostReturnCellRun(op, returnBoundary, "__atom");
            String result = slot((ValueId) op.result());
            out.append(result).append(" = __resT\n");
            emitResultSuccess(op, result, (RuntimeDescriptor) op.resultType());
        }

        private IntrinsicKind adapterIntrinsicKind(
                FunctionExecutionBinding.AdapterBinding adapter) {
            if (!(adapter.sourceRef() instanceof AdaptSourceRef.Value value)) {
                return null;
            }
            return intrinsicKindOf(value.value());
        }

        /** The emitted helper of one intrinsic kind's invocation ladder. */
        private static String intrinsicHelper(IntrinsicKind kind) {
            return switch (kind) {
                case INT_CONVERT -> "__intConv";
                case NUMBER_CONVERT -> "__numConv";
                case BYTES_NEW -> "__bytesNew";
            };
        }

        /**
         * The trailing argument list of one intrinsic invocation: the
         * invoking op's kind label, the declared parameter's static kind
         * for a conversion, and the op key, the contract digest, the
         * parent key, and the origin — plus the source span triplet of
         * the bytes allocation's runtime entry. The invocation's declared
         * signature is the only descriptor source (never a call site's or
         * an adapter target's).
         */
        private String intrinsicInvocationArgs(SemanticOp op, IntrinsicKind kind) {
            StringBuilder args = new StringBuilder();
            args.append(luaString(op.kind().name())).append(", ");
            if (kind != IntrinsicKind.BYTES_NEW) {
                args.append(luaString(staticKind(
                    kind.declaredSignature().paramTypes().get(0)))).append(", ");
            }
            args.append(luaString(opKey(op.opId()))).append(", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                .append(luaString(originOf(op)));
            if (kind == IntrinsicKind.BYTES_NEW) {
                args.append(", ").append(spanTripletArgs(op));
            }
            return args.toString();
        }

        private void emitIntrinsicLadder(SemanticOp op, IntrinsicKind kind, String input,
                                         String indent) {
            out.append(indent).append("__resT = ").append(intrinsicHelper(kind))
                .append("(").append(input).append(", ")
                .append(intrinsicInvocationArgs(op, kind)).append(")\n");
        }

        private void emitIntrinsicLadderPcall(SemanticOp op, IntrinsicKind kind,
                                              String input, String indent) {
            out.append(indent).append("__okA, __resA = pcall(")
                .append(intrinsicHelper(kind)).append(", ").append(input).append(", ")
                .append(intrinsicInvocationArgs(op, kind)).append(")\n");
        }

        private void emitAdapterOverIntrinsicRun(SemanticOp op,
                KindPayload.CallPayload payload,
                FunctionExecutionBinding.AdapterBinding adapter) {
            IntrinsicKind kind = adapterIntrinsicKind(adapter);
            if (kind == null) {
                throw new IllegalStateException("CALL " + op.opId()
                    + " records an adapter whose source is not the seeded intrinsic "
                    + "identity (producer defect)");
            }
            RuntimeDescriptor.Func declared = kind.declaredSignature();
            String adapterSlot = slot((ValueId) opsById.get(adapter.adaptOpId()).result());
            String origin = originOf(op);
            out.append("__dynS = __adaptSource(").append(adapterSlot).append(")\n");
            out.append("__okB, __chkB = pcall(__fncheck, __dynS, ")
                .append(luaString(adapter.sourceSignature().canonicalSpecText()))
                .append(", ").append(luaString(origin)).append(")\n");
            out.append("if not __okB then\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__chkB)");
            out.append("  error(__chkB, 0)\n");
            out.append("end\n");
            int m = adapter.sourceSignature().paramTypes().size();
            if (m != declared.paramTypes().size()
                    || m > payload.parameterBoundaryOpIds().size()) {
                throw new IllegalStateException("the adapter-over-intrinsic call "
                    + op.opId() + " projects " + m + " leading source argument(s) "
                    + "onto the '" + kind + "' intrinsic's "
                    + declared.paramTypes().size() + " declared parameter(s) with "
                    + payload.parameterBoundaryOpIds().size()
                    + " recorded target cell(s) (producer defect)");
            }
            SemanticOp leading = opsById.get(payload.parameterBoundaryOpIds().get(0));
            String input = slot(((KindPayload.BoundaryPayload) leading.payload()).input());
            emitIntrinsicLadder(op, kind, input, "");
            SemanticOp returnBoundary = payload.returnBoundaryOpId() == null
                ? null : opsById.get(payload.returnBoundaryOpId());
            emitHostReturnCellRun(op, returnBoundary, "__atom");
        }

        /**
         * The cataloged-callable sub-class of one host-shaped call: the read's
         * own registration resolved the closed catalog row, so the invocation is
         * the row's one invoker with the invoking call's own context — never the
         * loaded surface entry's {@code .f} — and the recorded
         * {@code HOST_TO_DEAL} + {@code HOST_SYNC_RETURN} cell runs at the call
         * origin, exactly the direct {@code STDLIB_CALL} arm's observables and the
         * oracle's cataloged branch of {@code invokeResolvedHostRequest}.
         *
         * <p>The argument domain is the recorded {@code HOST_PARAMETER} cells'
         * host-facing projection: the closed catalog rows declare non-nullable
         * scalar/table parameter positions only, for which that projection is the
         * identity — a nullable, function, or class position is a fail-closed
         * producer defect, never a silently projected argument.</p>
         *
         */
        private void emitStdlibCalleeInvocation(SemanticOp op,
                KindPayload.CallPayload payload, String target,
                StdlibFunctionCatalog.Entry row, int argCount, SemanticOp returnBoundary) {
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                RuntimeDescriptor descriptor = ((KindPayload.BoundaryPayload)
                    opsById.get(boundaryId).payload()).descriptor();
                if (isFunctionPosition(descriptor)
                        || descriptor instanceof RuntimeDescriptor.Class
                        || descriptor instanceof RuntimeDescriptor.Nullable) {
                    throw new IllegalStateException("the cataloged callable '"
                        + row.declaredDescriptor().canonicalSpecText() + "' arrives at "
                        + op.opId() + " with the parameter position "
                        + descriptor.canonicalSpecText() + ", whose host-facing "
                        + "projection is not the identity — a producer defect");
                }
            }
            out.append("if ").append(target).append(" ~= nil and ").append(target)
                .append(".__sid ~= nil then\n");
            out.append("  __resT = ").append(target).append(".__fn(")
                .append(luaString(op.kind().name())).append(", ")
                .append(luaString(opKey(op.opId()))).append(", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                .append(luaString(originOf(op)));
            for (int i = 1; i <= argCount; i++) {
                out.append(", __hbT[").append(i).append("]");
            }
            out.append(")\n");
            // The recorded cell's admission: the cataloged callable's result is a
            // plain chunk value, admitted by the same general check the direct
            // STDLIB_CALL arm's STDLIB_RETURN cell runs (its failures keep the
            // descriptor-kind projection), so a null-typed row's Lua nil is the
            // language null exactly as the oracle's NullValue is.
            emitHostReturnCellRun(op, returnBoundary, "__atom");
            out.append("else\n");
            emitHostInvocationTail(op, target, argCount, returnBoundary);
            out.append("end\n");
        }

        private void emitHostInvocationTail(SemanticOp op, String target, int argCount,
                                            SemanticOp returnBoundary) {
            String declaredReturn = returnBoundary == null ? null
                : ((KindPayload.BoundaryPayload) returnBoundary.payload())
                    .descriptor().canonicalSpecText();
            // The converged host-boundary call shape: the trailing literal
            // span triplet is how a boundary error reports the DEAL call
            // site byte-exact.
            out.append("__okT, __resT = pcall(").append(target).append(".f");
            for (int i = 1; i <= argCount; i++) {
                out.append(", __hbT[").append(i).append("]");
            }
            out.append(", ").append(spanTripletArgs(op)).append(")\n");
            out.append("if not __okT then\n");
            out.append("  __resT = __hostError(__resT, ")
                .append(luaString(originOf(op))).append(")\n");
            if (returnBoundary != null) {
                // The loaded wrapper runs the declared return rule itself,
                // so its return-cell failure surfaces inside the pcall:
                // the boundary child's FAILURE event carries the wrapper's
                // error, exactly the JVM arm's wrapper-error path.
                out.append("  if type(__resT) == \"table\" and __resT.code == \"E8010\""
                    + " and type(__resT.m) == \"string\" and string.find(__resT.m,"
                    + " \"return value 1 type mismatch\", 1, true) == 1 then\n");
                emitFailureEvent(returnBoundary.opId(), "BOUNDARY", returnBoundary,
                    "__errtext(__resT)");
                out.append("  end\n");
            }
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__resT)");
            out.append("  error(__resT, 0)\n");
            out.append("end\n");
            emitHostReturnCellRun(op, returnBoundary, "__hostCellAtom");
        }

        private void emitHostReturnCellRun(SemanticOp op, SemanticOp returnBoundary,
                                           String atomizer) {
            if (returnBoundary == null) {
                return;
            }
            boolean hostValue = "__hostCellAtom".equals(atomizer);
            KindPayload.BoundaryPayload cellPayload =
                (KindPayload.BoundaryPayload) returnBoundary.payload();
            String declaredReturn = cellPayload.descriptor().canonicalSpecText();
            // The class-value carrier projection (F4): on a class-typed
            // return the wrapper's loaded value is projected into the
            // chunk's class representation *before* the crossing's
            // events, so the boundary's START/SUCCESS atoms and the
            // program observe the one projected heap value — never the
            // wrapper's raw runtime value (the trace-identity rule).
            // A cataloged stdlib result is a plain chunk value: no
            // projection applies.
            if (hostValue && cellPayload.descriptor() instanceof RuntimeDescriptor.Class) {
                out.append("__resT = __hostDealProject(")
                    .append(luaString(declaredReturn)).append(", __resT, ")
                    .append(luaString(originOf(returnBoundary))).append(")\n");
            }
            // The boundary events' atoms are the DEAL-null-aware host
            // atom for a loaded host surface value (the deployed
            // runtime's null sentinel is the language null, so a null
            // return atomizes as "null" exactly like the oracle's
            // NullValue) and the general value atom for the cataloged
            // stdlib callable's result (whose session emits no host
            // helper).
            emitBoundaryStartAtom(returnBoundary, atomizer + "("
                + luaString(staticKind(cellPayload.descriptor())) + ", __resT)");
            if (hostValue) {
                out.append("__okB, __chkB = pcall(__hostReturnCell, ")
                    .append(luaString(declaredReturn)).append(", __resT, ")
                    .append(luaString(originOf(returnBoundary))).append(", false)\n");
            } else {
                out.append("__okB, __chkB = pcall(__bcheck, ")
                    .append(luaString(declaredReturn)).append(", ")
                    .append(luaString(staticKind(cellPayload.descriptor())))
                    .append(", __resT)\n");
            }
            out.append("if not __okB then\n");
            if (!hostValue) {
                out.append("  __chkB.o = ")
                    .append(luaString(originOf(returnBoundary))).append("\n");
            }
            emitFailureEvent(returnBoundary.opId(), "BOUNDARY", returnBoundary,
                "__errtext(__chkB)");
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "__errtext(__chkB)");
            out.append("  error(__chkB, 0)\n");
            out.append("end\n");
            emitBoundarySuccessAtom(returnBoundary, atomizer + "("
                + luaString(staticKind(cellPayload.descriptor())) + ", __resT)");
            out.append("__resT = __chkB\n");
        }

        private void emitDynamicCall(SemanticOp op, KindPayload.CallPayload payload,
                                     KindPayload.CallCallee.Dynamic callee) {
            KindPayload.DynamicReturnBoundary cells = payload.dynamicReturnBoundary();
            if (cells == null) {
                throw new IllegalStateException("the dynamic CALL " + op.opId()
                    + " records no DynamicReturnBoundary cell set (producer defect)");
            }
            SemanticOp hostCell = opsById.get(cells.hostBoundaryOpId());
            if (hostCell == null
                    || !(hostCell.payload() instanceof KindPayload.BoundaryPayload boundary)
                    || boundary.kind() != BoundaryKind.HOST_TO_DEAL) {
                throw new IllegalStateException("the dynamic CALL " + op.opId()
                    + " records the host return cell " + cells.hostBoundaryOpId()
                    + ", which is not a HOST_TO_DEAL boundary of the emitted closure"
                    + " (producer defect)");
            }
            String origin = luaString(originOf(op));

            SemanticOp recordedDealCell = opsById.get(cells.dealBodyBoundaryOpId());
            boolean callOwnedDealCell = recordedDealCell != null
                && callOwnedCell(recordedDealCell);
            StringBuilder argList = new StringBuilder();
            StringBuilder argTable = new StringBuilder();
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                SemanticOp parameter = opsById.get(boundaryId);
                String input = slot(((KindPayload.BoundaryPayload) parameter.payload())
                    .input());
                argList.append(", ").append(input);
                if (argTable.length() > 0) {
                    argTable.append(", ");
                }
                argTable.append(input);
            }
            out.append("__dynC = ").append(slot(callee.callee())).append("\n");
            out.append("__dynK = __dynClass(__dynC)\n");
            // DEAL_BODY: the carrier's function id resolves its owning
            // module; the frame and the module context are restored on
            // every path.
            out.append("if __dynK == \"DEAL_BODY\" then\n");
            out.append("  __dynM = __fnModules[__dynC.__fid]\n");
            out.append("  if __dynM == nil then\n");
            emitDynamicCarrierFailure(op, origin);
            out.append("  end\n");
            out.append("  __modStack[#__modStack + 1] = __module\n");
            out.append("  __module = __dynM\n");
            out.append("  table.insert(__frames, 1, tostring(__dynC.__fid))\n");
            // The invoking dynamic call's origin: a failure inside the
            // callee body whose arm renders the call origin (the
            // @jsonable toJson walk) reads the innermost active call —
            // the f(w) invocation, never an enclosing static call.
            out.append("  __callOrigins[#__callOrigins + 1] = ").append(origin)
                .append("\n");
            out.append("  __okT, __resT = pcall(__unfn(__dynC)").append(argList)
                .append(")\n");
            out.append("  __callOrigins[#__callOrigins] = nil\n");
            out.append("  table.remove(__frames, 1)\n");
            out.append("  __module = __modStack[#__modStack]\n");
            out.append("  __modStack[#__modStack] = nil\n");
            out.append("  if not __okT then\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__resT)");
            out.append("    error(__resT, 0)\n");
            out.append("  end\n");
            if (callOwnedDealCell) {
                emitRecordedCellRun(op, recordedDealCell, "__resT", "  ");
            }

            out.append("elseif __dynK == \"ADAPTER\" then\n");
            out.append("  __dynS = __adaptSource(__dynC)\n");
            out.append("  __okB, __chkB = pcall(__fncheck, __dynS, __dynC.__csrc, ")
                .append(origin).append(")\n");
            out.append("  if not __okB then\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__chkB)");
            out.append("    error(__chkB, 0)\n");
            out.append("  end\n");
            out.append("  __dynS = __chkB\n");
            out.append("  __dynSK = __dynClass(__dynS)\n");
            out.append("  if __dynSK == \"DEAL_BODY\" then\n");
            out.append("    __dynM = __fnModules[__dynS.__fid]\n");
            out.append("    if __dynM == nil then\n");
            out.append("      __dynC = __dynS\n");
            emitDynamicCarrierFailure(op, origin);
            out.append("    end\n");
            out.append("    __modStack[#__modStack + 1] = __module\n");
            out.append("    __module = __dynM\n");
            out.append("    table.insert(__frames, 1, tostring(__dynS.__fid))\n");
            out.append("    __dynA = {").append(argTable).append("}\n");
            // The invoking dynamic call's origin, as in the direct
            // DEAL_BODY row: the innermost active call is the f(w)
            // invocation.
            out.append("    __callOrigins[#__callOrigins + 1] = ").append(origin)
                .append("\n");
            out.append("    __okT, __resT = pcall(__unfn(__dynS), unpack(__dynA, 1, ")
                .append("__dynC.__m))\n");
            out.append("    __callOrigins[#__callOrigins] = nil\n");
            out.append("    table.remove(__frames, 1)\n");
            out.append("    __module = __modStack[#__modStack]\n");
            out.append("    __modStack[#__modStack] = nil\n");
            out.append("    if not __okT then\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__resT)");
            out.append("      error(__resT, 0)\n");
            out.append("    end\n");
            if (callOwnedDealCell) {
                emitRecordedCellRun(op, recordedDealCell, "__resT", "    ");
            }

            out.append("  elseif __dynS.__it ~= nil then\n");
            if (payload.parameterBoundaryOpIds().isEmpty()) {
                // The leading-M projection of the runtime intrinsic source has
                // no argument to convert (the intrinsic declares one
                // parameter): the carrier fails closed at the call origin.
                out.append("    __dynC = __dynS\n");
                emitDynamicCarrierFailure(op, origin);
            } else {
                SemanticOp dynamicLeading =
                    opsById.get(payload.parameterBoundaryOpIds().get(0));
                String dynamicInput =
                    slot(((KindPayload.BoundaryPayload) dynamicLeading.payload()).input());
                out.append("    if __dynS.__it == \"INT_CONVERT\" then\n");
                emitIntrinsicLadder(op, IntrinsicKind.INT_CONVERT, dynamicInput,
                    "      ");
                out.append("    elseif __dynS.__it == \"NUMBER_CONVERT\" then\n");
                emitIntrinsicLadder(op, IntrinsicKind.NUMBER_CONVERT, dynamicInput,
                    "      ");
                out.append("    elseif __dynS.__it == \"BYTES_NEW\" then\n");
                emitIntrinsicLadder(op, IntrinsicKind.BYTES_NEW, dynamicInput,
                    "      ");
                out.append("    else\n");
                out.append("      __dynC = __dynS\n");
                emitDynamicCarrierFailure(op, origin);
                out.append("    end\n");
                emitHostReturnCellRun(op, hostCell, "__atom");
            }
            out.append("  else\n");
            out.append("    __dynC = __dynS\n");
            emitDynamicCarrierFailure(op, origin);
            out.append("  end\n");
            // HOST: the loaded surface entry (the carrier itself) and the
            // recorded HOST_TO_DEAL + HOST_SYNC_RETURN cell.
            out.append("elseif __dynK == \"HOST\" then\n");
            emitDynamicHostRow(op, payload, hostCell);
            // The fail-closed residue: no class is resolvable.
            out.append("else\n");
            emitDynamicCarrierFailure(op, origin);
            out.append("end\n");
        }

        private void emitDynamicHostRow(SemanticOp op, KindPayload.CallPayload payload,
                                        SemanticOp returnBoundary) {

            if (payload.parameterBoundaryOpIds().size() == 1) {
                SemanticOp parameter = opsById.get(payload.parameterBoundaryOpIds().get(0));
                KindPayload.BoundaryPayload parameterPayload =
                    (KindPayload.BoundaryPayload) parameter.payload();
                String input = slot(parameterPayload.input());
                String declaredKind = luaString(staticKind(parameterPayload.descriptor()));
                out.append("  if __dynC.__it == \"INT_CONVERT\" then\n");
                out.append("    __resT = __intConv(").append(input)
                    .append(", ").append(luaString(op.kind().name())).append(", ")
                    .append(declaredKind).append(", ")
                    .append(luaString(opKey(op.opId()))).append(", ")
                    .append(luaString(op.contract().canonicalDigest())).append(", ")
                    .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(luaString(originOf(op))).append(")\n");
                emitHostReturnCellRun(op, returnBoundary, "__atom");
                out.append("  elseif __dynC.__it == \"NUMBER_CONVERT\" then\n");
                emitIntrinsicLadder(op, IntrinsicKind.NUMBER_CONVERT, input, "  ");
                emitHostReturnCellRun(op, returnBoundary, "__atom");
                out.append("  elseif __dynC.__it == \"BYTES_NEW\" then\n");
                emitIntrinsicLadder(op, IntrinsicKind.BYTES_NEW, input, "  ");
                emitHostReturnCellRun(op, returnBoundary, "__atom");
                out.append("  elseif __dynC.__sid ~= nil then\n");
            } else {
                // No checker-valid program reaches an intrinsic carrier at a
                // site without the intrinsic's single declared parameter cell;
                // the tag still fails closed rather than guessing a conversion.
                out.append("  if __dynC.__it ~= nil then\n");
                emitDynamicCarrierFailure(op, luaString(originOf(op)));
                out.append("  elseif __dynC.__sid ~= nil then\n");
            }
            out.append("    __resT = __dynC.__fn(")
                .append(luaString(op.kind().name())).append(", ")
                .append(luaString(opKey(op.opId()))).append(", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                .append(luaString(originOf(op)));
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                SemanticOp parameter = opsById.get(boundaryId);
                out.append(", ").append(slot(((KindPayload.BoundaryPayload)
                    parameter.payload()).input()));
            }
            out.append(")\n");
            emitHostReturnCellRun(op, returnBoundary, "__atom");
            out.append("  else\n");
            out.append("  __hbT = {}\n");
            int index = 1;
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                SemanticOp parameter = opsById.get(boundaryId);
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) parameter.payload();
                out.append("  __hbT[").append(index).append("] = __hostProjectArg(")
                    .append(luaString(boundaryPayload.descriptor().canonicalSpecText()))
                    .append(", ")
                    .append(sourceTextOf(boundaryPayload.descriptor())).append(", ")
                    .append(slot(boundaryPayload.input())).append(")\n");
                index++;
            }
            emitHostInvocationTail(op, "__dynC", index - 1, returnBoundary);
            out.append("  end\n");
        }

        private void emitRecordedCellRun(SemanticOp invocation, SemanticOp cell,
                                         String valueSlot, String indent) {
            KindPayload.BoundaryPayload payload =
                (KindPayload.BoundaryPayload) cell.payload();
            // The START carries the input's atom (the oracle's
            // runBoundaryChild START), the check runs under the cell's own
            // origin, and the SUCCESS terminal publishes the admitted value.
            out.append(indent).append("__ev(").append(luaString(opKey(cell.opId())))
                .append(", \"START\", \"BOUNDARY\", ")
                .append(luaString(cell.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(cell.origin().parentOpId())))
                .append(", {__atom(")
                .append(luaString(staticKind(payload.descriptor()))).append(", ")
                .append(valueSlot).append(")}, nil, nil)\n");
            out.append(indent).append("__okD, __chkD = pcall(__bcheck, ")
                .append(bcheckArgs(payload.descriptor(), valueSlot)).append(")\n");
            out.append(indent).append("if not __okD then\n");
            out.append(indent).append("  __chkD.o = ")
                .append(luaString(originOf(cell))).append("\n");
            emitFailureEvent(cell.opId(), "BOUNDARY", cell, "__errtext(__chkD)");
            emitFailureEvent(invocation.opId(), invocation.kind().name(), invocation,
                "__errtext(__chkD)");
            out.append(indent).append("  error(__chkD, 0)\n");
            out.append(indent).append("end\n");
            out.append(indent).append(valueSlot).append(" = __chkD\n");
            out.append(indent).append("__ev(").append(luaString(opKey(cell.opId())))
                .append(", \"SUCCESS\", \"BOUNDARY\", ")
                .append(luaString(cell.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(cell.origin().parentOpId())))
                .append(", {}, __atom(")
                .append(luaString(staticKind(payload.descriptor()))).append(", ")
                .append(valueSlot).append("), nil)\n");
        }

        /**
         * The dynamic dispatch's fail-closed residue: a carrier whose
         * class is not resolvable at this boundary projects the pinned
         * E8001 {@code expected function} text with the carrier's actual
         * runtime kind at the call origin, emits the op's single FAILURE
         * terminal, and raises — nothing executes silently.
         */
        private void emitDynamicCarrierFailure(SemanticOp op, String origin) {
            out.append("__dynE = __arm(\"TYPED_BOUNDARY_KIND\", {kind = \"function\"}, ")
                .append(origin)
                .append(", \"function\", __typedBoundaryKind(\"function\", __dynC))\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__dynE)");
            out.append("error(__dynE, 0)\n");
        }

        private void emitDynamicAsyncStart(SemanticOp op,
                                           KindPayload.AsyncStartPayload payload,
                                           KindPayload.CallCallee.Dynamic callee,
                                           AsyncTokenId token) {
            String origin = luaString(originOf(op));
            String carrierArgs = "S.__sa" + op.opId().id();

            SemanticOp recordedTaskCell = payload.returnBoundaryOpId() == null
                ? null : opsById.get(payload.returnBoundaryOpId());
            boolean callOwnedTaskCell = recordedTaskCell != null
                && callOwnedCell(recordedTaskCell);
            out.append("__dynC = ").append(slot(callee.callee())).append("\n");
            out.append("__dynK = __dynClass(__dynC)\n");
            // DEAL_BODY: the carrier's function id resolves its owning
            // module; the frame and the module context are restored on
            // every path.
            out.append("if __dynK == \"DEAL_BODY\" then\n");
            out.append("  __dynM = __fnModules[__dynC.__fid]\n");
            out.append("  if __dynM == nil then\n");
            emitDynamicCarrierFailure(op, origin);
            out.append("  end\n");
            out.append("  __asyncStartTask(").append(token.tokenId())
                .append(", \"DEAL_BODY_TASK\", coroutine.create(function()\n");
            out.append("    __modStack[#__modStack + 1] = __module\n");
            out.append("    __module = __dynM\n");
            out.append("    table.insert(__frames, 1, tostring(__dynC.__fid))\n");
            out.append("    local __okA, __resA = pcall(__unfn(__dynC), unpack(")
                .append(carrierArgs).append(", 1, #").append(carrierArgs)
                .append("))\n");
            out.append("    table.remove(__frames, 1)\n");
            out.append("    __module = __modStack[#__modStack]\n");
            out.append("    __modStack[#__modStack] = nil\n");
            out.append("    if not __okA then error(__resA, 0) end\n");
            if (callOwnedTaskCell) {
                emitRecordedCellRun(op, recordedTaskCell, "__resA", "    ");
            }
            out.append("    return __resA\n");
            out.append("  end), ").append(carrierArgs).append(")\n");

            out.append("elseif __dynK == \"ADAPTER\" then\n");
            out.append("  __dynS = __adaptSource(__dynC)\n");
            out.append("  __okB, __chkB = pcall(__fncheck, __dynS, __dynC.__csrc, ")
                .append(origin).append(")\n");
            out.append("  if not __okB then\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__chkB)");
            out.append("    error(__chkB, 0)\n");
            out.append("  end\n");
            out.append("  __dynS = __chkB\n");

            out.append("  if __dynS.__it ~= nil then\n");
            if (recordedArgCount(payload, op) == 0) {
                // The leading-M projection of the runtime intrinsic source has
                // no argument to convert (the intrinsic declares one
                // parameter): the carrier fails closed at the start origin.
                out.append("    __dynC = __dynS\n");
                emitDynamicCarrierFailure(op, origin);
            } else {
            out.append("    __asyncStartTask(").append(token.tokenId())
                .append(", \"DEAL_BODY_TASK\", coroutine.create(function()\n");
            out.append("      local __okA, __resA\n");
            out.append("      if __dynS.__it == \"INT_CONVERT\" then\n");
            emitIntrinsicLadderPcall(op, IntrinsicKind.INT_CONVERT,
                carrierArgs + "[1]", "        ");
            out.append("      else\n");
            emitIntrinsicLadderPcall(op, IntrinsicKind.NUMBER_CONVERT,
                carrierArgs + "[1]", "        ");
            out.append("      end\n");
            out.append("      if not __okA then error(__resA, 0) end\n");
            out.append("      return __resA\n");
            out.append("    end), ")
                .append(carrierArgs).append(")\n");
            }
            out.append("  elseif __dynClass(__dynS) ~= \"DEAL_BODY\" then\n");
            out.append("    __dynC = __dynS\n");
            emitDynamicCarrierFailure(op, origin);
            out.append("  else\n");
            out.append("  __dynM = __fnModules[__dynS.__fid]\n");
            out.append("  if __dynM == nil then\n");
            out.append("    __dynC = __dynS\n");
            emitDynamicCarrierFailure(op, origin);
            out.append("  end\n");
            out.append("  __asyncStartTask(").append(token.tokenId())
                .append(", \"DEAL_BODY_TASK\", coroutine.create(function()\n");
            out.append("    __modStack[#__modStack + 1] = __module\n");
            out.append("    __module = __dynM\n");
            out.append("    table.insert(__frames, 1, tostring(__dynS.__fid))\n");
            out.append("    local __okA, __resA = pcall(__unfn(__dynS), unpack(")
                .append(carrierArgs).append(", 1, __dynC.__m))\n");
            out.append("    table.remove(__frames, 1)\n");
            out.append("    __module = __modStack[#__modStack]\n");
            out.append("    __modStack[#__modStack] = nil\n");
            out.append("    if not __okA then error(__resA, 0) end\n");
            if (callOwnedTaskCell) {
                emitRecordedCellRun(op, recordedTaskCell, "__resA", "    ");
            }
            out.append("    return __resA\n");
            out.append("  end), ").append(carrierArgs).append(")\n");
            out.append("  end\n");
            // HOST: the carrier is a loaded host surface entry, so the
            // declared host export's async start runs through the same host
            // calling convention and the same ASYNC_OPERATION_HANDLE terminal
            // as the static arm — the loaded wrapper's declared-async shape
            // check inside the pcall, the operation handle bound to this
            // token under the declared identity's operation label (the
            // oracle's dynamic HOST resolution: the loaded declared async
            // export, the responder's startAsync under the same label). The
            // declared identity is the carrier's own identity-indexed home in
            // the program's export-surface registry; a carrier with no unique
            // home identifies no loaded surface entry and fails closed.
            out.append("elseif __dynK == \"HOST\" then\n");
            out.append("  __dynHN = __surfaceNameOf(__dynC)\n");
            out.append("  if __dynHN == nil then\n");
            emitDynamicCarrierFailure(op, origin);
            out.append("  end\n");
            out.append("  __dynLbl = __dynHN[1]..\".\"..__dynHN[2]\n");
            if (trace) {
                out.append("  io.stderr:write(\"F|ASYNC_START_OP|-|\"..__esc(__dynLbl)"
                    + "..\"\\n\")\n");
                out.append("  io.stderr:flush()\n");
            }
            out.append("  __okH, __resH = pcall(__dynC.f");
            for (int i = 1; i <= recordedArgCount(payload, op); i++) {
                out.append(", ").append(carrierArgs).append("[").append(i).append("]");
            }
            out.append(", ").append(spanTripletArgs(op)).append(")\n");
            out.append("  if not __okH then\n");
            out.append("    __resH = __hostError(__resH, ").append(origin).append(")\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__resH)");
            out.append("    error(__resH, 0)\n");
            out.append("  end\n");
            out.append("  __asyncStartHost(").append(token.tokenId())
                .append(", __dynLbl, __resH)\n");
            // The fail-closed residue: no class is resolvable.
            out.append("else\n");
            emitDynamicCarrierFailure(op, origin);
            out.append("end\n");
        }

        /**
         * The completed argument count of one {@code ASYNC_START} payload: the
         * parameter boundaries under {@code RUN}, the raw operands under
         * {@code ELIDED_BY_ADAPTER} (the carrier table holds exactly them).
         */
        private static int recordedArgCount(KindPayload.AsyncStartPayload payload,
                                            SemanticOp op) {
            return payload.parameterBoundaryMode() == ParameterBoundaryMode.RUN
                ? payload.parameterBoundaryOpIds().size() : op.operands().size();
        }

        /** Whether one declared host position is a function type. */
        private boolean isFunctionPosition(RuntimeDescriptor descriptor) {
            RuntimeDescriptor inner = descriptor instanceof RuntimeDescriptor.Nullable nullable
                ? nullable.inner() : descriptor;
            return inner instanceof RuntimeDescriptor.Func;
        }

        /** The canonical descriptor text of one declared position's inner type. */
        private String innerFunctionText(RuntimeDescriptor descriptor) {
            RuntimeDescriptor inner = descriptor instanceof RuntimeDescriptor.Nullable nullable
                ? nullable.inner() : descriptor;
            return inner.canonicalSpecText();
        }

        /** The inner-type argument of the projection helper (nil for other positions). */
        private String sourceTextOf(RuntimeDescriptor descriptor) {
            return isFunctionPosition(descriptor)
                ? luaString(innerFunctionText(descriptor)) : "nil";
        }

        /** The literal span-triplet arguments of one host call site. */
        private String spanTripletArgs(SemanticOp op) {
            SourceSpan span = op.origin().span();
            if (span == null) {
                return "\"-\", 0, 0";
            }
            return luaString(op.origin().sourceId()) + ", " + span.startLine() + ", "
                + span.startColumn();
        }

        private void emitIntrinsic(SemanticOp op) {
            KindPayload.IntrinsicCallPayload payload =
                (KindPayload.IntrinsicCallPayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            String input = slot(payload.input());
            String kind = staticKind(op.operandTypes().get(0));
            String origin = originOf(op);

            switch (payload.kind()) {
                case INT_CONVERT -> out.append(target).append(" = __intConv(")
                    .append(input).append(", ").append(luaString(op.kind().name()))
                    .append(", ").append(luaString(kind)).append(", ")
                    .append(luaString(opKey(op.opId()))).append(", ")
                    .append(luaString(op.contract().canonicalDigest())).append(", ")
                    .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(luaString(origin)).append(")\n");
                case NUMBER_CONVERT -> out.append(target).append(" = __numConv(")
                    .append(input).append(", ").append(luaString(op.kind().name()))
                    .append(", ").append(luaString(kind)).append(", ")
                    .append(luaString(opKey(op.opId()))).append(", ")
                    .append(luaString(op.contract().canonicalDigest())).append(", ")
                    .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(luaString(origin)).append(")\n");
                case BYTES_NEW -> out.append(target).append(" = __bytesNew(")
                    .append(input).append(", ").append(luaString(op.kind().name()))
                    .append(", ")
                    .append(luaString(opKey(op.opId()))).append(", ")
                    .append(luaString(op.contract().canonicalDigest())).append(", ")
                    .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(luaString(origin)).append(", ")
                    .append(spanTripletArgs(op)).append(")\n");
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private void emitStdlib(SemanticOp op) {
            KindPayload.StdlibCallPayload payload =
                (KindPayload.StdlibCallPayload) op.payload();
            // The custom START: the operand atoms render the actual value
            // kind (the oracle's atomOf — a dynamic argument at a declared
            // boundary atomizes as its own kind), never the declared kind.
            out.append("__ev(").append(luaString(opKey(op.opId())))
                .append(", \"START\", \"STDLIB_CALL\", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId()))).append(", {");
            for (int i = 0; i < payload.args().size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append("__rawArgAtom(")
                    .append(luaString(staticKind(op.operandTypes().get(i))))
                    .append(", ").append(slot(payload.args().get(i))).append(")");
            }
            out.append("}, nil, nil)\n");
            // The closed STDLIB_PARAMETER boundaries in one-based declared
            // order: a failing boundary publishes the boundary FAILURE and
            // the op FAILURE events with the boundary origin, then rethrows
            // (the exact oracle event sequence — never a silent terminal).
            List<SemanticOp> paramBoundaries = stdlibParamBoundaries(op);
            for (SemanticOp boundary : paramBoundaries) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                // Custom boundary START: the input atom renders the actual
                // value kind (the oracle's atomOf).
                out.append("__ev(").append(luaString(opKey(boundary.opId())))
                    .append(", \"START\", \"BOUNDARY\", ")
                    .append(luaString(boundary.contract().canonicalDigest()))
                    .append(", ")
                    .append(luaString(parentKey(boundary.origin().parentOpId())))
                    .append(", {__rawArgAtom(")
                    .append(luaString(staticKind(boundaryPayload.descriptor())))
                    .append(", ").append(slot(boundaryPayload.input()))
                    .append(")}, nil, nil)\n");
                out.append("__okB, __chkB = pcall(__bcheck, ")
                    .append(luaString(descriptorText(boundaryPayload.descriptor())))
                    .append(", ")
                    .append(luaString(staticKind(boundaryPayload.descriptor())))
                    .append(", ").append(slot(boundaryPayload.input())).append(")\n");
                out.append("if not __okB then\n");
                out.append("  __chkB.o = ").append(luaString(originOf(boundary)))
                    .append("\n");
                emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                    "__errtext(__chkB)");
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "__errtext(__chkB)");
                out.append("  error(__chkB, 0)\n");
                out.append("end\n");
                emitBoundarySuccess(boundary, "__chkB", boundaryPayload.descriptor());
            }
            SemanticOp returnBoundary = stdlibReturnBoundary(op);
            String target = slot((ValueId) op.result());
            // Every row runs the one row invoker the cataloged callable does
            // (M4): the console rows' single-effect write is one
            // realization, never a second statement-level copy — the
            // result is the row invoker's null and the effect is exactly
            // one write on the row's channel with the direct arm's text
            // projection. The in-target stdlib algorithm runs over the
            // boundary-admitted carriers through the same invoker: an
            // algorithm failure publishes the op FAILURE event and raises
            // the exact closed projection at the call origin (the
            // __stdlib helper converts it through the fail-closed
            // pattern).
            out.append(target).append(" = __stdlibInvoke(")
                .append(luaString(op.kind().name())).append(", ")
                .append(luaString(payload.function().name())).append(", ")
                .append(luaString(opKey(op.opId()))).append(", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                .append(luaString(originOf(op)));
            for (ValueId arg : payload.args()) {
                out.append(", ").append(slot(arg));
            }
            out.append(")\n");
            if (returnBoundary != null) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) returnBoundary.payload();
                // Custom boundary START: the result atom renders the
                // actual value kind (the oracle's atomOf).
                out.append("__ev(").append(luaString(opKey(returnBoundary.opId())))
                    .append(", \"START\", \"BOUNDARY\", ")
                    .append(luaString(returnBoundary.contract().canonicalDigest()))
                    .append(", ")
                    .append(luaString(parentKey(returnBoundary.origin().parentOpId())))
                    .append(", {__rawArgAtom(")
                    .append(luaString(staticKind(boundaryPayload.descriptor())))
                    .append(", ").append(target)
                    .append(")}, nil, nil)\n");
                out.append("__okB, __chkB = pcall(__bcheck, ")
                    .append(luaString(descriptorText(boundaryPayload.descriptor())))
                    .append(", ")
                    .append(luaString(staticKind(boundaryPayload.descriptor())))
                    .append(", ").append(target).append(")\n");
                out.append("if not __okB then\n");
                out.append("  __chkB.o = ").append(luaString(originOf(returnBoundary)))
                    .append("\n");
                emitFailureEvent(returnBoundary.opId(), "BOUNDARY", returnBoundary,
                    "__errtext(__chkB)");
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "__errtext(__chkB)");
                out.append("  error(__chkB, 0)\n");
                out.append("end\n");
                out.append(target).append(" = __chkB\n");
                emitBoundarySuccess(returnBoundary, target, boundaryPayload.descriptor());
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private void emitClassNew(SemanticOp op) {
            KindPayload.ClassNewPayload payload =
                (KindPayload.ClassNewPayload) op.payload();
            emitStart(op);
            ClassLayout layout = classLayouts.get(payload.classId());
            if (layout == null && payload.defaultOwner() == DefaultOwner.BUILTIN_DEFAULTS) {

                layout = ClassLayout.BUILTIN_ERROR;
            }
            if (layout == null && payload.defaultOwner() == DefaultOwner.HOST_DEFAULTS) {

                layout = payload.layout();
            }
            if (layout == null && payload.defaultOwner() == DefaultOwner.FFI_PLAN) {

                layout = payload.layout();
            }
            if (layout == null) {
                throw new IllegalStateException("CLASS_NEW " + op.opId() + " classId "
                    + payload.classId() + " has no layout in the resolution context "
                    + "(producer defect)");
            }
            java.util.Set<String> provided = new java.util.HashSet<>();
            for (KindPayload.ProvidedField field : payload.providedFields()) {
                provided.add(field.name());
            }
            switch (payload.defaultOwner()) {
                case LOCAL -> emitClassNewLocalDefaults(op, payload, provided);
                case SHARED_FACTORY -> emitClassNewFactoryTransfer(op, payload, provided);
                case HOST_DEFAULTS -> {

                }
                case FFI_PLAN -> {

                    emitClassNewFfiPlan(op, payload, layout);
                    return;
                }
                case BUILTIN_DEFAULTS -> {
                    // The builtin Error construction (K13 items 2-4): the
                    // builtin defaults are compiler constants, so no default
                    // child runs — provided fields only, and the omitted
                    // fields take the constant empty string at the
                    // construction site.
                }
                default -> throw new IllegalStateException("CLASS_NEW " + op.opId()
                    + " carries defaultOwner " + payload.defaultOwner()
                    + " outside the emitted owners (producer defect)");
            }

            emitExtraKeyRejection(op, payload, layout);
            if (payload.defaultOwner() == DefaultOwner.BUILTIN_DEFAULTS) {
                emitClassNewBuiltinDefaults(op, payload, layout);
                return;
            }
            if (payload.defaultOwner() == DefaultOwner.HOST_DEFAULTS) {
                emitClassNewHostDefaults(op, payload, layout);
                return;
            }

            out.append("__instT = {}\n");
            out.append("__instT.__f = {}\n");
            out.append("__instT.__p = {}\n");
            for (KindPayload.FieldBoundary entry : payload.fieldBoundaries()) {
                SemanticOp boundary = opsById.get(entry.boundaryOpId());
                if (boundary == null) {
                    throw new IllegalStateException("CLASS_NEW " + op.opId()
                        + " field boundary " + entry.boundaryOpId() + " does not "
                        + "resolve (producer defect)");
                }
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                String inputExpr;
                if (entry.kind() == BoundaryKind.CLASS_DEFAULT_FIELD
                        && payload.defaultOwner() == DefaultOwner.SHARED_FACTORY) {

                    SemanticOp factoryOp = opsById.get(factoryOpIdOf(op, payload));
                    inputExpr = "__fT";
                    out.append("__fT = __member(")
                        .append(slot((ValueId) factoryOp.result())).append(", ")
                        .append(luaString(entry.field())).append(")\n");
                } else {
                    inputExpr = slot(boundaryPayload.input());
                }
                emitBoundaryStart(boundary, inputExpr, boundaryPayload.descriptor());
                out.append("__okB, __chkB = pcall(__bcheck, ")
                    .append(luaString(descriptorText(boundaryPayload.descriptor())))
                    .append(", ")
                    .append(luaString(staticKind(boundaryPayload.descriptor())))
                    .append(", ").append(inputExpr).append(")\n");
                out.append("if not __okB then\n");
                out.append("  __chkB.o = ")
                    .append(luaString(originOf(boundary))).append("\n");
                emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                    "__errtext(__chkB)");
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "__errtext(__chkB)");
                out.append("  error(__chkB, 0)\n");
                out.append("end\n");
                emitBoundarySuccess(boundary, "__chkB", boundaryPayload.descriptor());
                out.append("__instT.__f[").append(luaString(entry.field()))
                    .append("] = (__chkB == nil) and __NULL or __chkB\n");
                out.append("__instT.__p[").append(luaString(entry.field()))
                    .append("] = true\n");
            }

            out.append("__instT.__c = true\n");
            out.append("__instT.__id = ")
                .append(luaString(payload.classId().text())).append("\n");
            out.append(slot((ValueId) op.result())).append(" = __instT\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
        }

        private void emitExtraKeyRejection(SemanticOp op,
                KindPayload.ClassNewPayload payload, ClassLayout layout) {
            for (KindPayload.ProvidedField field : payload.providedFields()) {
                if (fieldOf(layout, field.name()) == null) {
                    out.append("__eT = __arm(\"CLASS_EXTRA_FIELD\", {field = ")
                        .append(luaString(field.name()))
                        .append(", classId = ")
                        .append(luaString(payload.classId().text()))
                        .append("}, ").append(luaString(originOf(op)))
                        .append(", nil, nil)\n");
                    emitFailureEvent(op.opId(), op.kind().name(), op,
                        "__errtext(__eT)");
                    out.append("error(__eT, 0)\n");
                }
            }
        }

        /** The resolved CLASS_LITERAL_FIELD boundary child of one field entry. */
        private SemanticOp requireClassNewFieldBoundary(SemanticOp op,
                                                        KindPayload.FieldBoundary entry) {
            SemanticOp boundary = opsById.get(entry.boundaryOpId());
            if (boundary == null) {
                throw new IllegalStateException("CLASS_NEW " + op.opId()
                    + " field boundary " + entry.boundaryOpId() + " does not "
                    + "resolve (producer defect)");
            }
            return boundary;
        }

        /**
         * One CLASS_LITERAL_FIELD boundary child of a class construction:
         * the boundary START, the pcall admission into the named local,
         * the boundary and owner FAILURE events, and the boundary SUCCESS.
         */
        private void emitClassNewFieldCheck(SemanticOp op, SemanticOp boundary,
                                            String checked) {
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) boundary.payload();
            String inputExpr = slot(boundaryPayload.input());
            emitBoundaryStart(boundary, inputExpr, boundaryPayload.descriptor());
            out.append("__okB, ").append(checked).append(" = pcall(__bcheck, ")
                .append(luaString(descriptorText(boundaryPayload.descriptor())))
                .append(", ")
                .append(luaString(staticKind(boundaryPayload.descriptor())))
                .append(", ").append(inputExpr).append(")\n");
            out.append("if not __okB then\n");
            out.append("  ").append(checked).append(".o = ")
                .append(luaString(originOf(boundary))).append("\n");
            emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                "__errtext(" + checked + ")");
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "__errtext(" + checked + ")");
            out.append("  error(").append(checked).append(", 0)\n");
            out.append("end\n");
            emitBoundarySuccess(boundary, checked, boundaryPayload.descriptor());
        }

        private void emitClassNewBuiltinDefaults(SemanticOp op,
                KindPayload.ClassNewPayload payload, ClassLayout layout) {
            if (!ClassId.ERROR.equals(payload.classId())
                    || !layout.equals(ClassLayout.BUILTIN_ERROR)) {
                throw new IllegalStateException("CLASS_NEW " + op.opId()
                    + " carries defaultOwner BUILTIN_DEFAULTS for " + payload.classId()
                    + " over " + layout.classId() + ": the builtin-defaults owner is"
                    + " admissible only for the compiler-owned builtin Error class"
                    + " (producer defect)");
            }
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            for (KindPayload.FieldBoundary entry : payload.fieldBoundaries()) {
                SemanticOp boundary = requireClassNewFieldBoundary(op, entry);
                String checked = "__ecT" + boundary.opId().id();
                emitClassNewFieldCheck(op, boundary, checked);
                values.put(entry.field(), checked);
            }
            String target = slot((ValueId) op.result());
            String code = values.get("code");
            String message = values.get("message");
            out.append("__instT = {__d = true, code = ")
                .append(code == null ? luaString("") : code)
                .append(", m = ")
                .append(message == null ? luaString("") : message).append("}\n");
            out.append(target).append(" = __instT\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private void emitClassNewHostDefaults(SemanticOp op,
                KindPayload.ClassNewPayload payload, ClassLayout layout) {
            if (!isHostClass(payload.classId())) {
                throw new IllegalStateException("CLASS_NEW " + op.opId()
                    + " carries defaultOwner HOST_DEFAULTS for " + payload.classId()
                    + ", which is not a declared host class of the compile's"
                    + " declaration surface (the class defaults entry has exactly"
                    + " one source — a producer defect)");
            }
            if (payload.classFactoryRef() != null
                    || !payload.classDefaultOpIds().isEmpty()) {
                throw new IllegalStateException("CLASS_NEW " + op.opId()
                    + " carries defaultOwner HOST_DEFAULTS with a non-null factory"
                    + " ref or a non-empty default child list: the host construction"
                    + " carries neither (the loaded defaults are data — a producer"
                    + " defect)");
            }
            // (1) The provided values in literal order, normalized to the
            // deployed runtime's own language-null sentinel.
            out.append("__provT = {}\n");
            for (KindPayload.ProvidedField field : payload.providedFields()) {
                out.append("__pvT = ").append(slot(field.valueOpId())).append("\n");
                out.append("if __pvT == nil or __pvT == __NULL then __pvT = "
                    + "__rt.__NULL end\n");
                out.append("__provT[").append(luaString(field.name()))
                    .append("] = __pvT\n");
            }
            // (2)-(4) The deep copy, the provided overlay with the extra-key
            // rejection, the sentinel removal, and the tag: the deployed
            // construction entry (the one authority the retained route and
            // the shared route share).
            out.append("__okB, __instT = pcall(__rt.class_, ")
                .append(luaString(payload.classId().text())).append(", ")
                .append(hostDefaultsExpr(payload.classId())).append(", __provT, ")
                .append(classLiteralOriginArgs(op)).append(")\n");
            out.append("if not __okB then\n");
            out.append("  __eT = __hostError(__instT, ")
                .append(luaString(originOf(op))).append(")\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__eT)");
            out.append("  error(__eT, 0)\n");
            out.append("end\n");
            out.append("__instT.__c = true\n");
            out.append("__instT.__id = ")
                .append(luaString(payload.classId().text())).append("\n");
            // (5) The CLASS_LITERAL_FIELD boundary children in declaration
            // order; the admitted value replaces the overlaid one (the
            // language null renormalizes to the runtime sentinel, so a
            // present null stays present for the host side and the
            // presence-aware reads).
            for (KindPayload.FieldBoundary entry : payload.fieldBoundaries()) {
                SemanticOp boundary = requireClassNewFieldBoundary(op, entry);
                String checked = "__hccT" + boundary.opId().id();
                emitClassNewFieldCheck(op, boundary, checked);
                out.append("__instT[").append(luaString(entry.field()))
                    .append("] = (").append(checked)
                    .append(" == nil) and __rt.__NULL or ").append(checked)
                    .append("\n");
            }
            String target = slot((ValueId) op.result());
            out.append(target).append(" = __instT\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private void emitClassNewFfiPlan(SemanticOp op,
                KindPayload.ClassNewPayload payload, ClassLayout layout) {
            if (ffiClassModules.get(payload.classId()) == null) {
                throw new IllegalStateException("CLASS_NEW " + op.opId()
                    + " carries defaultOwner FFI_PLAN for " + payload.classId()
                    + ", which is not a declared extern-C class of the compile's"
                    + " declaration surface (the class plan entry has exactly one"
                    + " source — a producer defect)");
            }
            if (payload.classFactoryRef() != null
                    || !payload.classDefaultOpIds().isEmpty()) {
                throw new IllegalStateException("CLASS_NEW " + op.opId()
                    + " carries defaultOwner FFI_PLAN with a non-null factory"
                    + " ref or a non-empty default child list: the C-struct"
                    + " construction carries neither (the loaded plan's"
                    + " evaluators are the single default authority — a"
                    + " producer defect)");
            }
            // (1) The provided fields in declaration order: the boundary
            // child's descriptor-kind check over the provided value's
            // completed slot, then the admitted value into the provided
            // table (a class-typed position projects to the runtime
            // representation the loaded matcher validates).
            out.append("__provT = {}\n");
            for (KindPayload.FieldBoundary entry : payload.fieldBoundaries()) {
                SemanticOp boundary = requireClassNewFieldBoundary(op, entry);
                String checked = "__fpcT" + boundary.opId().id();
                emitClassNewFieldCheck(op, boundary, checked);
                out.append("__provT[").append(luaString(entry.field()))
                    .append("] = ")
                    .append(ffiClassFieldProjection(
                        ((KindPayload.BoundaryPayload) boundary.payload())
                            .descriptor(),
                        checked))
                    .append("\n");
            }
            // (2) The deterministic extra-key guard.
            emitExtraKeyRejection(op, payload, layout);
            // (3) The runtime's four phases over the loaded plan entry.
            out.append("__okB, __instT = pcall(__rt.class_plan_, ")
                .append(luaString(payload.classId().text())).append(", ")
                .append(ffiPlanExpr(payload.classId())).append(", __provT, ")
                .append(classLiteralOriginArgs(op)).append(")\n");
            out.append("if not __okB then\n");
            out.append("  __eT = __hostError(__instT, ")
                .append(luaString(originOf(op))).append(")\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__eT)");
            out.append("  error(__eT, 0)\n");
            out.append("end\n");
            // (4) The wrapper-to-chunk projection of the constructed
            // instance, then the publication.
            String target = slot((ValueId) op.result());
            out.append(target).append(" = __hostDealProject(")
                .append(luaString(payload.classId().text())).append(", __instT, ")
                .append(luaString(originOf(op))).append(")\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        /**
         * The provided-table expression of one C-struct field: a
         * class-typed position (the declared descriptor is a class
         * identity) is projected from the chunk representation to the
         * runtime representation the loaded plan's phase-3 matcher
         * validates (the load/crossing page F4's {@code __hostClassProject})
         * and every other position passes its checked value unchanged.
         */
        private String ffiClassFieldProjection(RuntimeDescriptor descriptor,
                String checked) {
            if (descriptor instanceof RuntimeDescriptor.Class) {
                return "__hostClassProject(" + luaString(descriptorText(descriptor))
                    + ", " + checked + ")";
            }
            return checked;
        }

        private void emitClassNewLocalDefaults(SemanticOp op,
                KindPayload.ClassNewPayload payload, java.util.Set<String> provided) {
            for (OpId defaultOpId : payload.classDefaultOpIds()) {
                SemanticOp defaultOp = opsById.get(defaultOpId);
                if (defaultOp == null) {
                    throw new IllegalStateException("CLASS_NEW " + op.opId()
                        + " CLASS_DEFAULT child " + defaultOpId + " does not resolve "
                        + "(producer defect)");
                }
                KindPayload.ClassDefaultPayload defaultPayload =
                    (KindPayload.ClassDefaultPayload) defaultOp.payload();
                if (provided.contains(defaultPayload.field())) {
                    continue; // the skip-provided rule
                }
                emitClassDefaultCall(op, defaultOp);
            }
        }

        /** One CLASS_DEFAULT child execution (START, function, terminal). */
        private void emitClassDefaultCall(SemanticOp op, SemanticOp defaultOp) {
            out.append("__ev(").append(luaString(opKey(defaultOp.opId())))
                .append(", \"START\", \"CLASS_DEFAULT\", ")
                .append(luaString(defaultOp.contract().canonicalDigest()))
                .append(", \"-\", {}, nil, nil)\n");
            out.append("__okT, __resT = pcall(")
                .append(defaultFn(defaultOp.opId())).append(")\n");
            out.append("if not __okT then\n");
            emitFailureEvent(defaultOp.opId(), "CLASS_DEFAULT", defaultOp,
                "__errtext(__resT)");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__resT)");
            out.append("  error(__resT, 0)\n");
            out.append("end\n");
            if (defaultOp.result() instanceof ValueId resultId) {
                out.append(slot(resultId)).append(" = __resT\n");
            }
            out.append("__ev(").append(luaString(opKey(defaultOp.opId())))
                .append(", \"SUCCESS\", \"CLASS_DEFAULT\", ")
                .append(luaString(defaultOp.contract().canonicalDigest()))
                .append(", \"-\", {}, __atom(")
                .append(luaString(staticKind((RuntimeDescriptor) defaultOp.resultType())))
                .append(", __resT), nil)\n");
        }

        /**
         * The nesting-safe module save of a factory transfer: one stack
         * push; the stack order is the transfer nesting order.
         */
        private void emitModulePush() {
            out.append("__modStack[#__modStack + 1] = __module\n");
        }

        /**
         * The matching restore: the transfer's own saved module, never an
         * enclosing transfer's (the nesting-safe pop).
         */
        private void emitModulePop() {
            out.append("__module = __modStack[#__modStack]\n");
            out.append("__modStack[#__modStack] = nil\n");
        }

        private void emitClassNewFactoryTransfer(SemanticOp op,
                KindPayload.ClassNewPayload payload, java.util.Set<String> provided) {
            OpId factoryOpId = factoryOpIdOf(op, payload);
            SemanticOp factoryOp = opsById.get(factoryOpId);
            if (factoryOp == null || factoryOp.kind() != SemanticOpKind.CLASS_FACTORY) {
                throw new IllegalStateException("CLASS_NEW " + op.opId()
                    + " resolves factory op " + factoryOpId + " outside the pinned "
                    + "kind (producer defect)");
            }
            KindPayload.ClassFactoryPayload factoryPayload =
                (KindPayload.ClassFactoryPayload) factoryOp.payload();
            if (!factoryPayload.classId().equals(payload.classId())) {
                throw new IllegalStateException("CLASS_NEW " + op.opId()
                    + " resolves a factory of " + factoryPayload.classId()
                    + " (producer defect)");
            }
            if (!(factoryOp.result() instanceof ValueId factoryResult)) {
                throw new IllegalStateException("CLASS_FACTORY " + factoryOp.opId()
                    + " publishes no ValueId result (producer defect)");
            }
            String ownerPath = factoryOpId.module().path();
            emitModulePush();
            out.append("__module = ").append(luaString(ownerPath)).append("\n");
            out.append("__ev(").append(luaString(opKey(factoryOp.opId())))
                .append(", \"START\", \"CLASS_FACTORY\", ")
                .append(luaString(factoryOp.contract().canonicalDigest()))
                .append(", ").append(luaString(opKey(op.opId())))
                .append(", {}, nil, nil)\n");
            // The factory's default children in declaration order,
            // skipping any child whose field the caller provides.
            java.util.List<SemanticOp> filled = new ArrayList<>();
            for (OpId defaultOpId : factoryPayload.classDefaultOpIds()) {
                SemanticOp defaultOp = opsById.get(defaultOpId);
                if (defaultOp == null) {
                    throw new IllegalStateException("CLASS_FACTORY " + factoryOp.opId()
                        + " CLASS_DEFAULT child " + defaultOpId + " does not resolve "
                        + "(producer defect)");
                }
                KindPayload.ClassDefaultPayload defaultPayload =
                    (KindPayload.ClassDefaultPayload) defaultOp.payload();
                if (provided.contains(defaultPayload.field())) {
                    continue;
                }
                out.append("__ev(").append(luaString(opKey(defaultOp.opId())))
                    .append(", \"START\", \"CLASS_DEFAULT\", ")
                    .append(luaString(defaultOp.contract().canonicalDigest()))
                    .append(", \"-\", {}, nil, nil)\n");
                out.append("__okT, __resT = pcall(")
                    .append(defaultFn(defaultOp.opId())).append(")\n");
                out.append("if not __okT then\n");
                emitFailureEvent(defaultOp.opId(), "CLASS_DEFAULT", defaultOp,
                    "__errtext(__resT)");
                out.append("__ev(").append(luaString(opKey(factoryOp.opId())))
                    .append(", \"FAILURE\", \"CLASS_FACTORY\", ")
                    .append(luaString(factoryOp.contract().canonicalDigest()))
                    .append(", ").append(luaString(opKey(op.opId())))
                    .append(", {}, nil, __errtext(__resT))\n");
                // Restore the caller's module before the caller's own
                // terminal: the owner-side terminals above carry the
                // owner's module, the caller's CLASS_NEW FAILURE carries
                // the caller's (the oracle's own tagging).
                emitModulePop();
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "__errtext(__resT)");
                out.append("  error(__resT, 0)\n");
                out.append("end\n");
                if (defaultOp.result() instanceof ValueId resultId) {
                    out.append(slot(resultId)).append(" = __resT\n");
                }
                out.append("__ev(").append(luaString(opKey(defaultOp.opId())))
                    .append(", \"SUCCESS\", \"CLASS_DEFAULT\", ")
                    .append(luaString(defaultOp.contract().canonicalDigest()))
                    .append(", \"-\", {}, __atom(")
                    .append(luaString(staticKind((RuntimeDescriptor) defaultOp.resultType())))
                    .append(", __resT), nil)\n");
                filled.add(defaultOp);
            }
            // The untagged internal transfer instance: the defaulted
            // fields present (present null via the __NULL sentinel),
            // every other field missing.
            out.append("__instT = {}\n");
            out.append("__instT.__f = {}\n");
            out.append("__instT.__p = {}\n");
            for (SemanticOp defaultOp : filled) {
                KindPayload.ClassDefaultPayload defaultPayload =
                    (KindPayload.ClassDefaultPayload) defaultOp.payload();
                out.append("__instT.__f[").append(luaString(defaultPayload.field()))
                    .append("] = (").append(slot((ValueId) defaultOp.result()))
                    .append(" == nil) and __NULL or ")
                    .append(slot((ValueId) defaultOp.result())).append("\n");
                out.append("__instT.__p[").append(luaString(defaultPayload.field()))
                    .append("] = true\n");
            }
            out.append("__instT.__c = true\n");
            out.append("__instT.__id = ")
                .append(luaString(payload.classId().text())).append("\n");
            out.append(slot(factoryResult)).append(" = __instT\n");
            out.append("__ev(").append(luaString(opKey(factoryOp.opId())))
                .append(", \"SUCCESS\", \"CLASS_FACTORY\", ")
                .append(luaString(factoryOp.contract().canonicalDigest()))
                .append(", ").append(luaString(opKey(op.opId())))
                .append(", {}, __atom(\"class\", __instT), nil)\n");
            emitModulePop();
        }

        /** The declared layout entry of one field name, or null. */
        private ClassLayout.FieldLayout fieldOf(ClassLayout layout, String name) {
            for (ClassLayout.FieldLayout field : layout.fields()) {
                if (field.name().equals(name)) {
                    return field;
                }
            }
            return null;
        }

        private void emitBranch(SemanticOp op) {
            KindPayload.BranchPayload payload = (KindPayload.BranchPayload) op.payload();
            emitStart(op);
            String condition = slot(payload.condition());
            switch (payload.selector()) {
                case IF -> {
                    String selected = label(payload.selectedBlock());
                    String after = label(payload.selectedBlock()) + "A";
                    if (payload.alternateBlock() != null) {
                        String alternate = label(payload.alternateBlock());
                        out.append("if ").append(condition).append(" then goto ")
                            .append(selected).append(" else goto ").append(alternate)
                            .append(" end\n");
                        emitLabeledBlock(payload.selectedBlock());
                        out.append("goto ").append(after).append("\n");
                        emitLabeledBlock(payload.alternateBlock());
                        out.append("::").append(after).append("::\n");
                    } else {
                        out.append("if ").append(condition).append(" then goto ")
                            .append(selected).append(" end\n");
                        out.append("goto ").append(after).append("\n");
                        emitLabeledBlock(payload.selectedBlock());
                        out.append("::").append(after).append("::\n");
                    }
                    emitPlainSuccess(op);
                }
                case LOGICAL_AND, LOGICAL_OR -> {
                    String selected = label(payload.selectedBlock());
                    String after = label(payload.selectedBlock()) + "A";
                    String target = slot((ValueId) op.result());
                    boolean isAnd = payload.selector()
                        == deal.semantic.ir.ControlSelector.LOGICAL_AND;
                    if (isAnd) {
                        // AND: the block runs when the left value is true.
                        out.append("if ").append(condition).append(" then goto ")
                            .append(selected).append(" end\n");
                    } else {
                        // OR: the block runs when the left value is false.
                        out.append("if not ").append(condition).append(" then goto ")
                            .append(selected).append(" end\n");
                    }
                    out.append(target).append(" = ").append(condition).append("\n");
                    out.append("goto ").append(after).append("\n");
                    emitLabeledBlock(payload.selectedBlock());
                    out.append("::").append(after).append("::\n");
                    emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
                }
                default -> throw new IllegalStateException("BRANCH selector "
                    + payload.selector());
            }
        }

        private void emitLoop(SemanticOp op) {
            KindPayload.LoopPayload payload = (KindPayload.LoopPayload) op.payload();
            emitStart(op);
            String exit = loopExit(op.opId());
            String cont = loopCont(op.opId());
            String condition = slot(payload.condition());
            switch (payload.selector()) {
                case WHILE -> {
                    out.append("::").append(cont).append("::\n");
                    emitLabeledBlock(payload.initBlock());
                    out.append("if not ").append(condition).append(" then goto ")
                        .append(exit).append(" end\n");
                    emitLabeledBlock(payload.bodyBlock());
                    out.append("goto ").append(cont).append("\n");
                    out.append("::").append(exit).append("::\n");
                }
                case FOR -> {
                    emitLabeledBlock(payload.initBlock());
                    out.append("::").append(cont).append("T::\n");
                    out.append("if not ").append(condition).append(" then goto ")
                        .append(exit).append(" end\n");
                    emitLabeledBlock(payload.bodyBlock());
                    out.append("::").append(cont).append("::\n");
                    if (payload.updateBlock() != null) {
                        emitLabeledBlock(payload.updateBlock());
                    }
                    out.append("goto ").append(cont).append("T\n");
                    out.append("::").append(exit).append("::\n");
                }
                default -> throw new IllegalStateException("LOOP selector "
                    + payload.selector());
            }
            emitPlainSuccess(op);
        }

        private void emitForEach(SemanticOp op) {
            KindPayload.ForEachPayload payload = (KindPayload.ForEachPayload) op.payload();
            emitStart(op);
            String exit = loopExit(op.opId());
            switch (payload.mode()) {
                case ARRAY_VALUES -> {
                    String iterable = slot(payload.iterable());
                    String cellName = cell(payload.binding(), payload.generation());
                    String cont = loopCont(op.opId());
                    out.append("do\n");
                    out.append("  __itT = ").append(iterable).append("\n");
                    out.append("  __itnT = __itT.__n\n");
                    out.append("  for __i = 0, __itnT - 1 do\n");
                    out.append("    __elemT = __MISSING\n");
                    out.append("    if __i < __itT.__n then\n");
                    // The element read is a typed position: the admitted
                    // int/number variant materializes exactly as at
                    // __arrayRead (the element's own slot mark and the
                    // iterable's declared element descriptor), so the
                    // loop cell carries the value's own variant.
                    out.append("      __elemT = __readVar(__itT, __i + 1, ")
                        .append("__itT[__i + 1], ")
                        .append(isNumberKind(staticKind(elementDescriptorOf(op)))
                            ? "true" : "false")
                        .append(")\n");
                    out.append("      if __elemT == __NULL then __elemT = nil\n");
                    out.append("      elseif __elemT == nil then __elemT = __MISSING end\n");
                    out.append("    end\n");
                    out.append("    __foreachCheck(")
                        .append(luaString(opKey(op.opId()))).append(", ")
                        .append(luaString(op.contract().canonicalDigest())).append(", ")
                        .append(luaString(parentKey(op.origin().parentOpId()))).append(", ")
                        .append(luaString(descriptorText(elementDescriptorOf(op))))
                        .append(", __elemT, ").append(luaString(originOf(op))).append(")\n");
                    out.append("    ").append(cellName).append(" = {__elemT}\n");
                    emitLabeledBlock(payload.body());
                    out.append("    ::").append(cont).append("::\n");
                    out.append("  end\n");
                    // The loop op's exit label (the FOR_EACH form a
                    // BREAK/CONTINUE targets defines both labels its body's
                    // transfers use): the no-protected-body transfer arm
                    // emits its `goto X<id>` at this op's own emission level,
                    // which is inside this `do … end` block, and the loop
                    // op's SUCCESS event stays after the label so a break
                    // exit emits it exactly once (the oracle's loop wrapper).
                    out.append("  ::").append(exit).append("::\n");
                    out.append("end\n");
                }
                case STRING_SCALARS -> {
                    String iterable = slot(payload.iterable());
                    String cellName = cell(payload.binding(), payload.generation());
                    String cont = loopCont(op.opId());
                    out.append("do\n");
                    out.append("  __itT = ").append(iterable).append("\n");
                    out.append("  for __i = 1, #__itT do\n");
                    out.append("    ").append(cellName)
                        .append(" = {__u8sub(__itT, __i, __i)}\n");
                    emitLabeledBlock(payload.body());
                    out.append("    ::").append(cont).append("::\n");
                    out.append("  end\n");
                    out.append("  ::").append(exit).append("::\n");
                    out.append("end\n");
                }
            }
            emitPlainSuccess(op);
        }

        private void emitTryCatch(SemanticOp op) {
            KindPayload.TryCatchPayload payload = (KindPayload.TryCatchPayload) op.payload();
            emitStart(op);
            String cellName = cell(payload.catchBinding(), 0);
            tryDepth++;
            out.append("__okT, __resT = pcall(function()\n");
            emitBlockOps(payload.tryBlock());
            out.append("end)\n");
            tryDepth--;
            out.append("if not __okT then\n");
            emitTransferDispatch(op, payload.tryBlock(), "  ", "__resT");
            out.append("  if type(__resT) == \"table\" and __resT.__d then\n");

            out.append("    ").append(cellName).append(" = __resT\n");
            tryDepth++;
            out.append("    __okT, __terrT = pcall(function()\n");
            emitBlockOps(payload.catchBlock());
            out.append("    end)\n");
            tryDepth--;
            out.append("    if not __okT then\n");

            emitTransferDispatch(op, payload.catchBlock(), "      ", "__terrT");
            out.append("      __wrappedT = {__d = true, code = __terrT.code, "
                + "m = __terrT.m, o = __terrT.o, e = __terrT.e, a = __terrT.a, "
                + "f = __terrT.f, cause = __resT}\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__wrappedT)");
            out.append("      error(__wrappedT, 0)\n");
            out.append("    end\n");
            out.append("  else\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__resT)");
            out.append("    error(__resT, 0)\n");
            out.append("  end\n");
            out.append("end\n");
            emitPlainSuccess(op);
        }

        /**
         * The transfer dispatch after a pcall-wrapped block: the block's
         * BREAK/CONTINUE/RETURN ops (recursively) signalled through the
         * pcall boundary re-apply their static transfers here. The
         * {@code errorVar} names the pcall result the dispatch reads —
         * {@code __resT} for the try block, {@code __terrT} for the catch
         * block (its own pcall).
         *
         * <p>A transfer is applied at the level where its target label is
         * defined in the same emitted Lua function: a target inside the
         * level's own function takes the emitted jump, while a target
         * outside the nearest enclosing protected body crosses that body as
         * the re-raised marker (the next enclosing dispatch owns the jump) —
         * no emitted jump may reference a label outside its emitted
         * function.</p>
         */
        private void emitTransferDispatch(SemanticOp tryOp, BlockId block, String pad,
                                          String errorVar) {
            List<SemanticOp> transfers = new ArrayList<>();
            collectTransfers(block, transfers);
            if (transfers.isEmpty()) {
                return;
            }
            out.append(pad).append("if type(").append(errorVar)
                .append(") == \"table\" and ").append(errorVar).append(".__tr then\n");
            for (SemanticOp transfer : transfers) {
                switch (transfer.kind()) {
                    case BREAK -> {
                        KindPayload.BreakPayload breakPayload =
                            (KindPayload.BreakPayload) transfer.payload();
                        SemanticOp targetLoop = opsById.get(breakPayload.loopId());
                        boolean visible = transferTargetVisible(tryOp, targetLoop);
                        out.append(pad).append("  if ").append(errorVar)
                            .append(".t == \"break\" and ").append(errorVar)
                            .append(".id == ")
                            .append(breakPayload.loopId().id()).append(" then\n");
                        emitPlainSuccess(tryOp);
                        emitTransferClosures(tryOp, targetLoop, !visible);
                        out.append(pad).append("    ").append(visible
                            ? "goto " + loopExit(breakPayload.loopId())
                            : "error(" + errorVar + ", 0)").append("\n");
                        out.append(pad).append("  end\n");
                    }
                    case CONTINUE -> {
                        KindPayload.ContinuePayload continuePayload =
                            (KindPayload.ContinuePayload) transfer.payload();
                        SemanticOp targetLoop = opsById.get(continuePayload.loopId());
                        boolean visible = transferTargetVisible(tryOp, targetLoop);
                        out.append(pad)
                            .append("  if ").append(errorVar)
                            .append(".t == \"continue\" and ").append(errorVar)
                            .append(".id == ")
                            .append(continuePayload.loopId().id()).append(" then\n");
                        emitPlainSuccess(tryOp);
                        emitTransferClosures(tryOp, targetLoop, !visible);
                        out.append(pad).append("    ").append(visible
                            ? "goto " + loopCont(continuePayload.loopId())
                            : "error(" + errorVar + ", 0)").append("\n");
                        out.append(pad).append("  end\n");
                    }
                    case RETURN -> {
                        // The return trampoline label lives at the enclosing
                        // function's top level: it is visible here exactly
                        // when this level is not inside a protected body.
                        boolean visible = transferTargetVisible(tryOp, null);
                        out.append(pad)
                            .append("  if ").append(errorVar)
                            .append(".t == \"return\" then\n");
                        emitPlainSuccess(tryOp);
                        emitTransferClosures(tryOp, null, !visible);
                        out.append(pad).append("    ").append(visible
                            ? "return " + errorVar + ".v"
                            : "error(" + errorVar + ", 0)").append("\n");
                        out.append(pad).append("  end\n");
                    }
                    default -> {
                    }
                }
            }
            out.append(pad).append("  error(").append(errorVar).append(", 0)\n");
            out.append(pad).append("end\n");
        }

        private void emitThrow(SemanticOp op) {
            KindPayload.ThrowPayload payload = (KindPayload.ThrowPayload) op.payload();
            emitStart(op);
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "__errtext({__d = true, code = " + slot(payload.errorValue())
                    + ".code, m = " + slot(payload.errorValue()) + ".m, o = "
                    + luaString(originOf(op)) + ", e = nil, a = nil, f = __framesText(), "
                    + "cause = nil})");
            out.append("error({__d = true, code = ").append(slot(payload.errorValue()))
                .append(".code, m = ").append(slot(payload.errorValue()))
                .append(".m, o = ").append(luaString(originOf(op)))
                .append(", e = nil, a = nil, f = __framesText(), cause = nil}, 0)\n");
        }

        private void emitReturn(SemanticOp op) {
            KindPayload.ReturnPayload payload = (KindPayload.ReturnPayload) op.payload();
            SemanticOp boundary = opsById.get(payload.returnBoundaryOpId());
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) boundary.payload();
            emitStart(op);
            String value = payload.value() == null ? "nil" : slot(payload.value());
            out.append("__rvT = ").append(value).append("\n");
            emitBoundaryStart(boundary, "__rvT", boundaryPayload.descriptor());
            // The body-local return cell's own failure projection: the cell
            // projection carries the cell's origin (the callee's RETURN), the
            // boundary and the RETURN op emit their FAILURE terminals, and the
            // identical error propagates (the semantic oracle's
            // runBoundaryChild + the owning-op FAILURE).
            out.append("__okB, __chkB = pcall(__bcheck, ")
                .append(luaString(descriptorText(boundaryPayload.descriptor())))
                .append(", ")
                .append(luaString(staticKind(boundaryPayload.descriptor())))
                .append(", __rvT");
            if (containsFunction(boundaryPayload.descriptor())) {
                out.append(", ")
                    .append(luaString(boundaryPayload.descriptor().canonicalSpecText()));
            }
            out.append(")\n");
            out.append("if not __okB then\n");
            out.append("  __chkB.o = ").append(luaString(originOf(boundary)))
                .append("\n");
            emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                "__errtext(__chkB)");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__chkB)");
            out.append("  error(__chkB, 0)\n");
            out.append("end\n");
            out.append("__rvcT = __chkB\n");
            emitBoundarySuccess(boundary, "__rvcT", boundaryPayload.descriptor());
            emitPlainSuccess(op);
            if (!transferTargetVisible(op, null)) {
                emitTransferClosures(op, null, true);
                out.append("error({__tr = true, t = \"return\", v = __rvcT}, 0)\n");
            } else {
                emitTransferClosures(op, null, false);
                if (currentFunctionId != null) {
                    // Jump to the function's return trampoline — the last
                    // statement of the closure body. A raw Lua return
                    // would be followed by a structure's closing label
                    // (a return inside a branch/loop/for-each block) or by
                    // the trampoline label itself (a top-level return
                    // before the tail) — both invalid Lua.
                    out.append("goto ").append(returnLabel(currentFunctionId))
                        .append("\n");
                } else {
                    // Defensive: a RETURN outside any factory emission
                    // (never produced by the walk) keeps the plain form.
                    out.append("return __rvcT\n");
                }
            }
        }

        private void emitBreak(SemanticOp op) {
            KindPayload.BreakPayload payload = (KindPayload.BreakPayload) op.payload();
            emitStart(op);
            emitPlainSuccess(op);
            SemanticOp targetLoop = opsById.get(payload.loopId());
            if (transferTargetVisible(op, targetLoop)) {
                emitTransferClosures(op, targetLoop, false);
                out.append("goto ").append(loopExit(payload.loopId())).append("\n");
            } else {
                emitTransferClosures(op, targetLoop, true);
                out.append("error({__tr = true, t = \"break\", id = ")
                    .append(payload.loopId().id()).append("}, 0)\n");
            }
        }

        private void emitContinue(SemanticOp op) {
            KindPayload.ContinuePayload payload = (KindPayload.ContinuePayload) op.payload();
            emitStart(op);
            emitPlainSuccess(op);
            SemanticOp targetLoop = opsById.get(payload.loopId());
            if (transferTargetVisible(op, targetLoop)) {
                emitTransferClosures(op, targetLoop, false);
                out.append("goto ").append(loopCont(payload.loopId())).append("\n");
            } else {
                emitTransferClosures(op, targetLoop, true);
                out.append("error({__tr = true, t = \"continue\", id = ")
                    .append(payload.loopId().id()).append("}, 0)\n");
            }
        }

        private void emitDiscard(SemanticOp op) {
            emitStart(op);
            emitPlainSuccess(op);
        }

        /**
         * {@code EXPORT_PUBLISH} — the checked {@code MODULE_EXPORT}
         * boundary (its owned child) then the export-table publication of
         * the callable function value (the wrapper's closure, unwrapped
         * so a retained caller can invoke it directly) plus the
         * compiler-owned {@code __val} field carrying the published value
         * itself (M2: the compiled read's resolution source; the
         * {@code f} projection stays the retained-caller ABI's raw
         * callable).
         */
        private void emitExportPublish(SemanticOp op) {
            KindPayload.ExportPublishPayload payload =
                (KindPayload.ExportPublishPayload) op.payload();
            emitStart(op);
            SemanticOp boundary = boundaryChildOf(op);
            if (boundary != null) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                emitBoundaryStart(boundary, slot(payload.value()),
                    boundaryPayload.descriptor());
                out.append("__chk = ")
                    .append(bcheckExpr(boundaryPayload.descriptor(),
                        slot(payload.value())))
                    .append("\n");
                emitBoundarySuccess(boundary, "__chk", boundaryPayload.descriptor());
            }
            out.append("__exportSurfaces[")
                .append(luaString(emittingModulePath(op)))
                .append("][").append(luaString(payload.name()))
                .append("] = {__kind = \"function\", sig = ")
                .append(luaString(payload.descriptor().canonicalSpecText()))
                .append(", f = __unfn(").append(slot(payload.value()))
                .append("), __val = ").append(slot(payload.value()))
                .append("}\n");
            // The publication records the entry's key in the surface's own
            // order discipline: the module's declared exports are its
            // surface's entries in declaration order (K15 item 1), so
            // TABLE_KEYS and the JSON walker read the module's table.
            out.append("__orderAdd(__exportSurfaces[")
                .append(luaString(emittingModulePath(op))).append("], ")
                .append(luaString(payload.name())).append(")\n");
            emitPlainSuccess(op);
        }

        /**
         * The statically known identity of the module that emits an op:
         * the export surface's key. An op with no owning module is a
         * producer defect, never a path guess.
         */
        private String emittingModulePath(SemanticOp op) {
            ModuleId moduleId = opModule.get(op.opId());
            if (moduleId == null) {
                throw new IllegalStateException("the op " + op.opId()
                    + " has no owning module (the export-surface key is the emitting "
                    + "module's identity — a producer defect, never a path guess)");
            }
            return moduleId.path();
        }

        /**
         * {@code EXTERNAL_ENTRY} — the callee-unit invocation record of a
         * function callable across a shared/shadow edge: the exports table
         * already publishes the callable value under the export name, so
         * the record needs no runtime action.
         */
        private void emitExternalEntryRecord(SemanticOp op) {
            emitStart(op);
            emitPlainSuccess(op);
        }

        /**
         * CALLBACK_INVOKE (E6, D13 host-driven dispatch): one per-unit
         * entry function the scenario host invokes top-level with
         * scripted arguments. The entry emits its own START (the
         * scripted argument inputs, no parentOpId — the scenario step
         * triggers it), runs the {@code HOST_TO_DEAL} parameter
         * boundaries in one-based order, executes the bound
         * {@link FunctionExecutionBinding} (a DEAL body, or the D15
         * adapter protocol with the leading-M projection), and closes
         * with the op's terminal (SUCCESS with the checked value, or
         * FAILURE with the propagated error). The single
         * {@code DEAL_TO_HOST} return boundary runs by the executed
         * body's {@code RETURN} (its payload names this op as the
         * enclosing invocation). The block walk never runs the entry.
         */
        private void emitCallbackInvoke(SemanticOp op) {
            KindPayload.CallbackInvokePayload payload =
                (KindPayload.CallbackInvokePayload) op.payload();
            FunctionExecutionBinding binding = unit.functionBindings().get(
                new FunctionAllocationIdentity(payload.function().id()));
            if (binding == null) {
                throw new IllegalStateException("CALLBACK_INVOKE " + op.opId()
                    + " resolves no FunctionExecutionBinding (producer defect)");
            }
            String entry = "cb" + op.opId().id();
            out.append("__callbacks[").append(luaString(entry))
                .append("] = function(...)\n");
            out.append("  local __cargs = {...}\n");
            if (trace) {
                StringBuilder inputs = new StringBuilder();
                for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                    if (i > 0) {
                        inputs.append(", ");
                    }
                    inputs.append("__hostAtom(__cargs[").append(i + 1).append("])");
                }
                out.append("  __ev(").append(luaString(opKey(op.opId())))
                    .append(", \"START\", ")
                    .append(luaString(op.kind().name())).append(", ")
                    .append(luaString(op.contract().canonicalDigest()))
                    .append(", \"-\", {").append(inputs).append("}, nil, nil)\n");
            }
            for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                SemanticOp boundary = opsById.get(payload.parameterBoundaryOpIds().get(i));
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                if (trace) {
                    out.append("  __ev(").append(luaString(opKey(boundary.opId())))
                        .append(", \"START\", \"BOUNDARY\", ")
                        .append(luaString(boundary.contract().canonicalDigest()))
                        .append(", ")
                        .append(luaString(parentKey(boundary.origin().parentOpId())))
                        .append(", {__hostAtom(__cargs[").append(i + 1)
                        .append("])}, nil, nil)\n");
                }
                out.append("  __okB, __chkB = pcall(__bcheck, ")
                    .append(luaString(descriptorText(boundaryPayload.descriptor())))
                    .append(", ")
                    .append(luaString(staticKind(boundaryPayload.descriptor())))
                    .append(", __cargs[").append(i + 1).append("])\n");
                out.append("  if not __okB then\n");
                out.append("    __chkB.o = ")
                    .append(luaString(originOf(boundary))).append("\n");
                if (trace) {
                    emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                        "__errtext(__chkB)");
                    emitFailureEvent(op.opId(), op.kind().name(), op,
                        "__errtext(__chkB)");
                }
                out.append("    error(__chkB, 0)\n");
                out.append("  end\n");
                if (trace) {
                    out.append("  __ev(").append(luaString(opKey(boundary.opId())))
                        .append(", \"SUCCESS\", \"BOUNDARY\", ")
                        .append(luaString(boundary.contract().canonicalDigest()))
                        .append(", ")
                        .append(luaString(parentKey(boundary.origin().parentOpId())))
                        .append(", {}, __atom(")
                        .append(luaString(staticKind(boundaryPayload.descriptor())))
                        .append(", __chkB), nil)\n");
                }
                out.append("  __cargs[").append(i + 1).append("] = __chkB\n");
            }
            switch (binding) {
                case FunctionExecutionBinding.LoweredBody body -> {
                    LoweredFunction function = functionOf(body.functionId());
                    List<BindingGeneration> captures = function == null
                        ? List.of() : function.captures();
                    StringBuilder caps = new StringBuilder();
                    for (BindingGeneration capture : captures) {
                        if (caps.length() > 0) {
                            caps.append(", ");
                        }
                        caps.append(captureArg(capture));
                    }
                    out.append("  table.insert(__frames, 1, ")
                        .append(luaString(String.valueOf(body.functionId().id())))
                        .append(")\n");
                    out.append("  __okT, __resT = pcall(")
                        .append(fnFactory(body.functionId())).append("(")
                        .append(caps).append(")");
                    for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                        out.append(", __cargs[").append(i + 1).append("]");
                    }
                    out.append(")\n");
                    out.append("  table.remove(__frames, 1)\n");
                    out.append("  if not __okT then\n");
                    if (trace) {
                        emitFailureEvent(op.opId(), op.kind().name(), op,
                            "__errtext(__resT)");
                    }
                    out.append("    error(__resT, 0)\n");
                    out.append("  end\n");
                }
                case FunctionExecutionBinding.AdapterBinding adapter -> {
                    String adapterSlot =
                        slot((ValueId) opsById.get(adapter.adaptOpId()).result());
                    out.append("  __okT, __resT = pcall(").append(adapterSlot)
                        .append(".__fn, ").append(adapterSlot).append(", ")
                        .append(luaString(originOf(op)));
                    for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                        out.append(", __cargs[").append(i + 1).append("]");
                    }
                    out.append(")\n");
                    out.append("  if not __okT then\n");
                    if (trace) {
                        emitFailureEvent(op.opId(), op.kind().name(), op,
                            "__errtext(__resT)");
                    }
                    out.append("    error(__resT, 0)\n");
                    out.append("  end\n");
                }
                case FunctionExecutionBinding.HostFunction host ->
                    emitHostCallbackInvoke(op, payload,
                        "__exportSurfaces[" + luaString(host.hostModuleId().path())
                            + "][" + luaString(host.exportName()) + "]");
                case FunctionExecutionBinding.HostFunctionValue hostValue -> {
                    SemanticOp crossing = opsById.get(
                        hostValue.materializingBoundaryOpId());
                    if (crossing == null
                            || !(crossing.payload()
                                instanceof KindPayload.BoundaryPayload boundary)) {
                        throw new IllegalStateException("CALLBACK_INVOKE "
                            + op.opId() + " names the materializing host crossing "
                            + hostValue.materializingBoundaryOpId()
                            + ", which is not a boundary op of the emitted closure"
                            + " (a producer defect)");
                    }
                    emitHostCallbackInvoke(op, payload, slot(boundary.input()));
                }
                default -> throw new IllegalStateException("CALLBACK_INVOKE "
                    + op.opId() + " resolves a binding outside the statically-resolved "
                    + "slice: " + binding);
            }
            if (trace) {
                out.append("  __ev(").append(luaString(opKey(op.opId())))
                    .append(", \"SUCCESS\", ")
                    .append(luaString(op.kind().name())).append(", ")
                    .append(luaString(op.contract().canonicalDigest()))
                    .append(", \"-\", {}, __atom(")
                    .append(luaString(staticKind(payload.descriptor().returnType())))
                    .append(", __resT), nil)\n");
            }
            out.append("  return __resT\n");
            out.append("end\n");
        }

        private void emitHostCallbackInvoke(SemanticOp op,
                KindPayload.CallbackInvokePayload payload, String target) {
            StringBuilder args = new StringBuilder();
            for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                if (args.length() > 0) {
                    args.append(", ");
                }
                args.append("__cargs[").append(i + 1).append("]");
            }
            out.append("  __okT, __resT = pcall(").append(target).append(".f");
            if (args.length() > 0) {
                out.append(", ").append(args);
            }
            out.append(", ").append(spanTripletArgs(op)).append(")\n");
            out.append("  if not __okT then\n");
            out.append("    __resT = __hostError(__resT, ")
                .append(luaString(originOf(op))).append(")\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__resT)");
            out.append("    error(__resT, 0)\n");
            out.append("  end\n");
            if (payload.returnBoundaryOpId() != null) {
                SemanticOp boundary = opsById.get(payload.returnBoundaryOpId());
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                emitBoundaryStart(boundary, "__resT", boundaryPayload.descriptor());
                out.append("  __okB, __chkB = pcall(__bcheck, ")
                    .append(luaString(descriptorText(boundaryPayload.descriptor())))
                    .append(", ")
                    .append(luaString(staticKind(boundaryPayload.descriptor())))
                    .append(", __resT)\n");
                out.append("  if not __okB then\n");
                out.append("    __chkB.o = ")
                    .append(luaString(originOf(boundary))).append("\n");
                emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                    "__errtext(__chkB)");
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "__errtext(__chkB)");
                out.append("    error(__chkB, 0)\n");
                out.append("  end\n");
                out.append("  __resT = __chkB\n");
                emitBoundarySuccess(boundary, "__resT", boundaryPayload.descriptor());
            }
        }

        // -- async (E4, D13) ----------------------------------------------------------

        /** The canonical referent token identity (alias chains resolve transitively). */
        private long canonicalReferent(AsyncTokenId token) {
            AsyncTokenId current = token;
            while (current instanceof AsyncTokenId.Alias alias) {
                current = alias.referent();
            }
            return current.tokenId();
        }

        /** The canonical referent's closed owner (statically resolved). */
        private AsyncTokenOwner canonicalOwnerOf(AsyncTokenId token) {
            AsyncTokenId current = token;
            while (current instanceof AsyncTokenId.Alias alias) {
                current = alias.referent();
            }
            return ((AsyncTokenId.Canonical) current).owner();
        }

        /** The canonical token atom of an ASYNC_START/EXTERNAL_ENTRY SUCCESS. */
        private String tokenAtom(AsyncTokenId token) {
            return switch (token) {
                case AsyncTokenId.Canonical canonical -> "tok:" + canonical.tokenId()
                    + ":" + canonical.owner().name();
                case AsyncTokenId.Alias alias -> "alias:" + alias.tokenId() + "->"
                    + canonicalReferent(alias.referent());
            };
        }

        /** SUCCESS with a raw output atom expression (token atoms). */
        private void emitTokenSuccess(SemanticOp op, String atomExpr) {
            out.append("__ev(").append(luaString(opKey(op.opId())))
                .append(", \"SUCCESS\", ").append(luaString(op.kind().name()))
                .append(", ").append(luaString(op.contract().canonicalDigest()))
                .append(", ").append(luaString(parentKey(op.origin().parentOpId())))
                .append(", {}, ").append(atomExpr).append(", nil)\n");
        }

        /** A boundary START event with a raw input atom expression. */
        private void emitBoundaryStartAtom(SemanticOp boundary, String atomExpr) {
            out.append("__ev(").append(luaString(opKey(boundary.opId())))
                .append(", \"START\", \"BOUNDARY\", ")
                .append(luaString(boundary.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(boundary.origin().parentOpId())))
                .append(", {").append(atomExpr).append("}, nil, nil)\n");
        }

        /** A boundary SUCCESS event with a raw output atom expression. */
        private void emitBoundarySuccessAtom(SemanticOp boundary, String atomExpr) {
            out.append("__ev(").append(luaString(opKey(boundary.opId())))
                .append(", \"SUCCESS\", \"BOUNDARY\", ")
                .append(luaString(boundary.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(boundary.origin().parentOpId())))
                .append(", {}, ").append(atomExpr).append(", nil)\n");
        }

        /** The nested source ASYNC_START parented to an adapter-over-async op. */
        private SemanticOp nestedAsyncStartOf(SemanticOp outer) {
            for (SemanticOp candidate : opsById.values()) {
                if (candidate.kind() == SemanticOpKind.ASYNC_START
                        && outer.opId().equals(candidate.origin().parentOpId())) {
                    return candidate;
                }
            }
            throw new IllegalStateException("the adapter-over-async task has no nested "
                + "source ASYNC_START (producer defect)");
        }

        /**
         * ASYNC_START (E4, D13 task creation): the parameter boundaries
         * (the complete xN set under RUN — zero under
         * ELIDED_BY_ADAPTER), then exactly one task record per call. A
         * DEAL body task is a coroutine wrapping the body-task closure
         * (the canonical {@code AsyncTokenId} is the task record); an
         * adapter-over-async task resolves the D15 source, checks the
         * source signature, and executes the nested source op; a host
         * operation starts through the artifact's host-seam dispatch
         * entry (bad handle → the op's own E8010
         * {@code ASYNC_OPERATION_HANDLE}); an external operation starts
         * through the callee artifact's async-entry dispatch entry. The
         * op's terminal publishes the canonical/alias token atom.
         */
        private void emitAsyncStart(SemanticOp op) {
            KindPayload.AsyncStartPayload payload =
                (KindPayload.AsyncStartPayload) op.payload();
            AsyncTokenId token = (AsyncTokenId) op.result();
            emitStart(op);
            // The argument carrier: the boundary-checked values (RUN) or
            // the raw operand values (ELIDED_BY_ADAPTER) — a fresh table
            // per execution, captured by the task record. Each declared
            // parameter cell publishes its own FAILURE (with the owning
            // ASYNC_START's) at the boundary's origin before the error
            // propagates, exactly the oracle's boundary-child pair.
            if (payload.parameterBoundaryMode() == ParameterBoundaryMode.RUN) {
                out.append("S.__sa").append(op.opId().id()).append(" = {}\n");
                for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                    SemanticOp boundary = opsById.get(boundaryId);
                    KindPayload.BoundaryPayload boundaryPayload =
                        (KindPayload.BoundaryPayload) boundary.payload();
                    emitBoundaryStart(boundary, slot(boundaryPayload.input()),
                        boundaryPayload.descriptor());
                    out.append("__okB, __chkB = pcall(__bcheck, ")
                        .append(bcheckArgs(boundaryPayload.descriptor(),
                            slot(boundaryPayload.input())))
                        .append(")\n");
                    out.append("if not __okB then\n");
                    out.append("  __chkB.o = ").append(luaString(originOf(boundary)))
                        .append("\n");
                    emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                        "__errtext(__chkB)");
                    emitFailureEvent(op.opId(), op.kind().name(), op,
                        "__errtext(__chkB)");
                    out.append("  error(__chkB, 0)\n");
                    out.append("end\n");
                    emitBoundarySuccess(boundary, "__chkB", boundaryPayload.descriptor());
                    out.append("S.__sa").append(op.opId().id())
                        .append("[#S.__sa").append(op.opId().id())
                        .append(" + 1] = __chkB\n");
                }
            } else {
                out.append("S.__sa").append(op.opId().id()).append(" = {");
                for (int i = 0; i < op.operands().size(); i++) {
                    if (i > 0) {
                        out.append(", ");
                    }
                    out.append(slot(op.operands().get(i)));
                }
                out.append("}\n");
            }
            if (payload.callee() instanceof KindPayload.CallCallee.Dynamic dynamic) {

                emitDynamicAsyncStart(op, payload, dynamic, token);
                emitTokenSuccess(op, luaString(tokenAtom(token)));
                return;
            }
            FunctionExecutionBinding binding = switch (payload.callee()) {
                case KindPayload.CallCallee.Static staticCallee -> staticCallee.binding();
                case KindPayload.CallCallee.Indirect indirect -> {
                    FunctionExecutionBinding resolved = bindingOfIdentity(indirect.callee());
                    if (resolved == null) {
                        throw new IllegalStateException("ASYNC_START " + op.opId()
                            + " resolves the indirect callee identity " + indirect.callee()
                            + " to no registered FunctionExecutionBinding (producer defect)");
                    }
                    yield resolved;
                }
                default -> throw new IllegalStateException("ASYNC_START " + op.opId()
                    + " resolves a callee outside the statically-resolved slice: "
                    + payload.callee());
            };
            switch (binding) {
                case FunctionExecutionBinding.LoweredBody body -> {
                    LoweredFunction function = functionOf(body.functionId());
                    List<BindingGeneration> captures = function == null
                        ? List.of() : function.captures();
                    StringBuilder caps = new StringBuilder();
                    for (BindingGeneration capture : captures) {
                        if (caps.length() > 0) {
                            caps.append(", ");
                        }
                        caps.append(captureArg(capture));
                    }
                    out.append("__asyncStartTask(").append(token.tokenId())
                        .append(", \"DEAL_BODY_TASK\", coroutine.create(function()\n");
                    out.append("  table.insert(__frames, 1, ")
                        .append(luaString(String.valueOf(body.functionId().id())))
                        .append(")\n");
                    // The task's body execution is the async counterpart of
                    // the synchronous invocation: a recursive await of the
                    // same body runs the nested task inline (the FIFO drain)
                    // while the enclosing invocation is still live, so the
                    // callee body's private slots are saved before the body
                    // runs and restored on the success and failure paths.
                    String savedState = emitAsyncInvocationStateSave(body.functionId(),
                        op.opId(), "  ");
                    out.append("  local __okA, __resA = pcall(");
                    if (payload.callee()
                            instanceof KindPayload.CallCallee.Indirect indirect) {
                        // The value-carried async invocation (a nested group
                        // member with creation-site captures): the closure
                        // value the binding holds runs its own invoker, whose
                        // captured cells are the ones its creation published —
                        // never a call-site re-resolution of a per-creation
                        // incarnation.
                        out.append("__unfn(").append(slot(indirect.callee())).append(")");
                    } else {
                        out.append(fnFactory(body.functionId())).append("(")
                            .append(caps).append(")");
                    }
                    out.append(", unpack(S.__sa").append(op.opId().id())
                        .append(", 1, #S.__sa").append(op.opId().id()).append("))\n");
                    out.append("  table.remove(__frames, 1)\n");
                    emitAsyncInvocationStateRestore(body.functionId(), savedState, "  ");
                    out.append("  if not __okA then error(__resA, 0) end\n");
                    out.append("  return __resA\n");
                    out.append("end), S.__sa").append(op.opId().id()).append(")\n");
                }
                case FunctionExecutionBinding.AdapterBinding adapter -> {
                    // The outer adapter-over-async task (zero return
                    // boundaries of its own): the D15 source resolution
                    // and source-signature check, then the nested source
                    // op's full emission (its task queues under the FIFO
                    // drain — the outer task completes after it).
                    SemanticOp nested = nestedAsyncStartOf(op);
                    out.append("__asyncStartTask(").append(token.tokenId())
                        .append(", \"DEAL_BODY_TASK\", coroutine.create(function()\n");
                    out.append("  local __srcA = __adaptSource(")
                        .append(slot((ValueId) opsById.get(adapter.adaptOpId()).result()))
                        .append(")\n");
                    out.append("  __fncheck(__srcA, ")
                        .append(luaString(adapter.sourceSignature().canonicalSpecText()))
                        .append(", ").append(luaString(originOf(op))).append(")\n");
                    emitAsyncStart(nested);
                    out.append("  return nil\n");
                    out.append("end), S.__sa").append(op.opId().id()).append(")\n");
                }
                case FunctionExecutionBinding.HostFunction host -> {
                    if (hostSurface == null) {

                        emitAsyncHostSeamStart(op, token, host.hostModuleId().path(),
                            host.exportName());
                    } else {
                        emitAsyncHostStart(op, token,
                            hostSurfaceEntry(host.hostModuleId().path(),
                                host.exportName()),
                            asyncStartArgs(op, payload));
                    }
                }
                case FunctionExecutionBinding.HostFunctionValue hostValue -> {
                    if (hostSurface == null) {
                        emitAsyncHostSeamStart(op, token,
                            hostValue.hostModuleId().path(),
                            "@value#" + hostValue.materializingBoundaryOpId().id());
                    } else {
                        emitAsyncHostStart(op, token, hostValueTarget(hostValue),
                            asyncStartArgs(op, payload));
                    }
                }
                case FunctionExecutionBinding.ExternalFunction external ->
                    emitAsyncExternalStart(op, token, payload.externalAsyncLink());
                case FunctionExecutionBinding.IntrinsicFunction intrinsic -> {

                    String carrier = "S.__sa" + op.opId().id() + "[1]";
                    out.append("__asyncStartTask(").append(token.tokenId())
                        .append(", \"DEAL_BODY_TASK\", coroutine.create(function()\n");
                    out.append("  local __okA, __resA\n");
                    emitIntrinsicLadderPcall(op, intrinsic.kind(), carrier, "  ");
                    out.append("  if not __okA then error(__resA, 0) end\n");
                    out.append("  return __resA\n");
                    out.append("end), S.__sa").append(op.opId().id()).append(")\n");
                }
                case FunctionExecutionBinding.DynamicFunctionValue dynamic ->
                    throw new IllegalStateException("ASYNC_START " + op.opId()
                        + " resolves the dynamic function value produced by "
                        + dynamic.materializingOpId() + " outside the dynamic arm (a "
                        + "DynamicFunctionValue names no execution class and is never a "
                        + "statically resolved callee — producer defect)");
            }
            emitTokenSuccess(op, luaString(tokenAtom(token)));
        }

        /**
         * The completed argument slots of one {@code ASYNC_START} arm: the
         * per-position entries of the {@code S.__sa<op>} carrier table.
         * Each argument is passed as its own expression — a trailing
         * {@code unpack} would be truncated to one value in a non-final
         * argument position under Lua 5.1/LuaJIT semantics, silently
         * dropping arguments and colliding with the trailing span triplet.
         */
        private List<String> asyncStartArgs(SemanticOp op,
                KindPayload.AsyncStartPayload payload) {
            int count = payload.parameterBoundaryMode() == ParameterBoundaryMode.RUN
                ? payload.parameterBoundaryOpIds().size()
                : op.operands().size();
            List<String> args = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                args.add("S.__sa" + op.opId().id() + "[" + i + "]");
            }
            return args;
        }

        private void emitAsyncHostSeamStart(SemanticOp op, AsyncTokenId token,
                                            String module, String export) {
            KindPayload.AsyncStartPayload payload =
                (KindPayload.AsyncStartPayload) op.payload();
            String label = payload.hostOperationLabel();
            if (trace) {
                out.append("io.stderr:write(\"F|ASYNC_START_OP|-|\"..__esc(")
                    .append(luaString(label)).append(")..\"\\n\")\n");
                out.append("io.stderr:flush()\n");
            }
            out.append("local __handleH = __callbacks.__hostStartAsync(")
                .append(luaString(label)).append(", ")
                .append(luaString(module)).append(", ")
                .append(luaString(export)).append(", unpack(S.__sa")
                .append(op.opId().id()).append(", 1, #S.__sa")
                .append(op.opId().id()).append("))\n");
            out.append("if __handleH == nil then\n");
            out.append("  local __eH = __arm(\"ASYNC_SHAPE\", {actual = ")
                .append(luaString("nothing")).append("}, ")
                .append(luaString(originOf(op)))
                .append(", \"async operation\", \"nothing\")\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__eH)");
            out.append("  error(__eH, 0)\n");
            out.append("end\n");
            out.append("__asyncStartHost(").append(token.tokenId()).append(", ")
                .append(luaString(label)).append(")\n");
        }

        private void emitAsyncHostStart(SemanticOp op, AsyncTokenId token,
                                        String target, List<String> args) {
            KindPayload.AsyncStartPayload payload =
                (KindPayload.AsyncStartPayload) op.payload();
            String label = payload.hostOperationLabel();
            if (trace) {
                out.append("io.stderr:write(\"F|ASYNC_START_OP|-|\"..__esc(")
                    .append(luaString(label)).append(")..\"\\n\")\n");
                out.append("io.stderr:flush()\n");
            }
            // The converged host-boundary call shape, identical to the
            // sync arm: the trailing literal span triplet is how the
            // loaded wrapper's declared-async shape check reports the DEAL
            // call site byte-exact (the pinned E8010 at the call origin).
            out.append("__okT, __resT = pcall((").append(target).append(").f");
            for (String arg : args) {
                out.append(", ").append(arg);
            }
            out.append(", ").append(spanTripletArgs(op)).append(")\n");
            out.append("if not __okT then\n");
            out.append("  __resT = __hostError(__resT, ")
                .append(luaString(originOf(op))).append(")\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__resT)");
            out.append("  error(__resT, 0)\n");
            out.append("end\n");
            out.append("__asyncStartHost(").append(token.tokenId()).append(", ")
                .append(luaString(label)).append(", __resT)\n");
        }

        /**
         * The loaded surface entry of one declared host export: the
         * module identity is the surface key (H1), so no alias is needed
         * and the direct {@code ASYNC_START(HOST)}, the value-position
         * {@code ASYNC_START} on the read's registration, and a
         * {@code HostFunctionValue} crossing all resolve one entry.
         */
        private String hostSurfaceEntry(String modulePath, String exportName) {
            return "__exportSurfaces[" + luaString(modulePath) + "]["
                + luaString(exportName) + "]";
        }

        /**
         * The materialized host-facing function value of one
         * {@code HostFunctionValue} crossing ({@code .f} is the loaded
         * wrapper's calling convention, exactly as the sync arm's).
         */
        private String hostValueTarget(FunctionExecutionBinding.HostFunctionValue hostValue) {
            SemanticOp crossing = opsById.get(hostValue.materializingBoundaryOpId());
            if (crossing == null
                    || !(crossing.payload() instanceof KindPayload.BoundaryPayload boundary)) {
                throw new IllegalStateException("ASYNC_START names the materializing"
                    + " host crossing " + hostValue.materializingBoundaryOpId()
                    + ", which is not a boundary op of the emitted closure"
                    + " (a producer defect)");
            }
            return slot(boundary.input());
        }

        private void emitAsyncExternalStart(SemanticOp op, AsyncTokenId token,
                                            ExternalAsyncLink link) {
            if (link == null) {
                throw new IllegalStateException("ASYNC_START(EXTERNAL) without "
                    + "its ExternalAsyncLink (producer defect)");
            }
            if (projectSession) {
                resolveExternalAsyncEntry(op, link);
            }
            out.append("__asyncEntries[")
                .append(luaString(link.calleeModuleId().path() + "#"
                    + link.exportName())).append("](")
                .append(luaString(opKey(op.opId()))).append(", false, unpack(S.__sa")
                .append(op.opId().id()).append(", 1, #S.__sa")
                .append(op.opId().id()).append("))\n");
        }

        private void emitAwait(SemanticOp op) {
            KindPayload.AwaitPayload payload = (KindPayload.AwaitPayload) op.payload();
            long canonicalId = canonicalReferent(payload.token());
            SemanticOp boundary = opsById.get(payload.completionBoundaryOpId());
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) boundary.payload();
            emitStart(op);
            out.append("__asyncDrain()\n");
            out.append("__tA = __tasks[").append(canonicalId).append("]\n");
            out.append("if __tA == nil then\n");
            out.append("  error(\"AWAIT consumes an unbound token ")
                .append(payload.token()).append(" (producer defect)\", 0)\n");
            out.append("end\n");
            out.append("if __tA.status == 2 then\n");
            out.append("  if __tA.handle ~= nil then\n");

            out.append("    __okH, __errH = pcall(__rt.async_step, "
                + "__tA.handle)\n");
            out.append("    if not __okH then\n");
            out.append("      if type(__errH) == \"table\" and (__errH.__d "
                + "or (__errH.code ~= nil and __errH.message ~= nil)) then\n");
            out.append("        __tA.err = __hostError(__errH, ")
                .append(luaString(originOf(op))).append(")\n");
            if (trace) {
                out.append("        io.stderr:write(\"F|ASYNC_COMPLETE_THROW|-|\""
                    + "..__esc(__tA.label..\"!\"..__tA.err.code)..\"\\n\")\n");
                out.append("        io.stderr:flush()\n");
            }
            out.append("      else\n");
            out.append("        error(__errH, 0)\n");
            out.append("      end\n");
            out.append("    else\n");
            out.append("      __tA.value = __tA.handle.__result\n");
            if (trace) {
                out.append("      io.stderr:write(\"F|ASYNC_COMPLETE_RETURN|-|\""
                    + "..__esc(__tA.label..\"=\"..__hostAtom(__tA.value))..\"\\n\")\n");
                out.append("      io.stderr:flush()\n");
            }
            out.append("    end\n");
            out.append("  else\n");
            out.append("    __oA = "
                + "__callbacks.__hostCompleteAsync(__tA.label)\n");
            out.append("    if __oA.ok then\n");
            if (trace) {
                out.append("      io.stderr:write(\"F|ASYNC_COMPLETE_RETURN|-|\""
                    + "..__esc(__tA.label..\"=\"..__hostAtom(__oA.v))..\"\\n\")\n");
                out.append("      io.stderr:flush()\n");
            }
            out.append("      __tA.value = __oA.v\n");
            out.append("    else\n");
            if (trace) {
                out.append("      io.stderr:write(\"F|ASYNC_COMPLETE_THROW|-|\""
                    + "..__esc(__tA.label..\"!\"..__oA.code)..\"\\n\")\n");
                out.append("      io.stderr:flush()\n");
            }
            out.append("      __tA.err = {__d = true, code = __oA.code, m = __oA.m, o = ")
                .append(luaString(originOf(op)))
                .append(", e = nil, a = nil, f = __framesText(), cause = nil}\n");
            out.append("    end\n");
            out.append("  end\n");
            out.append("  __tA.status = 1\n");
            out.append("end\n");
            out.append("if __tA.err ~= nil then\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__tA.err)");
            out.append("  error(__tA.err, 0)\n");
            out.append("end\n");
            // The single ASYNC_COMPLETION boundary at the await site: a
            // host completion atomizes by its runtime carrier; a DEAL body
            // value atomizes by the declared descriptor. A boundary
            // failure is re-originated at the boundary's own origin (the
            // await expression) and publishes the boundary's FAILURE
            // beside the await op's — the oracle's boundary-child pair.
            if (canonicalOwnerOf(payload.token()) == AsyncTokenOwner.HOST_OPERATION) {
                emitBoundaryStartAtom(boundary, "__hostAtom(__tA.value)");
            } else {
                emitBoundaryStart(boundary, "__tA.value", boundaryPayload.descriptor());
            }
            out.append("__okB, __chkB = pcall(__bcheck, ")
                .append(luaString(descriptorText(boundaryPayload.descriptor())))
                .append(", ")
                .append(luaString(staticKind(boundaryPayload.descriptor())))
                .append(", __tA.value, ")
                .append(containsFunction(boundaryPayload.descriptor())
                    ? luaString(boundaryPayload.descriptor().canonicalSpecText())
                    : "nil")
                .append(", true)\n");
            out.append("if not __okB then\n");
            out.append("  __chkB.o = ").append(luaString(originOf(boundary))).append("\n");
            emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                "__errtext(__chkB)");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__chkB)");
            out.append("  error(__chkB, 0)\n");
            out.append("end\n");
            emitBoundarySuccess(boundary, "__chkB", boundaryPayload.descriptor());
            out.append(slot((ValueId) op.result())).append(" = __chkB\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
        }

        private void emitAsyncEntry(SemanticOp op, LoweredModuleUnit owner) {
            KindPayload.ExternalEntryPayload payload =
                (KindPayload.ExternalEntryPayload) op.payload();
            LoweredFunction function = owner.functions().get(payload.function());
            List<BindingGeneration> captures = function == null
                ? List.of() : function.captures();
            StringBuilder caps = new StringBuilder();
            for (BindingGeneration capture : captures) {
                if (caps.length() > 0) {
                    caps.append(", ");
                }
                caps.append(captureArg(capture));
            }
            String ownerPath = owner.moduleId().path();
            out.append("__asyncEntries[")
                .append(luaString(ownerPath + "#" + payload.exportName()))
                .append("] = function(__parentKey, __drive, ...)\n");

            emitModulePush();
            out.append("  __module = ").append(luaString(ownerPath)).append("\n");
            out.append("  local __eargs = {...}\n");
            if (trace) {
                out.append("  __ev(").append(luaString(opKey(op.opId())))
                    .append(", \"START\", ").append(luaString(op.kind().name()))
                    .append(", ").append(luaString(op.contract().canonicalDigest()))
                    .append(", __parentKey, {}, nil, nil)\n");
            }
            out.append("  __asyncStartTask(").append(op.opId().id())
                .append(", \"DEAL_BODY_TASK\", coroutine.create(function()\n");
            // The body task runs at the caller's AWAIT drain (or at the
            // entry's own drive below), so it establishes the callee
            // module itself and restores it on every path.
            emitModulePush();
            out.append("    __module = ").append(luaString(ownerPath)).append("\n");
            out.append("    table.insert(__frames, 1, ")
                .append(luaString(String.valueOf(payload.function().id())))
                .append(")\n");
            String savedState = emitInvocationStateSave(payload.function(), op.opId());
            out.append("    local __okA, __resA = pcall(")
                .append(fnFactory(payload.function())).append("(").append(caps)
                .append("), unpack(__eargs, 1, #__eargs))\n");
            out.append("    table.remove(__frames, 1)\n");
            emitInvocationStateRestore(payload.function(), savedState);
            emitModulePop();
            out.append("    if not __okA then error(__resA, 0) end\n");
            out.append("    return __resA\n");
            out.append("  end), __eargs)\n");
            if (trace) {
                out.append("  __ev(").append(luaString(opKey(op.opId())))
                    .append(", \"SUCCESS\", ").append(luaString(op.kind().name()))
                    .append(", ").append(luaString(op.contract().canonicalDigest()))
                    .append(", __parentKey, {}, \"tok:").append(op.opId().id())
                    .append(":DEAL_BODY_TASK\", nil)\n");
            }
            out.append("  if __drive then\n");
            out.append("    __asyncDrain()\n");
            out.append("    local __tE = __tasks[").append(op.opId().id()).append("]\n");
            // The caller context is restored before the entry returns or
            // raises: the drive's own failure is the caller's to observe.
            emitModulePop();
            out.append("    if __tE.err ~= nil then error(__tE.err, 0) end\n");
            out.append("    return __tE.value\n");
            out.append("  end\n");
            emitModulePop();
            out.append("end\n");
        }

        /**
         * ENTRY_INVOKE — delegates exactly one CALL(DIRECT) to main
         * (its owned child) and exits after the terminal. A delegated
         * failure publishes the ENTRY_INVOKE FAILURE terminal (the oracle's
         * own projection) before the error propagates to the module-init
         * wrapper's terminal.
         */
        private void emitEntryInvoke(SemanticOp op) {
            emitStart(op);
            out.append("__okE, __resE = pcall(function()\n");
            for (SemanticOp candidate : opsById.values()) {
                if (candidate.kind() == SemanticOpKind.CALL
                        && op.opId().equals(candidate.origin().parentOpId())) {
                    emitCall(candidate);
                }
            }
            out.append("end)\n");
            out.append("if not __okE then\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__resE)");
            out.append("  error(__resE, 0)\n");
            out.append("end\n");
            emitPlainSuccess(op);
        }

        private void emitModuleImport(SemanticOp op) {
            KindPayload.ModuleImportPayload payload =
                (KindPayload.ModuleImportPayload) op.payload();
            emitStart(op);
            if (payload.kind() == ModuleImportKind.HOST && hostSurface != null) {

                HostDeclarationSurface.DeclarationFacts facts =
                    hostSurface.require(payload.resolvedModule());
                if (facts.kind() == HostDeclarationSurface.DeclarationKind.EXTERN_C) {
                    if (ffiInput != null) {
                        emitFfiLoad(op, payload);
                    }
                } else {
                    emitHostLoad(op, payload, facts);
                }
            }
            emitAliasCellCompletion(payload);
            emitPlainSuccess(op);
        }

        /**
         * The {@code MODULE_IMPORT} completion write (K15 item 2): every
         * alias cell named by the payload receives the module's namespace
         * value — the module-identity-keyed {@code __exportSurfaces}
         * entry, the one surface object the module's publication or its
         * single load created, never a per-alias re-derivation — in the
         * cell's own publication discipline ({@code SHARED_CELL} writes
         * the captured cell table in place). The write runs after the
         * kind-specific load, so a HOST/FFI alias holds the loaded table.
         */
        private void emitAliasCellCompletion(KindPayload.ModuleImportPayload payload) {
            if (payload.aliasCells().isEmpty()) {
                return;
            }
            String surface = "__exportSurfaces["
                + luaString(payload.resolvedModule().path()) + "]";
            for (BindingId aliasCell : payload.aliasCells()) {
                BindingCellKind kind = cellKindOf(aliasCell, 0);
                if (kind == BindingCellKind.SHARED_CELL) {
                    out.append(cell(aliasCell, 0)).append("[1] = ")
                        .append(surface).append("\n");
                } else {
                    out.append(cell(aliasCell, 0)).append(" = ")
                        .append(surface).append("\n");
                }
            }
        }

        /**
         * The inline host load of one {@code MODULE_IMPORT(HOST)} op: the
         * declared map is read from the compile's host declaration
         * surface by the resolved module identity (the one descriptor
         * and declaration-order source — never a second producer), and
         * the origin is the import statement's span so the pinned E8011
         * failures carry the import origin.
         */
        private void emitHostLoad(SemanticOp op,
                                  KindPayload.ModuleImportPayload payload,
                                  HostDeclarationSurface.DeclarationFacts facts) {
            // A second alias of one host module emits the same guarded load
            // at its own import position: the first call is the only load of
            // this module per program, and every later import op re-writes
            // the already published surface entry through the same guard.
            ModuleId moduleId = payload.resolvedModule();
            String key = luaString(moduleId.path());
            out.append("__exportSurfaces[").append(key).append("] = ")
                .append("__exportSurfaces[").append(key).append("] or ")
                .append("__rt.load_host(").append(luaString(payload.rawSpecifier()))
                .append(", ").append(hostDeclaredMap(facts)).append(", ")
                .append(loadOriginArgs(op)).append(")\n");
            out.append("__nsSurface(__exportSurfaces[").append(key).append("])\n");
            // The loaded table is the import's namespace value, and its
            // declared function exports are its entries in declaration
            // order — the loader's own `pairs(declared)` build order is
            // not a declaration order — so the surface's order discipline
            // records exactly the entries the JVM host ABI surface writes
            // and the direct read path resolves.
            emitSurfaceOrder(key, declaredFunctionExports(facts));
            // The load's registry entry exists from this position on, so
            // a later binding of a provider alias naming this declaration
            // module captures the published loaded table.
            emittedDeclarationLoads.add(moduleId);
        }

        /** The declared function export names of one host declaration, in order. */
        private static List<String> declaredFunctionExports(
                HostDeclarationSurface.DeclarationFacts facts) {
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, Type> export : facts.exports().entrySet()) {
                if (export.getValue() instanceof Type.Func) {
                    names.add(export.getKey());
                }
            }
            return names;
        }

        /**
         * Records one surface's declared entry names in the namespace state
         * ({@code __nsOrder}): the emitted order list is the surface's
         * declared exports in declaration order, so TABLE_KEYS and the JSON
         * walker read the module's declared table. The write is idempotent,
         * so a second alias of one module re-records nothing, and a
         * surface the artifact never published (a torn or poisoned drive)
         * stays absent for the read path's own missing-entry row.
         */
        private void emitSurfaceOrder(String key, List<String> names) {
            if (names.isEmpty()) {
                return;
            }
            StringBuilder order = new StringBuilder("{");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    order.append(", ");
                }
                order.append(luaString(names.get(i)));
            }
            order.append("}");
            out.append("__nsOrder(__exportSurfaces[").append(key).append("], ")
                .append(order).append(")\n");
        }

        private void emitFfiLoad(SemanticOp op,
                                 KindPayload.ModuleImportPayload payload) {
            ModuleId moduleId = payload.resolvedModule();
            FfiGeneratedModule module = ffiInput.require(moduleId);
            int ordinal = ++ffiImportCounter;
            String bindingsLocal = "__ffi_bindings_" + ordinal;
            String importPrefix = "__ffi_import_" + ordinal + "_";
            LuaFfiBindingGenerator.Generation generation =
                LuaFfiBindingGenerator.generate(module,
                    ffiInput.manifestDirectory(), bindingsLocal,
                    importPrefix);
            if (generation.failure() != null) {
                throw new IllegalStateException("the extern-C generated"
                    + " module of '" + moduleId.path() + "' cannot be"
                    + " serialized into the load_ffi literals: "
                    + generation.failure().message());
            }
            LuaFfiBindingGenerator.LoadCallParts parts = generation.parts();
            requireOneProviderModulePerAlias(op, payload, moduleId, module);
            for (LuaFfiBindingGenerator.ProviderBinding provider
                    : LuaFfiBindingGenerator.providerBindings(
                        module.bindings())) {
                ModuleId providerId = new ModuleId(provider.importedModulePath());
                if (!isWrapperCapableProvider(providerId)) {
                    String consuming = consumingModulePathOf(op);
                    throw new FfiProviderGap(consuming,
                        "the extern-C import '" + payload.rawSpecifier()
                        + "' in the consuming module '" + consuming + "' at "
                        + importOriginText(op)
                        + " resolves the declaration module '"
                        + moduleId.path() + "' and binds the provider alias '"
                        + provider.importAlias() + "' to the module '"
                        + provider.importedModulePath() + "', which the"
                        + " artifact does not publish in the wrapper"
                        + " convention before the binding position: the"
                        + " provider is neither a session unit of the"
                        + " lowered closure nor a covered declaration"
                        + " module whose load the emission already emitted"
                        + " (a provider gap — a producer defect)");
                }
                out.append("local ").append(importPrefix)
                    .append(provider.importAlias())
                    .append(" = __exportSurfaces[")
                    .append(luaString(provider.importedModulePath()))
                    .append("] or {}\n");
            }
            out.append("local ").append(bindingsLocal).append(" = ")
                .append(parts.bindingsLiteral()).append("\n");
            String key = luaString(moduleId.path());
            out.append("__exportSurfaces[").append(key).append("] = ")
                .append("__exportSurfaces[").append(key)
                .append("] or __rt.load_ffi(")
                .append(parts.moduleKeyLiteral()).append(", ")
                .append(parts.bundleLiteral()).append(", ")
                .append(parts.plansLiteral()).append(", ")
                .append(bindingsLocal).append(", ")
                .append(loadOriginArgs(op)).append(")\n");
            out.append("__nsSurface(__exportSurfaces[").append(key).append("])\n");
            // The loaded extern-C table is the import's namespace value,
            // and its declared function exports are its entries in source
            // order (the runtime's `<exportName>_plan` entries are the
            // construction child's loaded plans, not declared exports), so
            // the surface's order discipline records exactly the entries
            // the runtime publishes for the declared functions.
            emitSurfaceOrder(key, ffiDeclaredFunctionExports(module));
            // The load's registry entry exists from this position on, so
            // a later binding of a provider alias naming this declaration
            // module captures the published loaded table.
            emittedDeclarationLoads.add(moduleId);
        }

        /** The declared function export names of one extern-C module, in order. */
        private static List<String> ffiDeclaredFunctionExports(
                FfiGeneratedModule module) {
            List<String> names = new ArrayList<>();
            for (FfiFunctionDescriptor function : module.descriptor().functions()) {
                names.add(function.dealName());
            }
            return names;
        }

        /**
         * The wrapper-capability predicate of one provider module (P2):
         * the artifact must publish the provider's export surface in the
         * wrapper convention before the binding position — either a
         * session unit of the one lowered closure (its surface object is
         * created at the chunk top and filled by the module's own
         * {@code EXPORT_PUBLISH} publication, so an entry published later
         * in the program is visible to the evaluator through the captured
         * object) or a covered declaration module whose load this walk
         * already emitted before the binding position (the loaded table
         * the registry holds when the binding captures it). Every other
         * provider is wrapper-incapable and fails the compile closed —
         * wrapper capability is a property of the artifact's publication,
         * never of the module kind alone.
         */
        private boolean isWrapperCapableProvider(ModuleId providerId) {
            return units.containsKey(providerId)
                || emittedDeclarationLoads.contains(providerId);
        }

        /**
         * The one-binding-per-alias invariant's fail-closed half (P1): one
         * import alias resolves exactly one provider module, so a
         * reference that names a provider module different from the
         * alias's already-bound provider module is a producer defect —
         * never a silent first-wins. The two reference lists are the
         * validator's frozen graph-ordered facts.
         */
        private void requireOneProviderModulePerAlias(SemanticOp op,
                KindPayload.ModuleImportPayload payload, ModuleId moduleId,
                FfiGeneratedModule module) {
            Map<String, String> aliasProviders = new LinkedHashMap<>();
            for (FfiImportedFunctionReference ref
                    : module.bindings().importedFunctions()) {
                requireStableAliasProvider(op, payload, moduleId,
                    aliasProviders, ref.importAlias(),
                    ref.importedModulePath());
            }
            for (FfiImportedClassPlanReference ref
                    : module.bindings().importedClassPlans()) {
                requireStableAliasProvider(op, payload, moduleId,
                    aliasProviders, ref.importAlias(),
                    ref.importedModulePath());
            }
        }

        /** One alias's provider-module agreement check. */
        private void requireStableAliasProvider(SemanticOp op,
                KindPayload.ModuleImportPayload payload, ModuleId moduleId,
                Map<String, String> aliasProviders, String importAlias,
                String importedModulePath) {
            String bound = aliasProviders.putIfAbsent(importAlias,
                importedModulePath);
            if (bound != null && !bound.equals(importedModulePath)) {
                String consuming = consumingModulePathOf(op);
                throw new FfiProviderGap(consuming,
                    "the extern-C import '" + payload.rawSpecifier()
                    + "' in the consuming module '" + consuming + "' at "
                    + importOriginText(op)
                    + " resolves the declaration module '"
                    + moduleId.path() + "' and binds the provider alias '"
                    + importAlias + "' to two provider modules ('" + bound
                    + "' and '" + importedModulePath + "'): one import"
                    + " alias resolves exactly one provider module (a"
                    + " provider gap — a producer defect)");
            }
        }

        /**
         * The consuming (emitting) module of one extern-C import op: the
         * module whose {@code MODULE_IMPORT} carries the extern-C import —
         * the module a provider-gap detail names.
         */
        private String consumingModulePathOf(SemanticOp op) {
            ModuleId owner = opModule.get(op.opId());
            if (owner == null) {
                throw new IllegalStateException("the extern-C import op "
                    + op.opId() + " carries no owning module: the provider"
                    + " gap cannot name its consuming module (a producer"
                    + " defect)");
            }
            return owner.path();
        }

        /**
         * The text of one import statement's origin (file, line, column)
         * for a fail-closed provider-gap message: the same origin the
         * load's own {@code FFI_LIBRARY_LOAD}/{@code FFI_SYMBOL_MISSING}
         * failures name. A span-less import op cannot name a real origin
         * and is a producer defect (the load's own origin requires the
         * import statement's span too).
         */
        private static String importOriginText(SemanticOp op) {
            SourceSpan span = op.origin().span();
            if (span == null) {
                throw new IllegalStateException("the extern-C import "
                    + op.opId() + " carries no source span: the provider-gap"
                    + " origin needs the import statement's own origin (a"
                    + " producer defect)");
            }
            return op.origin().sourceId() + ":" + span.startLine() + ":"
                + span.startColumn();
        }

        /**
         * The declared-map literal of one host declaration module: the
         * declared exports in declaration order, each with its canonical
         * runtime-descriptor text ({@code RuntimeDescriptor.Func} for a
         * function export, the canonical class descriptor for a class
         * export) — the single descriptor text the loader validates.
         */
        private String hostDeclaredMap(
                HostDeclarationSurface.DeclarationFacts facts) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Type> export : facts.exports().entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append("[").append(luaString(export.getKey()))
                    .append("] = ")
                    .append(luaString(hostDescriptorText(export.getValue())));
            }
            return sb.append("}").toString();
        }

        /**
         * The canonical descriptor text of one declared export type
         * (the compiled descriptor service — the only type-to-text
         * producer).
         */
        private String hostDescriptorText(Type type) {
            try {
                return DescriptorService.describe(type).canonicalSpecText();
            } catch (DescriptorService.Defect defect) {
                throw new IllegalStateException("a declared host export carries"
                    + " a type with no runtime representation: "
                    + defect.getMessage() + " (the declaration surface never"
                    + " carries one — a producer defect)", defect);
            }
        }

        /**
         * The literal origin triplet of a construction's call site: the
         * literal's own file, line, and column, so the pinned E8007 the
         * deployed construction entry raises names the literal origin (a
         * span-less op projects the retained {@code "-", 0, 0} triplet).
         */
        private String classLiteralOriginArgs(SemanticOp op) {
            SourceSpan span = op.origin().span();
            if (span == null) {
                return "\"-\", 0, 0";
            }
            return luaString(op.origin().sourceId()) + ", "
                + span.startLine() + ", " + span.startColumn();
        }

        /**
         * The literal origin triplet of a load's call site: the import
         * statement's own file, line, and column, so every E8011 the
         * loader raises names the import origin.
         */
        private String loadOriginArgs(SemanticOp op) {
            SourceSpan span = op.origin().span();
            if (span == null) {
                throw new IllegalStateException("the host import " + op.opId()
                    + " carries no source span: the pinned E8011 origin needs"
                    + " the import statement's own origin (a producer defect)");
            }
            return luaString(op.origin().sourceId()) + ", "
                + span.startLine() + ", " + span.startColumn();
        }

        private void emitModuleInit(SemanticOp op) {
            KindPayload.ModuleInitPayload payload =
                (KindPayload.ModuleInitPayload) op.payload();
            requireParentlessModuleInit(op);
            String stateKey = "__moduleStates[" + luaString(payload.module().path()) + "]";
            out.append("if ").append(stateKey).append(" == nil then\n");
            out.append(stateKey).append(" = \"INITIALIZING\"\n");
            emitStart(op);
            out.append("local __initOk, __initErr = pcall(function()\n");
            emitBlockOps(payload.initBlock());
            out.append("end)\n");
            out.append("if not __initOk then\n");
            out.append(stateKey).append(" = \"FAILED\"\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__initErr)");
            out.append("error(__initErr, 0)\n");
            out.append("end\n");
            out.append(stateKey).append(" = \"INITIALIZED\"\n");
            out.append("__ev(").append(luaString(opKey(op.opId())))
                .append(", \"SUCCESS\", \"MODULE_INIT\", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId())))
                .append(", {}, \"state:INITIALIZED\", nil)\n");
            out.append("elseif ").append(stateKey).append(" == \"INITIALIZED\" then\n");
            emitStart(op);
            out.append("__ev(").append(luaString(opKey(op.opId())))
                .append(", \"SUCCESS\", \"MODULE_INIT\", ")
                .append(luaString(op.contract().canonicalDigest())).append(", ")
                .append(luaString(parentKey(op.origin().parentOpId())))
                .append(", {}, \"state:INITIALIZED\", nil)\n");
            out.append("else\n");
            out.append("error(\"a second MODULE_INIT of module ")
                .append(payload.module().path())
                .append(" after a re-entrant or failed init (producer defect)\", 0)\n");
            out.append("end\n");
        }

        private void emitJsonPlan(ClassLayout layout) {
            out.append("__plans[").append(luaString(layout.classId().text()))
                .append("] = {classId = ").append(luaString(layout.classId().text()))
                .append(", fields = {\n");
            for (ClassLayout.FieldLayout field : layout.fields()) {
                out.append("  {name = ").append(luaString(field.name()))
                    .append(", desc = ").append(luaString(jsonDescriptorText(field.descriptor())))
                    .append(", optional = ").append(field.required() ? "false" : "true")
                    .append(", k = ").append(luaString(staticKind(field.descriptor())));
                SemanticOp defaultOp = classDefaultOpOf(layout.classId(), field.name());
                if (defaultOp != null && field.required()) {
                    out.append(", dk = ").append(luaString(opKey(defaultOp.opId())))
                        .append(", dd = ")
                        .append(luaString(defaultOp.contract().canonicalDigest()))
                        .append(", dfn = ").append(defaultFn(defaultOp.opId()));
                }
                out.append("},\n");
            }
            out.append("}}\n");
        }

        /**
         * The JSON plan descriptor text: the boundary descriptor text with
         * the JSON walk's {@code nullable:INNER} spelling (the walk's
         * closed kind grammar).
         */
        private static String jsonDescriptorText(RuntimeDescriptor descriptor) {
            if (descriptor instanceof RuntimeDescriptor.Nullable nullable) {
                return "nullable:" + jsonDescriptorText(nullable.inner());
            }
            if (descriptor instanceof RuntimeDescriptor.Array array) {
                return "array(" + jsonDescriptorText(array.element()) + ")";
            }
            return descriptorText(descriptor);
        }

        private void emitJsonFromClass(SemanticOp op) {
            KindPayload.JsonFromClassPayload payload =
                (KindPayload.JsonFromClassPayload) op.payload();
            emitStart(op);
            String target = slot((ValueId) op.result());
            out.append(target).append(" = __jsonFromClassOp(")
                .append(luaString(payload.layout().classId().text())).append(", ")
                .append(slot(payload.jsonString())).append(")\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType());
        }

        private void emitJsonToClass(SemanticOp op) {
            KindPayload.JsonToClassPayload payload =
                (KindPayload.JsonToClassPayload) op.payload();
            emitStart(op);
            // The walk's closed arm selection renders through the one
            // arm renderer (jsonable-tojson-walk-arm-binding W6): the walk
            // arm with its {fieldPath}/{actual} parameters, or the cycle
            // arm with none — the call site composes no message, no token,
            // and no span of its own, and the origin operand is the
            // invoking call expression (the generated body's synthetic
            // anchor is never the projection origin).
            out.append("__jokT, __jresT, __jarmT, __jcparT, __jfactT = "
                + "__jsonToClassOp(")
                .append(luaString(payload.layout().classId().text())).append(", ")
                .append(slot(payload.classValue())).append(")\n");
            out.append("if not __jokT then\n");
            out.append("__eT = __arm(__jarmT, __jcparT, __callOrigin(")
                .append(luaString(originOf(op))).append("), nil, __jfactT)\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "__errtext(__eT)");
            out.append("error(__eT, 0)\n");
            out.append("end\n");
            out.append(slot((ValueId) op.result())).append(" = __jresT\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
        }

        /**
         * The MODULE_INIT structural-parent contract: the module-level
         * envelope op is parentless (its trace parent is the absent
         * structural parent). A recorded parent is a producer defect —
         * the orchestrator's fail-closed gate converts the throw into
         * E6005, never a silent re-parenting.
         */
        private static void requireParentlessModuleInit(SemanticOp op) {
            if (op.origin().parentOpId() != null) {
                throw new IllegalStateException("MODULE_INIT " + op.opId()
                    + " records the structural parent " + op.origin().parentOpId()
                    + " (the module-level envelope op is parentless — a wrong parent is a "
                    + "producer defect, never a silent re-parenting)");
            }
        }

        /** The unit's lowerer-produced MODULE_INIT op, or null (hand-built units). */
        private SemanticOp moduleInitOpOf(LoweredModuleUnit moduleUnit) {
            for (SemanticOp op : moduleUnit.ops()) {
                if (op.kind() == SemanticOpKind.MODULE_INIT) {
                    return op;
                }
            }
            return null;
        }

        /**
         * {@code EXPORT_READ} — the per-kind read resolution (M2/M4/M5/M6):
         * a COMPILED read resolves the owning module's published value
         * through the chunk-global program-scoped export-surface registry
         * (the entry's {@code __val} field); a HOST/FFI read resolves the
         * same registry's entry itself — for a HOST module the surface is
         * the loaded module table {@code __rt.load_host} returns, so the
         * entry is the host ABI's value and the read never re-wraps it; an
         * absent surface or entry projects the {@code __MISSING} sentinel
         * in both cases; a STDLIB read publishes the in-target cataloged
         * callable of the closed catalog row (the one memoized accessor's
         * entry — the identical object the whole-surface population writes,
         * carrying the row's declared signature and canonical spec text,
         * never the reading site's). The emitted read expression is
         * identical in trace and production mode. In a project session a
         * COMPILED read whose owner module is not among the closure's units
         * is a producer defect and fails the emission closed; a per-unit
         * session never fails closed for a foreign owner (the owner's own
         * chunk publishes the surface of the same program).
         */
        private void emitExportRead(SemanticOp op) {
            KindPayload.ExportReadPayload payload =
                (KindPayload.ExportReadPayload) op.payload();

            ModuleImportKind kind = importKinds.get(payload.module());
            if (kind == ModuleImportKind.COMPILED && projectSession
                    && !units.containsKey(payload.module())) {
                throw new IllegalStateException("the compiled EXPORT_READ of export '"
                    + payload.name() + "' of module '" + payload.module().path()
                    + "' resolves an owner module outside the project session's"
                    + " closure (the one lowering resolves every COMPILED import to a"
                    + " closure module — a producer defect)");
            }
            emitStart(op);
            if (kind == ModuleImportKind.COMPILED) {
                out.append(slot((ValueId) op.result())).append(" = __exportValue(")
                    .append(luaString(payload.module().path())).append(", ")
                    .append(luaString(payload.name())).append(")\n");
            } else if (kind == ModuleImportKind.HOST) {
                out.append(slot((ValueId) op.result())).append(" = __exportHostValue(")
                    .append(luaString(payload.module().path())).append(", ")
                    .append(luaString(payload.name())).append(")\n");
            } else if (kind == ModuleImportKind.STDLIB) {
                // The read publishes the catalog row's callable entry itself
                // (M4): the memoized accessor the whole-surface population
                // uses, so the read and the surface hold the identical
                // object and the read never builds a per-read value.
                out.append(slot((ValueId) op.result())).append(" = __stdlibEntry(")
                    .append(stdlibRowArgs(stdlibRowOf(payload.module().path(),
                        payload.name()))).append(")\n");
            } else {

                String carrier = registeredIntrinsicCarrierExpr((ValueId) op.result());
                out.append(slot((ValueId) op.result())).append(" = ")
                    .append(carrier == null ? exportPlaceholderCarrier() : carrier)
                    .append("\n");
            }
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType());
        }
    }

    // =========================================================================
    // The Lua runtime prelude (byte-identical protocol output)
    // =========================================================================

    /**
     * The shared JSON algorithm realization (E7/K-D8/K-D10) of the
     * {@code JSON_FROM_CLASS}/{@code JSON_TO_CLASS} arms: the closed E8
     * RFC-8259 parse and canonical text algorithms plus the class walk
     * over the per-class plans the session records in {@code __plans}
     * (classId → plan: declaration-order fields with descriptor,
     * optionality, default-child metadata, and the owner factory
     * metadata of the nested-decode seam). The walk runs zero boundary
     * children; the class-default/class-factory events carry the plan's
     * recorded keys and digests. All syntax/decode/shape failures return
     * language null (the {@code JSON_FROM_NULL} projection); the
     * to-json walk returns the closed arm selection of its first
     * declaration-order failure ({@code armId, parameters, actual}) for
     * the walk family's {@code JSON_TO_ERROR} arms.
     */
    private static final String JSON_PRELUDE = """
-- ==== shared JSON algorithm (E7/K-D8/K-D10 realization) ====
-- The closed E8 RFC-8259 parse and canonical text algorithms plus the
-- flat-layout class walk of the JSON_FROM_CLASS/JSON_TO_CLASS arms. The
-- walk consumes the per-class plans the emitter records in __plans
-- (classId -> plan: declaration-order fields with descriptor,
-- optionality, and the per-field CLASS_DEFAULT child metadata).
-- Nested @jsonable class fields fail closed at emit time (a producer
-- defect naming the nested shape), never silently.
-- Byte-exactness: no backslash escapes appear in this source; quote and
-- backslash bytes are emitted through string.char.
local __JSONSYNTAX = setmetatable({}, {__tostring = function() return "json-syntax" end})
local __JNULL = setmetatable({}, {__tostring = function() return "json-null" end})
local __JSON_MAX_DEPTH = 512
local __plans = {}
local __BS = string.char(92)
local __QT = string.char(34)
local function __jsonIsObject(v)
  return type(v) == "table" and v.__jo == true
end
local function __jsonIsArray(v)
  return type(v) == "table" and v.__ja == true
end
local function __jsonEmptyObject()
  return {__jo = true, __jkeys = {}, __jnum = {}}
end
local function __jsonUtf8(cp)
  if cp < 0x80 then return string.char(cp) end
  if cp < 0x800 then
    return string.char(0xC0 + math.floor(cp / 0x40), 0x80 + cp % 0x40)
  end
  if cp < 0x10000 then
    return string.char(0xE0 + math.floor(cp / 0x1000),
      0x80 + math.floor(cp / 0x40) % 0x40, 0x80 + cp % 0x40)
  end
  return string.char(0xF0 + math.floor(cp / 0x40000),
    0x80 + math.floor(cp / 0x1000) % 0x40,
    0x80 + math.floor(cp / 0x40) % 0x40, 0x80 + cp % 0x40)
end
-- The E8 parse: every number carries its per-occurrence lexical variant
-- in the container's __jnum set — a digits-only in-range integer form is
-- the int variant, every other numeric form the number variant, exactly
-- the classification the shared parser and the JVM keep — so the
-- language carriers can mark their slots and the int descriptor admits
-- only integer lexical carriers; duplicate keys keep the last value, the
-- first position, and the last value's own variant; any syntax defect
-- returns __JSONSYNTAX.
local function __jsonParse(s)
  local pos = 1
  local len = #s
  local function skipws()
    while pos <= len do
      local b = string.byte(s, pos)
      if b == 32 or b == 9 or b == 10 or b == 13 then pos = pos + 1
      else break end
    end
  end
  local parseValue
  local function parseString()
    pos = pos + 1
    local out = {}
    while true do
      if pos > len then return nil end
      local c = string.sub(s, pos, pos)
      if c == __QT then
        pos = pos + 1
        return table.concat(out)
      elseif c == __BS then
        local e = string.sub(s, pos + 1, pos + 1)
        pos = pos + 2
        if e == __QT then out[#out + 1] = __QT
        elseif e == __BS then out[#out + 1] = __BS
        elseif e == "/" then out[#out + 1] = "/"
        elseif e == "b" then out[#out + 1] = string.char(8)
        elseif e == "f" then out[#out + 1] = string.char(12)
        elseif e == "n" then out[#out + 1] = string.char(10)
        elseif e == "r" then out[#out + 1] = string.char(13)
        elseif e == "t" then out[#out + 1] = string.char(9)
        elseif e == "u" then
          local hex = string.sub(s, pos, pos + 3)
          if #hex < 4 or not string.match(hex, "^%x%x%x%x$") then return nil end
          local cp = tonumber(hex, 16)
          pos = pos + 4
          if cp >= 0xD800 and cp <= 0xDBFF then
            if string.sub(s, pos, pos + 1) == __BS .. "u" then
              local hex2 = string.sub(s, pos + 2, pos + 5)
              if #hex2 == 4 and string.match(hex2, "^%x%x%x%x$") then
                local lo = tonumber(hex2, 16)
                if lo >= 0xDC00 and lo <= 0xDFFF then
                  pos = pos + 6
                  cp = 0x10000 + (cp - 0xD800) * 0x400 + (lo - 0xDC00)
                end
              end
            end
            if cp >= 0xD800 and cp <= 0xDFFF then return nil end
          elseif cp >= 0xDC00 and cp <= 0xDFFF then
            return nil
          end
          out[#out + 1] = __jsonUtf8(cp)
        else
          return nil
        end
      else
        local b = string.byte(c)
        if b < 32 then return nil end
        out[#out + 1] = c
        pos = pos + 1
      end
    end
  end
  local function parseNumber()
    local start = pos
    if string.sub(s, pos, pos) == "-" then pos = pos + 1 end
    if pos > len then return nil end
    local c = string.sub(s, pos, pos)
    if c == "0" then
      pos = pos + 1
      if pos <= len and string.match(string.sub(s, pos, pos), "%d") then
        return nil
      end
    elseif string.match(c, "[1-9]") then
      while pos <= len and string.match(string.sub(s, pos, pos), "%d") do
        pos = pos + 1
      end
    else
      return nil
    end
    local integral = true
    if pos <= len and string.sub(s, pos, pos) == "." then
      integral = false
      pos = pos + 1
      if pos > len or not string.match(string.sub(s, pos, pos), "%d") then
        return nil
      end
      while pos <= len and string.match(string.sub(s, pos, pos), "%d") do
        pos = pos + 1
      end
    end
    if pos <= len then
      local e = string.sub(s, pos, pos)
      if e == "e" or e == "E" then
        integral = false
        pos = pos + 1
        local sg = string.sub(s, pos, pos)
        if sg == "+" or sg == "-" then pos = pos + 1 end
        if pos > len or not string.match(string.sub(s, pos, pos), "%d") then
          return nil
        end
        while pos <= len and string.match(string.sub(s, pos, pos), "%d") do
          pos = pos + 1
        end
      end
    end
    local text = string.sub(s, start, pos - 1)
    local value = tonumber(text)
    if value == nil then return nil end
    if integral and value >= -2147483648 and value <= 2147483647 then
      -- The signed32 integer lexical form: the int variant with -0
      -- normalized to 0 (the shared parser's exact rule).
      if value == 0 then value = 0 end
      return value, false
    end
    return value, true
  end
  parseValue = function(depth)
    if depth > __JSON_MAX_DEPTH then return nil end
    skipws()
    if pos > len then return nil end
    local c = string.sub(s, pos, pos)
    if c == "{" then
      pos = pos + 1
      local obj = {__jo = true, __jkeys = {}, __jnum = {}}
      skipws()
      if pos <= len and string.sub(s, pos, pos) == "}" then
        pos = pos + 1
        return obj
      end
      while true do
        skipws()
        if pos > len or string.sub(s, pos, pos) ~= __QT then return nil end
        local key = parseString()
        if key == nil then return nil end
        skipws()
        if pos > len or string.sub(s, pos, pos) ~= ":" then return nil end
        pos = pos + 1
        local value, numberVariant = parseValue(depth + 1)
        if value == nil then return nil end
        if obj[key] == nil then obj.__jkeys[#obj.__jkeys + 1] = key end
        obj[key] = value
        if numberVariant then obj.__jnum[key] = true else obj.__jnum[key] = nil end
        skipws()
        if pos > len then return nil end
        c = string.sub(s, pos, pos)
        if c == "," then pos = pos + 1
        elseif c == "}" then pos = pos + 1; return obj
        else return nil end
      end
    elseif c == "[" then
      pos = pos + 1
      local arr = {__ja = true, __n = 0, __jnum = {}}
      skipws()
      if pos <= len and string.sub(s, pos, pos) == "]" then
        pos = pos + 1
        return arr
      end
      while true do
        local value, numberVariant = parseValue(depth + 1)
        if value == nil then return nil end
        arr.__n = arr.__n + 1
        arr[arr.__n] = value
        if numberVariant then arr.__jnum[arr.__n] = true end
        skipws()
        if pos > len then return nil end
        c = string.sub(s, pos, pos)
        if c == "," then pos = pos + 1
        elseif c == "]" then pos = pos + 1; return arr
        else return nil end
      end
    elseif c == __QT then
      local str = parseString()
      if str == nil then return nil end
      return str
    elseif c == "t" then
      if string.sub(s, pos, pos + 3) == "true" then pos = pos + 4; return true end
      return nil
    elseif c == "f" then
      if string.sub(s, pos, pos + 4) == "false" then pos = pos + 5; return false end
      return nil
    elseif c == "n" then
      if string.sub(s, pos, pos + 3) == "null" then pos = pos + 4; return __JNULL end
      return nil
    elseif c == "-" or string.match(c, "%d") then
      return parseNumber()
    end
    return nil
  end
  local value = parseValue(0)
  if value == nil then return __JSONSYNTAX end
  skipws()
  if pos <= len then return __JSONSYNTAX end
  return value
end
-- The Java-Double.toString-compatible canonical number text (shortest
-- round-trippable digits, always one fractional digit, E-notation
-- outside [1e-3, 1e7)).
local function __jsonNumText(v)
  if v ~= v or v == math.huge or v == -math.huge then return nil end
  if v == 0 then
    if 1 / v < 0 then return "-0.0" end
    return "0.0"
  end
  local sign = ""
  if v < 0 then sign = "-"; v = -v end
  local digits = nil
  local exp = nil
  for p = 1, 17 do
    local text = string.format("%." .. (p - 1) .. "e", v)
    if tonumber(text) == v then
      local lead, rest, e = string.match(text, "^(%d)%.?(%d*)e([%+%-]?%d+)$")
      if lead ~= nil then
        digits = lead .. rest
        exp = tonumber(e)
        break
      end
    end
  end
  if digits == nil then
    return nil
  end
  digits = string.gsub(digits, "0+$", "")
  if digits == "" then digits = "0" end
  local text
  if exp >= -3 and exp < 7 then
    if exp >= 0 then
      if #digits > exp + 1 then
        text = string.sub(digits, 1, exp + 1) .. "." .. string.sub(digits, exp + 2)
      else
        text = digits .. string.rep("0", exp + 1 - #digits) .. ".0"
      end
    else
      text = "0." .. string.rep("0", -exp - 1) .. digits
    end
  else
    local mantissa
    if #digits > 1 then
      mantissa = string.sub(digits, 1, 1) .. "." .. string.sub(digits, 2)
    else
      mantissa = string.sub(digits, 1, 1) .. ".0"
    end
    text = mantissa .. "E" .. tostring(exp)
  end
  return sign .. text
end
local function __jsonEscapeString(s)
  local out = {}
  for i = 1, #s do
    local b = string.byte(s, i)
    if b == 34 then out[#out + 1] = __BS .. __QT
    elseif b == 92 then out[#out + 1] = __BS .. __BS
    elseif b == 8 then out[#out + 1] = __BS .. "b"
    elseif b == 12 then out[#out + 1] = __BS .. "f"
    elseif b == 10 then out[#out + 1] = __BS .. "n"
    elseif b == 13 then out[#out + 1] = __BS .. "r"
    elseif b == 9 then out[#out + 1] = __BS .. "t"
    elseif b < 32 then out[#out + 1] = __BS .. "u" .. string.format("%04x", b)
    else out[#out + 1] = string.sub(s, i, i) end
  end
  return table.concat(out)
end
local function __jsonKindOf(desc)
  if string.sub(desc, 1, 6) == "array(" then return "array", string.sub(desc, 7, -2) end
  if string.sub(desc, 1, 9) == "nullable:" then return "nullable", string.sub(desc, 10) end
  if string.sub(desc, 1, 1) == "@" then return "class", desc end
  return desc, nil
end
local function __jsonKnownField(plan, name)
  local fields = plan.fields
  for i = 1, #fields do
    if fields[i].name == name then return true end
  end
  return false
end
-- One CLASS_DEFAULT child execution with its own START/terminal events
-- (the detached default op's recorded key/digest, parent "-").
local function __jsonDefaultRun(f)
  __ev(f.dk, "START", "CLASS_DEFAULT", f.dd, "-", {}, nil, nil)
  local ok, produced = pcall(f.dfn)
  if not ok then
    __ev(f.dk, "FAILURE", "CLASS_DEFAULT", f.dd, "-", {}, nil, __errtext(produced))
    return false
  end
  __ev(f.dk, "SUCCESS", "CLASS_DEFAULT", f.dd, "-", {}, __atom(f.k, produced), nil)
  return true, produced
end
-- The language carrier of one parsed JSON subtree: the shared table
-- carrier's full marker set — the presence map __keys, the
-- first-insertion order list __order (the TABLE_NEW / JSON_PARSE
-- carriers'), and the per-slot number marks __nK — so every carrier
-- consumer (TABLE_KEYS, JSON_STRINGIFY, the member/index writes and
-- deletes, and the read-side variant materialization) works on a
-- decoded table exactly as on a constructed one. The marks follow the
-- parser's per-occurrence lexical int/number classification; a nested
-- container carries its own markers.
local function __jsonToLanguage(v)
  if __jsonIsArray(v) then
    local out = {__a = true, __n = v.__n, __nK = {}}
    for i = 1, v.__n do
      local element = v[i]
      if element == __JNULL then out[i] = __NULL
      elseif __jsonIsObject(element) or __jsonIsArray(element) then
        out[i] = __jsonToLanguage(element)
      else
        out[i] = element
        if v.__jnum[i] == true then out.__nK[i] = true end
      end
    end
    return out
  end
  local out = {__t = true, __keys = {}, __order = {}, __nK = {}}
  for i = 1, #v.__jkeys do
    local key = v.__jkeys[i]
    local element = v[key]
    __orderAdd(out, key)
    if element == __JNULL then out[key] = __NULL
    elseif __jsonIsObject(element) or __jsonIsArray(element) then
      out[key] = __jsonToLanguage(element)
    else
      out[key] = element
      if v.__jnum[key] == true then out.__nK[key] = true end
    end
  end
  return out
end
-- The final descriptor conformance check of the walk (K-D8 step 7).
local function __jsonConforms(desc, v)
  local kind, inner = __jsonKindOf(desc)
  if kind == "null" then return v == nil end
  if kind == "boolean" then return type(v) == "boolean" end
  if kind == "int" then
    return type(v) == "number" and v == math.floor(v)
      and v >= -2147483648 and v <= 2147483647
  end
  if kind == "number" then return type(v) == "number" end
  if kind == "string" then return type(v) == "string" and __jsonValidUtf8(v) end
  if kind == "table" then return type(v) == "table" and v.__t == true end
  if kind == "array" then
    if type(v) ~= "table" or v.__a ~= true then return false end
    for i = 1, v.__n do
      local element = v[i]
      if element == __NULL then element = nil end
      if element == nil then return false end
      if not __jsonConforms(inner, element) then return false end
    end
    return true
  end
  if kind == "nullable" then
    if v == nil then return true end
    return __jsonConforms(inner, v)
  end
  if kind == "class" then
    -- A nested @jsonable class value: the tagged instance of exactly
    -- the declared class identity (the canonical nominal check).
    return type(v) == "table" and v.__c == true and v.__id == inner
  end
  return true
end
-- The provided-field decode of one declared descriptor (K-D8 step 4).
-- The fourth argument is the raw value's own number variant (the
-- parser's per-occurrence lexical classification; meaningful for
-- numbers): an int descriptor admits only an integer lexical carrier
-- (the oracle's Int-only rule), a number descriptor admits both forms
-- and publishes the number variant (its Int case converts exactly) —
-- so every decoded scalar keeps its own variant, and the decoded
-- container carriers record their slots' variants for the
-- read/write/JSON_STRINGIFY consumers. The nested @jsonable class arm
-- decodes through the nested class's own plan (the same phase order).
local __jsonDecodeClass
local function __jsonDecodeRaw(desc, v, depth, numberVariant)
  if depth > __JSON_MAX_DEPTH then return false end
  local kind, inner = __jsonKindOf(desc)
  if kind == "null" then
    if v == __JNULL then return true, nil end
    return false
  elseif kind == "boolean" then
    if type(v) == "boolean" then return true, v end
    return false
  elseif kind == "int" then
    if type(v) == "number" and not numberVariant and v == math.floor(v)
        and v >= -2147483648 and v <= 2147483647 then
      return true, v, "int"
    end
    return false
  elseif kind == "number" then
    if type(v) == "number" then return true, v, "number" end
    return false
  elseif kind == "string" then
    if type(v) == "string" and __jsonValidUtf8(v) then return true, v end
    return false
  elseif kind == "table" then
    if __jsonIsObject(v) then return true, __jsonToLanguage(v) end
    if __jsonIsArray(v) and v.__n == 0 then
      return true, {__t = true, __keys = {}, __order = {}, __nK = {}}
    end
    return false
  elseif kind == "array" then
    if not __jsonIsArray(v) then return false end
    local out = {__a = true, __n = v.__n, __nK = {}}
    for i = 1, v.__n do
      local ok, element, elementVariant =
        __jsonDecodeRaw(inner, v[i], depth + 1, v.__jnum[i] == true)
      if not ok then return false end
      out[i] = (element == nil) and __NULL or element
      if elementVariant == "number" then out.__nK[i] = true end
    end
    return true, out
  elseif kind == "nullable" then
    if v == __JNULL then return true, nil end
    return __jsonDecodeRaw(inner, v, depth, numberVariant)
  elseif kind == "class" then
    -- The nested @jsonable class decode (K-D8 step 6): the nested
    -- class's own plan drives the same phase order, and its omitted
    -- required-present defaults run their recorded CLASS_DEFAULT
    -- functions. An unresolvable nested plan is a producer defect.
    local nested = __plans[inner]
    if nested == nil then
      error("JSON_FROM_CLASS has no plan " .. inner .. " for a nested @jsonable "
        .. "class field (producer defect)", 0)
    end
    if __jsonIsArray(v) and v.__n == 0 then v = __jsonEmptyObject() end
    if not __jsonIsObject(v) then return false end
    local ok, inst = __jsonDecodeClass(nested, v, depth + 1)
    if not ok then return false end
    return true, inst
  end
  return false
end
local function __jsonInstanceOf(plan, fields, present, values)
  local inst = {__c = true, __id = plan.classId, __f = {}, __p = {}}
  for i = 1, #fields do
    local f = fields[i]
    if present[f.name] then
      local value = values[f.name]
      inst.__f[f.name] = (value == nil) and __NULL or value
      inst.__p[f.name] = true
    end
  end
  return inst
end
-- The class decode of one JSON object (K-D8): the extra-key gate in
-- document order, the provided-field decode in declaration order, the
-- omitted required-present defaults (their recorded CLASS_DEFAULT
-- functions), the final descriptor validation, and the tagged
-- instance. Shared by the top-level walk and the nested @jsonable class
-- field decode (the nested plan's own walk).
__jsonDecodeClass = function(plan, doc, depth)
  if depth > __JSON_MAX_DEPTH then return false end
  local fields = plan.fields
  for i = 1, #doc.__jkeys do
    if not __jsonKnownField(plan, doc.__jkeys[i]) then return false end
  end
  local values = {}
  local provided = {}
  for i = 1, #fields do
    local f = fields[i]
    if doc[f.name] ~= nil then
      local ok, decoded = __jsonDecodeRaw(f.desc, doc[f.name], depth,
        doc.__jnum[f.name] == true)
      if not ok then return false end
      values[f.name] = decoded
      provided[f.name] = true
    end
  end
  for i = 1, #fields do
    local f = fields[i]
    if not provided[f.name] and not f.optional then
      if f.dfn == nil then return false end
      local ok, produced = __jsonDefaultRun(f)
      if not ok then return false end
      values[f.name] = produced
      provided[f.name] = true
    end
  end
  for i = 1, #fields do
    local f = fields[i]
    if provided[f.name] and not __jsonConforms(f.desc, values[f.name]) then
      return false
    end
  end
  return true, __jsonInstanceOf(plan, fields, provided, values)
end
-- The JSON_FROM_CLASS walk: the tagged instance, or language null on
-- every listed failure (the JSON_FROM_NULL projection).
local function __jsonFromClassOp(planName, text)
  local plan = __plans[planName]
  if plan == nil then
    error("JSON_FROM_CLASS has no plan " .. planName .. " (producer defect)", 0)
  end
  local doc = __jsonParse(text)
  if doc == __JSONSYNTAX or doc == __JNULL then return nil end
  if __jsonIsArray(doc) and doc.__n == 0 then doc = __jsonEmptyObject() end
  if not __jsonIsObject(doc) then return nil end
  local ok, inst = __jsonDecodeClass(plan, doc, 0)
  if not ok then return nil end
  return inst
end
-- The JSON_TO_CLASS walk: (true, text) or (false, nil, armId,
-- parameters, actual) for the JSON_TO_ERROR projection. Table fields
-- serialize their present keys in ascending key order (the shared Lua
-- table carrier records key presence, not insertion order).
-- The innermost active call's origin, or the fallback: the generated
-- C$toJson body's JSON_TO_ERROR projection renders the call that
-- invoked it, never the generated body's synthetic anchor.
local function __callOrigin(fallback)
  if #__callOrigins > 0 then return __callOrigins[#__callOrigins] end
  return fallback
end
local function __jsonToClassOp(planName, root)
  local plan = __plans[planName]
  if plan == nil then
    error("JSON_TO_CLASS has no plan " .. planName .. " (producer defect)", 0)
  end
  local failureArm = nil
  local failureParams = nil
  local failureActual = nil
  -- The walk's closed arm selection (jsonable-tojson-walk-arm-binding W3):
  -- the walk arm at the walk's own checks, the cycle arm at a path-local
  -- container re-entry needle. The needle's own closed marker selects the
  -- arm, never a token comparison, and every token comes from the prelude's
  -- typed-boundary projection implementation — this walk composes no text,
  -- no token, and no span of its own.
  local function failure()
    return false, nil, failureArm, failureParams, failureActual
  end
  local function fail(path, v)
    local actual = __typedBoundaryKind("", v)
    failureArm = "JSON_TO_WALK"
    failureParams = {fieldPath = path, actual = actual}
    failureActual = actual
    return nil
  end
  local function cycle()
    failureArm = "JSON_TO_WALK_CYCLE"
    failureParams = nil
    failureActual = nil
    return nil
  end
  local encodeField
  local encodeTableValue
  local encodeArrayValue
  local encodeClass
  encodeTableValue = function(tv, tpath, visited)
    if visited[tv] then return cycle() end
    visited[tv] = true
    local keys = {}
    for k, _ in pairs(tv.__keys) do keys[#keys + 1] = k end
    table.sort(keys)
    local out = {"{"}
    for i = 1, #keys do
      local key = keys[i]
      local element = tv[key]
      if element == __NULL then element = nil end
      if i > 1 then out[#out + 1] = "," end
      out[#out + 1] = __QT .. __jsonEscapeString(key) .. __QT .. ":"
      local elementPath = (tpath == "") and key or (tpath .. "." .. key)
      if element == nil then out[#out + 1] = "null"
      elseif type(element) == "boolean" then out[#out + 1] = tostring(element)
      elseif type(element) == "number" then
        local text = __jsonNumText(element)
        if text == nil then return fail(elementPath, element) end
        out[#out + 1] = text
      elseif type(element) == "string" then
        if not __jsonValidUtf8(element) then
          return fail(elementPath, element)
        end
        out[#out + 1] = __QT .. __jsonEscapeString(element) .. __QT
      elseif type(element) == "table" then
        if element.__a then
          local nested = encodeArrayValue(element, elementPath, visited)
          if nested == nil then return nil end
          out[#out + 1] = nested
        elseif element.__t then
          local nested = encodeTableValue(element, elementPath, visited)
          if nested == nil then return nil end
          out[#out + 1] = nested
        else
          return fail(elementPath, element)
        end
      else
        return fail(elementPath, element)
      end
    end
    visited[tv] = nil
    out[#out + 1] = "}"
    return table.concat(out)
  end
  encodeArrayValue = function(av, apath, visited, edesc)
    if visited[av] then return cycle() end
    visited[av] = true
    local out = {"["}
    for i = 1, av.__n do
      local element = av[i]
      if element == __NULL then element = nil end
      local elementPath = apath .. "[" .. (i - 1) .. "]"
      if i > 1 then out[#out + 1] = "," end
      if edesc ~= nil then
        -- A declared array descriptor walks its elements through the
        -- declared element descriptor (the oracle's per-element rule):
        -- an int element spells its integer text, a nested array
        -- recurses the element descriptor, and a class element walks
        -- its class plan.
        local encoded = encodeField(edesc, element, elementPath, visited)
        if encoded == nil then return nil end
        out[#out + 1] = encoded
      elseif element == nil then out[#out + 1] = "null"
      elseif type(element) == "boolean" then out[#out + 1] = tostring(element)
      elseif type(element) == "number" then
        local text = __jsonNumText(element)
        if text == nil then return fail(elementPath, element) end
        out[#out + 1] = text
      elseif type(element) == "string" then
        if not __jsonValidUtf8(element) then
          return fail(elementPath, element)
        end
        out[#out + 1] = __QT .. __jsonEscapeString(element) .. __QT
      elseif type(element) == "table" then
        if element.__a then
          local nested = encodeArrayValue(element, elementPath, visited)
          if nested == nil then return nil end
          out[#out + 1] = nested
        elseif element.__t then
          local nested = encodeTableValue(element, elementPath, visited)
          if nested == nil then return nil end
          out[#out + 1] = nested
        else
          -- A class instance in a descriptor-free walk — table content
          -- that is not JSON-shaped data — is rejected here: table-field
          -- contents are JSON objects, arrays, and null/boolean/int/
          -- number/string leaves, so a nested @jsonable instance
          -- serializes only through a declared class/array element
          -- descriptor (the encodeField/encodeClass path).
          return fail(elementPath, element)
        end
      else
        return fail(elementPath, element)
      end
    end
    visited[av] = nil
    out[#out + 1] = "]"
    return table.concat(out)
  end
  -- The class walk shared by the root and every nested @jsonable class
  -- field (K-D10): the declared fields in declaration order, omitted
  -- optionals skipped, a missing required field failing, present null
  -- spelled as JSON null, and every present field encoded per its
  -- declared descriptor. The class instance enters the path-local
  -- visited set, so a class re-entry on the path is the cycle needle.
  encodeClass = function(instance, classId, cpath, visited)
    if type(instance) ~= "table" or instance.__c ~= true
        or instance.__id ~= classId then
      return fail(cpath, instance)
    end
    local cplan = __plans[classId]
    if cplan == nil then
      error("JSON_TO_CLASS has no plan " .. classId .. " for a nested @jsonable "
        .. "class field (producer defect)", 0)
    end
    if visited[instance] then return cycle() end
    visited[instance] = true
    local fields = cplan.fields
    local out = {"{"}
    local first = true
    for i = 1, #fields do
      local f = fields[i]
      local fieldPath = (cpath == "") and f.name or (cpath .. "." .. f.name)
      if not instance.__p[f.name] then
        if not f.optional then
          -- The internal nil propagation: the callers (a nested field's
          -- encodeField, the array element walk) check nil and return nil
          -- themselves, so only the root converts the walk's failure
          -- state through failure().
          fail(fieldPath, __MISSING)
          return nil
        end
      else
        local value = instance.__f[f.name]
        -- The present-null admission: the carrier's __NULL sentinel is
        -- the language null the declared descriptor then decides —
        -- a nullable position admits it and spells JSON null (the corpus
        -- fixtures' pinned three-state roundtrip), while a non-nullable
        -- position keeps the closed typed-boundary "null" projection
        -- (the typed-boundary projection classifies the value as null,
        -- never as the sentinel's table spelling).
        if value == __NULL then value = nil end
        local encoded = encodeField(f.desc, value, fieldPath, visited)
        if encoded == nil then return nil end
        if not first then out[#out + 1] = "," end
        first = false
        out[#out + 1] = __QT .. __jsonEscapeString(f.name) .. __QT .. ":"
        out[#out + 1] = encoded
      end
    end
    visited[instance] = nil
    out[#out + 1] = "}"
    return table.concat(out)
  end
  encodeField = function(desc, v, path, visited)
    local kind, inner = __jsonKindOf(desc)
    if kind == "null" then
      if v == nil then return "null" end
      return fail(path, v)
    elseif kind == "boolean" then
      if type(v) == "boolean" then return tostring(v) end
      return fail(path, v)
    elseif kind == "int" then
      -- An int-position value is the int32 carrier: a finite integral
      -- number. A fractional or nonfinite number (and every non-number)
      -- is the number/other carrier and fails here exactly like the
      -- oracle's Value.Int admission.
      if type(v) == "number" and v == math.floor(v)
          and v ~= math.huge and v ~= -math.huge then
        return tostring(v)
      end
      return fail(path, v)
    elseif kind == "number" then
      if type(v) == "number" then
        local text = __jsonNumText(v)
        if text == nil then return fail(path, v) end
        return text
      end
      return fail(path, v)
    elseif kind == "string" then
      if type(v) ~= "string" then return fail(path, v) end
      if not __jsonValidUtf8(v) then return fail(path, v) end
      return __QT .. __jsonEscapeString(v) .. __QT
    elseif kind == "nullable" then
      if v == nil then return "null" end
      return encodeField(inner, v, path, visited)
    elseif kind == "table" then
      if type(v) ~= "table" or v.__t ~= true then return fail(path, v) end
      return encodeTableValue(v, path, visited)
    elseif kind == "array" then
      if type(v) ~= "table" or v.__a ~= true then return fail(path, v) end
      return encodeArrayValue(v, path, visited, inner)
    elseif kind == "class" then
      return encodeClass(v, inner, path, visited)
    end
    return fail(path, v)
  end
  if root == nil or type(root) ~= "table" or root.__c ~= true
      or root.__id ~= plan.classId then
    fail("", root)
    return failure()
  end
  local encodedRoot = encodeClass(root, plan.classId, "", {})
  if not encodedRoot then
    return failure()
  end
  return true, encodedRoot, nil, nil, nil
end
""";

    private static final String HOST_BOUNDARY_PRELUDE = """
-- The production chunk is the v1.2 profile: the deployed runtime's int32
-- gate uses the pinned v1.2 template ("int out of safe range") for the
-- host cells' int rows, exactly like the emitted boundary checks' own
-- gate. The flag is process-wide and idempotent.
__rt.__INT32 = true
-- The actual-kind token of one host-crossing value (the pinned E8010
-- projections' {actual}): the runtime carrier's own kind, never the Lua
-- table spelling of a DEAL function or number carrier.
local function __hostKindOf(v)
  return __carrierKind(v)
end
-- The carried class identity of one class value in either
-- representation: the chunk's __c/__id carrier or the loaded runtime's
-- __kind/__classname carrier; nil for every other value. The class-value
-- carrier projection keys on this, so a class-typed crossing never
-- consults the runtime matcher's class row (which requires the runtime
-- representation the DEAL program never holds).
local function __hostClassIdOf(v)
  if type(v) ~= "table" then return nil end
  if v.__kind == "class" then return v.__classname end
  if v.__c then return v.__id end
  return nil
end
-- One host parameter/return cell failure: the host arm's composite with the
-- inner reason, the declared cell descriptor, and the carrier-kind
-- projection.
local function __hostCellFailure(index, inner, desc, v, origin, isReturn)
  if isReturn then
    return error(__arm("HOST_SYNC_RETURN_CELL", {inner = inner}, origin, desc,
      __carrierKind(v)), 0)
  end
  return error(__arm("HOST_PARAMETER_CELL", {index = index, inner = inner},
    origin, desc, __carrierKind(v)), 0)
end
-- The declared cell of one class-typed host-crossing position: a value
-- carrying the declared identity passes unchanged in either
-- representation; every other value fails through the pinned E8010
-- projection with the declared identity as expected and the carried
-- identity (or the canonical kind token) as actual — never the runtime
-- matcher's E8001.
local function __hostClassCell(desc, index, v, origin, isReturn)
  local carried = __hostClassIdOf(v)
  if carried == nil then
    -- The descriptor-kind inner reason of the class cell: the typed-boundary
    -- kind arm's own text with the class kind.
    local inner = __renderTemplate("TYPED_BOUNDARY_KIND", {kind = "class instance"})
    return __hostCellFailure(index, inner, desc, v, origin, isReturn)
  end
  if carried ~= desc then
    local inner = __renderTemplate("CLASS_IDENTITY", {expected = desc, actual = carried})
    return __hostCellFailure(index, inner, desc, v, origin, isReturn)
  end
  return v
end
-- The chunk-to-wrapper class-value carrier projection (F4): one fresh
-- runtime-representation value built from the source representation's own
-- complete field map — the identity text is the crossing position's
-- declared descriptor, a nested pointer field is projected by the same
-- rule, and a pointer token's own __ptr is carried. The source value is
-- never mutated; a value already carrying the runtime representation
-- passes through. No null mapping exists inside this bridge: C_STRUCT and
-- C_POINTER positions are non-nullable by the declaration policy.
local function __hostClassProject(desc, v)
  if type(v) ~= "table" then return v end
  if v.__kind == "class" then return v end
  if not v.__c then return v end
  local out = {__kind = "class", __classname = desc}
  if v.__ptr ~= nil then out.__ptr = v.__ptr end
  if type(v.__f) == "table" then
    for k, val in pairs(v.__f) do
      if type(val) == "table" and val.__c then
        out[k] = __hostClassProject(val.__id, val)
      else
        out[k] = val
      end
    end
  end
  return out
end
-- The wrapper-to-chunk class-value carrier projection (F4): the declared
-- identity text tags one fresh chunk-representation value whose declared
-- field set is the runtime instance's own non-tag keys (its phase-1
-- admission and phase-2 fill make them exactly the declared fields) and
-- whose pointer token carries its pointer. The source value is never
-- mutated; a value already carrying the chunk representation passes
-- through. The projection runs before the crossing's events, so the trace
-- observes exactly one heap value per class-typed crossing.
local function __hostDealProject(desc, v, origin)
  if type(v) ~= "table" then return v end
  if v.__c then return v end
  if v.__kind ~= "class" then return v end
  if v.__classname ~= desc then
    -- The bridge guard keeps its landed E8010 code while its text is the
    -- identity arm's own template (no composed spelling).
    return error(__failExpr("E8010",
      __renderTemplate("CLASS_IDENTITY", {expected = desc,
        actual = tostring(v.__classname)}), origin, desc, v.__classname), 0)
  end
  local out = {__c = true, __id = desc, __f = {}, __p = {}}
  for k, val in pairs(v) do
    if k ~= "__classname" and k ~= "__kind" and k ~= "__ptr" then
      if type(val) == "table" and val.__kind == "class" then
        out.__f[k] = __hostDealProject(val.__classname, val, origin)
      else
        out.__f[k] = val
      end
      out.__p[k] = true
    end
  end
  if v.__ptr ~= nil then out.__ptr = v.__ptr end
  return out
end
-- The declared parameter cell of a function-typed host position: the
-- loaded matcher's own rule over the DEAL function carrier (byte-exact
-- carried canonical descriptor) or a host-facing wrapper; the pinned
-- E8010 text is the loaded wrapper's own parameter projection.
local function __hostFnParam(desc, inner, index, v, origin)
  local function kindReason()
    return __renderTemplate("TYPED_BOUNDARY_KIND", {kind = "function"})
  end
  if v == nil or v == __NULL then
    if string.sub(desc, 1, 1) == "?" then return __rt.__NULL end
    return error(__arm("HOST_PARAMETER_CELL", {index = index, inner = kindReason()},
      origin, desc, __carrierKind(v)), 0)
  end
  local carried = nil
  if type(v) == "table" then
    if v.__csig ~= nil then carried = v.__csig
    elseif v.__kind == "function" then carried = v.sig end
  end
  if carried == nil then
    return error(__arm("HOST_PARAMETER_CELL", {index = index, inner = kindReason()},
      origin, desc, __carrierKind(v)), 0)
  end
  if carried ~= inner then
    return error(__arm("HOST_PARAMETER_CELL", {index = index,
      inner = __renderTemplate("FUNCTION_SIGNATURE_MISMATCH",
        {expected = inner, actual = carried})}, origin, desc, __carrierKind(v)), 0)
  end
  return v
end
-- The declared parameter cell of one host call: the loaded runtime's own
-- matcher (the wrapper's cell), projected through the pinned E8010
-- parameter template at the call origin. The crossing normalizes the
-- chunk's language-null sentinel to the deployed runtime's own (the two
-- sentinels are distinct values) and back on the return path.
local function __hostParamCell(desc, index, v, origin)
  if v == __NULL then v = __rt.__NULL end
  if string.sub(desc, 1, 1) == "@" then
    return __hostClassCell(desc, index, v, origin, false)
  end
  local ok, checked = pcall(__rt.check_type, desc, v)
  if ok then
    if checked == __rt.__NULL then return __NULL end
    return checked
  end
  local inner = type(checked) == "table" and checked.message or tostring(checked)
  return error(__arm("HOST_PARAMETER_CELL", {index = index, inner = inner},
    origin, desc, __carrierKind(v)), 0)
end
-- The declared-kind event atom of one host-crossing value (the
-- declared-cell boundaries of the host arms): the deployed runtime's
-- null sentinel and the chunk's own sentinel are the language null, so
-- a null position atomizes as "null" exactly like the oracle's
-- NullValue. The value-derived atom (the completion-value effect) is
-- the prelude's own 1-argument __hostAtom — this helper is
-- deliberately named apart so the two never shadow each other in a
-- chunk that carries both.
local function __hostCellAtom(kind, v)
  if v == nil or v == __NULL or v == __rt.__NULL then return "null" end
  return __atom(kind, v)
end
-- The declared return cell of one host call: the loaded wrapper's own
-- return rule, projected through the pinned E8010 return template, then
-- the host-to-DEAL projection of the admitted value (the language-null
-- sentinel and a declared array position's shared array carrier).
local function __hostToDealArray(v)
  if type(v) ~= "table" or v.__a then return v end
  local out = {__a = true, __n = 0, __nK = {}}
  local n = 0
  for i = 1, #v do
    local element = v[i]
    if element == __rt.__NULL then element = __NULL end
    out[i] = element
    n = i
  end
  out.__n = n
  return out
end
local function __hostReturnCell(desc, v, origin, nothing)
  if nothing and v == nil then
    return error(__arm("HOST_SYNC_RETURN_NOTHING", {expected = desc}, origin, desc,
      "nothing"), 0)
  end
  if string.sub(desc, 1, 1) == "@" then
    return __hostClassCell(desc, 0, v, origin, true)
  end
  local ok, checked = pcall(__rt.check_type, desc, v)
  if not ok then
    local inner = type(checked) == "table" and checked.message or tostring(checked)
    return error(__arm("HOST_SYNC_RETURN_CELL", {inner = inner}, origin, desc,
      __carrierKind(v)), 0)
  end
  if checked == __rt.__NULL then return nil end
  if string.sub(desc, 1, 1) == "[" or string.sub(desc, 1, 2) == "?[" then
    return __hostToDealArray(checked)
  end
  return checked
end
-- The DEAL function value call the host performs through the declared
-- function bridge: the carrier's own body runs (its own return cell
-- applies), an adapter carrier runs the D15 protocol.
local function __dealFnValue(w, ...)
  if w.__mode ~= nil then return __adaptInvoke(w, "-", ...) end
  return w.__fn(...)
end
-- The host-facing projection of one declared function position (H7): the
-- declared host wrapper class's LuaJIT twin — a plain Lua function the
-- host calls; a host-facing wrapper passes through unadapted and anything
-- else is left to the parameter cell.
local function __hostFnArg(desc, inner, v)
  if type(v) ~= "table" then return v end
  if v.__kind == "function" then return v end
  if v.__fn == nil then return v end
  local carrier = v
  return {__kind = "function", sig = inner,
          f = function(...) return __dealFnValue(carrier, ...) end}
end
-- The declared host-facing projection of one checked parameter: a
-- declared function position is bridged, every other position's carrier
-- is the Lua value itself (the shared Lua table in both directions). The
-- crossing normalizes the chunk's language-null sentinel to the
-- deployed runtime's own for the loaded matcher.
local function __hostProjectArg(desc, inner, v)
  if v == __NULL then return __rt.__NULL end
  if v == __rt.__NULL then return v end
  if v == nil then return v end
  if inner ~= nil and (string.sub(inner, 1, 1) == "("
      or string.sub(inner, 1, 9) == "async(") then
    return __hostFnArg(desc, inner, v)
  end
  if string.sub(desc, 1, 1) == "@" then
    return __hostClassProject(desc, v)
  end
  return v
end
--- The canonical DEAL error table of one host-boundary failure: the
-- loaded runtime raises the DEALRuntimeError shape ({code, message,
-- file, line, column, expected, actual}); the emitted chunk speaks the
-- __d-tagged carrier every consumer projects, so the host arm converts
-- it once at the boundary and rethrows the identical code, message,
-- expected, actual, and origin.
local function __hostError(e, origin)
  if type(e) ~= "table" then return e end
  if e.__d then return e end
  if e.code == nil or e.message == nil then return e end
  local o = origin
  if e.file ~= nil then
    o = tostring(e.file)..":"..tostring(e.line)..":"..tostring(e.column)
  end
  return __failExpr(e.code, e.message, o, e.expected, e.actual)
end
-- The loaded <C>_defaults entry of one declared host class (ISSUE-0624;
-- semantic-ir-construct-coverage-cutover K10): the declaring module's
-- published surface is the module table the one production load returned
-- (__rt.load_host, E8011 when the mandatory defaults entry is missing or
-- non-table), and the class's defaults entry is that copy-through. An
-- absent surface (the declaring module's MODULE_IMPORT(HOST) load has not
-- run) is a fail-closed producer defect: the load precedes every
-- construction in dependency order.
local function __hostClassDefaults(module, class)
  local surface = __exportSurfaces[module]
  if type(surface) ~= "table" then
    error("the host module '"..module.."' has no published surface before the"
      .." construction of '"..class.."' (the MODULE_IMPORT(HOST) load precedes"
      .." every construction in dependency order — a producer defect)", 0)
  end
  return surface[class.."_defaults"]
end
-- The loaded <C>_plan entry of one declared extern-C C-struct class
-- (ISSUE-0666; struct-plan F1): the declaring module's published surface
-- is the loaded module table the one production load returned
-- (__rt.load_ffi, which retains the validated plan per class under the
-- runtime's own <C>_plan key), and the construction calls
-- __rt.class_plan_ over that entry. An absent surface (the declaring
-- module's MODULE_IMPORT load has not run) is a fail-closed producer
-- defect: the load precedes every construction in dependency order. An
-- absent entry stays the runtime entry's own E8001 plan-shape failure —
-- the loaded plan is the only construction authority, never a silent
-- default.
local function __ffiClassPlan(module, class)
  local surface = __exportSurfaces[module]
  if type(surface) ~= "table" then
    error("the extern-C module '"..module.."' has no published surface before"
      .." the construction of '"..class.."' (the MODULE_IMPORT load precedes"
      .." every construction in dependency order — a producer defect)", 0)
  end
  return surface[class.."_plan"]
end
""";

    private static final String BYTES_PRELUDE = """
-- The bytes allocation intrinsic (K6 item 1): the pinned E8012
-- non-negative gate at the bytes(...) call expression, then the runtime's
-- zero-filled carrier.
-- The runtime error -> the canonical failure carrier (the deployed
-- runtime's own (file, line, column) wins when present, exactly like the
-- host prelude's conversion); a site that raises its own failure never
-- consults this.
local function __rtFail(e, origin)
  if type(e) ~= "table" then return e end
  if e.__d then return e end
  if e.code == nil or e.message == nil then return e end
  local o = origin
  if e.file ~= nil then
    o = tostring(e.file)..":"..tostring(e.line)..":"..tostring(e.column)
  end
  return __failExpr(e.code, e.message, o, e.expected, e.actual)
end
__bytesNew = function(length, evKind, opKey, digest, parent, origin, src, line, col)
  local n = __num(length)
  if n < 0 then
    local e = __arm("BYTES_ALLOCATE", nil, origin, nil, nil)
    __ev(opKey, "FAILURE", evKind, digest, parent, {}, nil, __errtext(e))
    error(e, 0)
  end
  local ok, b = pcall(__rt.bytes_new, n, src, line, col)
  if not ok then
    local e = __rtFail(b, origin)
    __ev(opKey, "FAILURE", evKind, digest, parent, {}, nil, __errtext(e))
    error(e, 0)
  end
  return b
end
-- The ARRAY_LENGTH read over a bytes receiver (K6 item 7): the fixed
-- logical length through the runtime entry.
local function __bytesLength(v, opKey, digest, parent, origin, src, line, col)
  local ok, n = pcall(__rt.bytes_length, v, src, line, col)
  if not ok then
    local e = __rtFail(n, origin)
    __ev(opKey, "FAILURE", "ARRAY_LENGTH", digest, parent, {}, nil, __errtext(e))
    error(e, 0)
  end
  return n
end
-- The bytes element read (K6 item 2): the ARRAY_LENGTH + BYTES_READ
-- normalize + INDEX_READ shape with its BYTE_ELEMENT_READ cell. The
-- bounds failure (E8012 "bytes index out of bounds") projects the
-- boundary and read FAILURE events at the index expression, and the read
-- at i == b.length fails exactly like the negative index. The cell's
-- input is the missing sentinel out of bounds (the probe never reads out
-- of bounds) and the byte otherwise.
local function __bytesRead(opKey, digest, parent, bKey, bDigest, bParent, container,
                           slotName, origin, src, line, col)
  local index = slotName.i
  local elem
  if index < 0 or index >= slotName.n then
    elem = __MISSING
  else
    elem = __rt.bytes_get(container, index, src, line, col)
  end
  local atom = (elem == __MISSING) and "missing" or ("int:"..tostring(elem))
  __ev(bKey, "START", "BOUNDARY", bDigest, bParent, {atom}, nil, nil)
  if elem == __MISSING then
    local e = __arm("BYTES_READ", nil, origin, nil, nil)
    __ev(bKey, "FAILURE", "BOUNDARY", bDigest, bParent, {}, nil, __errtext(e))
    __ev(opKey, "FAILURE", "INDEX_READ", digest, parent, {}, nil, __errtext(e))
    error(e, 0)
  end
  __ev(bKey, "SUCCESS", "BOUNDARY", bDigest, bParent, {}, atom, nil)
  return elem
end
-- The bytes write's BYTE_ELEMENT_ASSIGNMENT cell (K6 item 4): the bounds
-- check first (E8012 at the index expression), then the element
-- descriptor check; the commit owns the E8013 range check and the single
-- mutation.
local function __bytesBounds(bKey, bDigest, bParent, input, slotName, desc, kind, origin)
  local index = slotName.i
  __ev(bKey, "START", "BOUNDARY", bDigest, bParent, {__atom(kind, input)}, nil, nil)
  if index < 0 or index >= slotName.n then
    local e = __arm("BYTES_WRITE_BOUNDS", nil, origin, nil, nil)
    __ev(bKey, "FAILURE", "BOUNDARY", bDigest, bParent, {}, nil, __errtext(e))
    error(e, 0)
  end
  local ok, checked = pcall(__bcheck, desc, kind, input)
  if not ok then
    __ev(bKey, "FAILURE", "BOUNDARY", bDigest, bParent, {}, nil, __errtext(checked))
    error(checked, 0)
  end
  __ev(bKey, "SUCCESS", "BOUNDARY", bDigest, bParent, {}, __atom(kind, input), nil)
end
-- The bytes write's commit (K6 item 4): the E8013 value-range check and
-- the single in-place mutation through the runtime entry, at the
-- assignment-expression origin. A failed write changes no storage.
local function __bytesCommit(opKey, digest, parent, container, slotName, value, origin,
                             src, line, col)
  local ok, written = pcall(__rt.bytes_set, container, slotName.i, __num(value), src,
    line, col)
  if not ok then
    local e = __rtFail(written, origin)
    __ev(opKey, "FAILURE", "INDEX_WRITE", digest, parent, {}, nil, __errtext(e))
    error(e, 0)
  end
  return written
end
""";

    /**
     * The serialized closed failure-arm table (canonical failure-projection
     * authority P1/P4): the registry's arms, verbatim — id, code, template,
     * expected-token source, actual projection, render scope, and origin.
     * The table is the only failure-text source of the emitted chunk and is
     * compared against {@link FailureContractRegistry#canonicalArmSerialization()}
     * (no emitter-side fork).
     */
    private static String armTableLiteral() {
        StringBuilder out = new StringBuilder();
        out.append("local __arms = {\n");
        for (FailureArm arm : FailureContractRegistry.arms().values()) {
            out.append("  [").append(Session.luaString(arm.id().name())).append("] = {c=")
                .append(Session.luaString(FailureContractRegistry.codeOf(arm))).append(", t=")
                .append(Session.luaString(arm.template())).append(", e=")
                .append(Session.luaString(arm.expectedSource().name())).append(", a=")
                .append(Session.luaString(arm.actualProjection().name())).append(", s=")
                .append(Session.luaString(arm.scope().name())).append(", o=")
                .append(Session.luaString(arm.origin().name())).append(", k=")
                .append(Session.luaString(
                    FailureContractRegistry.parameterSourcesText(arm)));
            if (arm.pinnedExpectedText() != null) {
                // The arm's own pinned expected text (its expected field's
                // single source): the prelude renders it, never a literal
                // held at a failure site.
                out.append(", p=")
                    .append(Session.luaString(arm.pinnedExpectedText()));
            }
            out.append("},\n");
        }
        out.append("}\n");
        return out.toString();
    }

    /** The prelude with the serialized closed arm table spliced in. */
    private static String preludeWithArms() {
        String head = PRELUDE.replace("__ARM_TABLE__", armTableLiteral());
        return head + PRELUDE_ARM_RENDERER;
    }

    private static final String PRELUDE = """
-- ==== shared runtime prelude ====
local __MISSING = setmetatable({}, {__tostring = function() return "missing" end})
local __NULL = setmetatable({}, {__tostring = function() return "null" end})
-- The deployed runtime's own language-null sentinel (deal/runtime.lua's
-- __rt.__NULL — a value distinct from this chunk's __NULL). The chunk's
-- runtime binding assigns it when the chunk binds the deployed runtime
-- (a host or bytes session); a host-free chunk leaves it nil. The
-- typed-boundary projection classifies it as the language null exactly
-- like the chunk's own sentinel, so a runtime-produced carrier (a
-- host-crossed table entry, a runtime class field) reaching a failing
-- walk position renders "null", never the table spelling.
local __rtNull = nil
-- The deployed runtime's own absent-marker sentinel (deal/runtime.lua's
-- __rt.__MISSING — a value distinct from this chunk's __MISSING). The
-- chunk's runtime binding assigns it when the chunk binds the deployed
-- runtime; a host-free chunk leaves it nil. The typed-boundary projection
-- classifies it as the absent marker exactly like the chunk's own
-- sentinel, so a runtime-produced carrier (a host-crossed table entry, a
-- runtime class field) reaching a failing walk position renders "nil",
-- never the table spelling.
local __rtMissing = nil
-- The member-read helper (ISSUE-0239 E10): a present key yields the
-- stored value (a present null is the plain nil stored by the write
-- paths); an absent key yields the internal MISSING sentinel — exactly
-- JvmRuntime.Table.read's present/absent split, so the contextual
-- table-read boundary decides null-vs-error identically on both
-- targets. The __keys marker sub-table is the same one the TABLE_NEW /
-- MEMBER_WRITE / MEMBER_DELETE / INDEX_WRITE / INDEX_DELETE paths
-- maintain, so read and write presence stay consistent. A class
-- instance (E5: __c marker, __id tag, __p presence map, __f field
-- values with the __NULL sentinel for a present null) reads through
-- its presence map — present null yields nil and stays distinguishable
-- from an absent key via the presence map (has()/FIELD_READ).
-- A module export surface (the K15 namespace value an import alias cell
-- holds: a compiled module's publication table, a stdlib module's
-- cataloged-callable table, or the runtime-loaded host/FFI module table)
-- is the module's table in the shared table discipline without carrying a
-- compiler mark of its own: its entries stay exactly the module's
-- declared exports (the retained-caller ABI table and every publication
-- enumeration keep the reads child's payload), and the shared table
-- consumers read it through this identity-keyed namespace state. Its
-- presence is the entry's own non-nil slot (an entry is never a stored
-- nil), and the published value of an entry is __nsEntry's projection: a
-- compiled module's entry is the cross-chunk ABI wrapper ({__kind, sig,
-- f, __val}) whose __val is the identical value the direct __exportValue
-- read resolves, while a cataloged callable and a loaded host/FFI entry
-- carry no __val and are the entry itself. The state records the entry
-- order (the module's declared exports in declaration order plus every
-- later member/index write), the recorded keys, and the number-variant
-- marks of the entries written after creation. The registry is
-- chunk-global like the export-surface registry, so a surface a previous
-- chunk of the same process created keeps its state.
__nsSurfaces = __nsSurfaces or {}
local function __nsSurface(t)
  -- A torn or poisoned artifact whose load line never published the
  -- surface keeps it absent: the read path's own missing-entry row is the
  -- fail-closed projection, exactly as it is for an unpublished surface.
  if t == nil then return t end
  if __nsSurfaces[t] == nil then
    __nsSurfaces[t] = {keys = {}, order = {}, numbers = {}}
  end
  return t
end
local function __nsEntry(t, v)
  if __nsSurfaces[t] ~= nil and type(v) == "table" and v.__val ~= nil then
    return v.__val
  end
  return v
end
-- Records one surface's declared entry names in its namespace state (the
-- declared exports in declaration order): the emitted order list is the
-- emit-time fact, never the loader's own key order. The write is
-- idempotent (a name already recorded is not appended again), so a second
-- alias of one module re-records nothing, and an absent surface (a torn
-- or poisoned artifact whose load line never published it) stays absent —
-- the read path's own missing-entry row is the fail-closed projection.
local function __nsOrder(t, names)
  if t == nil then return t end
  __nsSurface(t)
  local ns = __nsSurfaces[t]
  for i = 1, #names do
    if not ns.keys[names[i]] then
      ns.order[#ns.order + 1] = names[i]
    end
    ns.keys[names[i]] = true
  end
  return t
end
local function __member(t, k)
  if t.__c then
    if t.__p[k] then
      local v = t.__f[k]
      if v == __NULL then return nil end
      return v
    end
    return __MISSING
  end
  local __ns = __nsSurfaces[t]
  if __ns ~= nil then
    -- The module namespace surface resolves in both of its branches: a
    -- member write to the surface (which records its key in the
    -- namespace state like any other table records __keys/__order) never
    -- hides the published entries, and the entry is projected through
    -- __nsEntry.
    local v = t[k]
    if v == nil then return __MISSING end
    return __nsEntry(t, v)
  end
  local __keys = t.__keys
  if __keys ~= nil then
    if __keys[k] then return t[k] end
    return __MISSING
  end
  local v = t[k]
  if v == nil then return __MISSING end
  return v
end
local function __esc(s)
  if s == nil then return "" end
  local out = {}
  for i = 1, #s do
    local c = string.sub(s, i, i)
    local b = string.byte(c)
    if c == "\\\\" then out[#out + 1] = "\\\\\\\\"
    elseif c == "\\n" then out[#out + 1] = "\\\\n"
    elseif c == "\\t" then out[#out + 1] = "\\\\t"
    elseif c == "\\r" then out[#out + 1] = "\\\\r"
    elseif c == ";" then out[#out + 1] = "\\\\u003b"
    elseif c == "|" then out[#out + 1] = "\\\\u007c"
    elseif b < 32 or b == 127 then
      out[#out + 1] = string.format("\\\\u%04x", b)
    else out[#out + 1] = c end
  end
  return table.concat(out)
end
local function __framesText()
  if #__frames == 0 then return "-" end
  return table.concat(__frames, ",")
end
local function __allocId(v)
  local id = __allocIds[v]
  if id == nil then
    id = __allocNext
    __allocNext = __allocNext + 1
    __allocIds[v] = id
  end
  return id
end
local __intrinsicInvoke
-- The bytes allocation helper (K6 item 1) is declared as a forward local
-- so the carrier's generic invoker below resolves it; the bytes prelude
-- assigns it when the chunk carries a bytes op, exactly the
-- __intrinsicInvoke pattern.
local __bytesNew
-- The intrinsics' memoized carriers (J2; published by the
-- seed's BINDING_INIT, the adapter's producer-less VALUE operand and a
-- residual export-read kind arm whose identity carries a seed
-- registration): one callable, class-tagged
-- carrier per intrinsic kind per program, carrying the intrinsic's
-- declared descriptor text and canonical spec text and no function id,
-- so the landed function row (__bcheck/__fncheck) admits it by its
-- carried signature, __unfn exposes its invoker, and every
-- materialization of one intrinsic is the identical value (functions
-- compare by reference identity). The kind tag `it` and the declared
-- signature texts `sig`/`csig` come from the unit's own
-- IntrinsicFunction registration — never a spelling, never a call
-- site's or an adapter target's signature.
local function __intrinsicFn(it, sig, csig)
  local c = __intrinsicCarriers[it]
  if c == nil then
    c = {__sig = sig, __csig = csig, __fid = nil, __it = it}
    c.__fn = function(...) return __intrinsicInvoke(it, ...) end
    __intrinsicCarriers[it] = c
  end
  return c
end
-- The residual export-read kind arm's placeholder carrier (a session
-- whose unit records no import fact for the read's module — the
-- test-only class-core carrier sessions): a fresh, per-read value
-- carrying the landed opaque export view the oracle projects for that
-- arm (a ()->number function) and the real carrier's interface, so the
-- landed function row admits it exactly as the oracle's view does and
-- the read keeps its own identity (the landed arm's per-read
-- allocation). The memoized intrinsic carrier is published at this arm
-- only when the read's identity carries an IntrinsicFunction
-- registration (the only kind authority).
local function __intrinsicExport()
  return {__fn = function() return nil end, __sig = "function(;number)",
    __csig = "()->number", __fid = nil}
end
-- The compiled export read (M2): the nil-safe accessor of the
-- chunk-global program-scoped export-surface registry. A published
-- entry exposes the compiler-owned __val field (the published value
-- itself — the entry's f projection stays the retained-caller ABI's raw
-- callable); an absent surface, an absent entry, or an entry without a
-- published value projects the __MISSING sentinel, which atomizes as
-- "missing" — exactly the oracle's Value.MissingValue and the JVM
-- runtime's MISSING. The read allocates, wraps, and copies nothing.
local function __exportValue(module, name)
  local surface = __exportSurfaces[module]
  if surface == nil then return __MISSING end
  local entry = surface[name]
  if entry == nil then return __MISSING end
  local value = entry.__val
  if value == nil then return __MISSING end
  return value
end
-- The host/FFI export read (M5): the module surface's entry itself. A
-- HOST module's surface is the loaded module table the MODULE_IMPORT
-- load publishes (__rt.load_host returns the wrapped export table), so
-- the entry is the host ABI's value — the read re-wraps nothing, copies
-- nothing, and runs no host code. An absent surface or entry (the load
-- has not run in this execution) projects the __MISSING sentinel, which
-- atomizes as "missing" — exactly the oracle's Value.MissingValue and the
-- JVM runtime's MISSING.
local function __exportHostValue(module, name)
  local surface = __exportSurfaces[module]
  if surface == nil then return __MISSING end
  local entry = surface[name]
  if entry == nil then return __MISSING end
  return entry
end
-- The numeric value view of one operand: a JSON_PARSE carrier (the
-- int/number-typed value the parsed graph carries) unwraps to its
-- number, a plain value passes through unchanged — the Lua value
-- model's realization of the shared JVM runtime's numberOf/longOf
-- tolerance, so every numeric consumer accepts either representation.
local function __num(v)
  if type(v) == "table" and v.__jn then return v.d end
  return v
end
-- The number-typed slot mark of the shared JSON_STRINGIFY walker: the
-- Lua value model carries no int/number distinction for plain numbers,
-- so every container write records the written value's own variant —
-- the __jn carrier's kind when the value is a runtime carrier, the
-- written expression's static kind otherwise. A number-variant slot
-- serializes through the closed decimal spelling (__sfNumText,
-- Double.toString parity), an int-variant slot through the integer
-- spelling. The mark is keyed exactly like the storage key (a table
-- key or a 1-based element index) and is cleared by a non-number
-- rewrite of the same slot, so the walker's decision always mirrors
-- the stored value's own variant — the oracle's and the shared JVM
-- runtime's typing rule.
local function __numKey(t, k, v, isNumber)
  if type(v) == "table" and v.__jn then isNumber = (v.k == "number") end
  local ns = __nsSurfaces[t]
  if ns ~= nil then
    -- A module namespace surface keeps its number-variant marks in the
    -- namespace state: the surface's own entries stay the module's
    -- exports, exactly as the oracle's and the JVM's surfaces do.
    if isNumber then
      ns.numbers[k] = true
    else
      ns.numbers[k] = nil
    end
    return
  end
  if isNumber then
    local marks = t.__nK
    if type(marks) ~= "table" then marks = {}; t.__nK = marks end
    marks[k] = true
  else
    local marks = t.__nK
    if type(marks) == "table" then marks[k] = nil end
  end
end
-- The read-side variant materialization: a plain number read from a
-- container slot whose recorded variant (__numKey) is number, or read
-- at a number-typed position while the slot's variant is int, becomes
-- a __jn carrier carrying its own variant; a plain number whose variant
-- matches the reading position stays plain. This keeps the closed
-- value model's int/number distinction through every typed boundary
-- (the oracle keeps the admitted variant and the shared JVM runtime
-- keeps the boxed Long/Double), so the JSON_STRINGIFY walker, the trace
-- atoms, and the failure actuals always see the value's own variant.
local function __readVar(t, k, v, wantsNumber)
  if type(v) ~= "number" then return v end
  local ns = __nsSurfaces[t]
  local marks = ns ~= nil and ns.numbers or t.__nK
  if type(marks) == "table" and marks[k] == true then
    return {__jn = true, k = "number", d = v}
  end
  if wantsNumber then return {__jn = true, k = "int", d = v} end
  return v
end
-- The canonical hex-float spelling (Double.toHexString parity; the
-- single renderer of the number atoms and of the SQRT_NEGATIVE
-- actual): NaN, both infinities and negative zero carry their Java
-- spellings (never LuaJIT's "inf"/"-inf"), a subnormal its
-- fixed-exponent denormal spelling, every other finite nonzero value
-- its shortest round-trippable hex form. Declared before __atom so the
-- number branch captures it as an upvalue.
local function __numHex(v)
  if v ~= v then return "NaN" end
  if v == math.huge then return "Infinity" end
  if v == -math.huge then return "-Infinity" end
  local neg = v < 0 or (v == 0 and 1 / v < 0)
  if v == 0 then return neg and "-0x0.0p0" or "0x0.0p0" end
  local m = neg and -v or v
  local sign = neg and "-" or ""
  -- A subnormal (|v| < 2^-1022) keeps Java's fixed-exponent denormal
  -- spelling: the fraction as 13 hex digits with its trailing zeros
  -- stripped and the pinned -1022 exponent ("0x0.0000000000001p-1022"),
  -- never LuaJIT's normalized "0x1p-1074". The two exact scalings
  -- recover the subnormal's integer significand (m * 2^1074, exact —
  -- every intermediate is a normal power-of-two scaling).
  if m < 2 ^ -1022 then
    local k = m * 2 ^ 537 * 2 ^ 537
    local hex = string.format("%013x", k)
    hex = string.gsub(hex, "0+$", "")
    if hex == "" then hex = "0" end
    return sign.."0x0."..hex.."p-1022"
  end
  local h = string.format("%a", m)
  h = string.gsub(h, "p(%+)(%d)", "p%2")
  if not string.find(h, ".", 1, true) then
    h = string.gsub(h, "^(0x[0-9a-f]+)p", "%1.0p")
  end
  return sign..h
end
local function __atom(kind, v)
  if v == __MISSING then return "missing" end
  if kind == "missing" then return "missing" end
  -- The value's own scalar kind wins over a mismatched declared kind
  -- (the oracle's atomOf): a deferred contextual read lets a wrong-kind
  -- value reach the consuming op's START, and the trace atoms must agree
  -- on the value's actual kind. Numeric carriers keep the declared-kind
  -- rule below (the int/number variant is carried by the value itself).
  if v == nil then return "null" end
  if type(v) == "string" then return "str:"..__esc(v) end
  if type(v) == "boolean" then return "bool:"..tostring(v) end
  if type(v) == "table" and v.__jn then
    if v.k == "int" then return "int:"..tostring(v.d) end
    return __atom("number", v.d)
  end
  if type(v) == "table" and v.__kind == "bytes" then return "ref:"..__allocId(v) end
  if kind == "null" then return "null" end
  if kind == "missing" then return "missing" end
  if kind == "bool" then return "bool:"..tostring(v) end
  if kind == "int" then return "int:"..tostring(v) end
  if kind == "number" then
    if v ~= v then return "num:nan" end
    return "num:"..__numHex(v)
  end
  if kind == "string" then
    if v == nil then return "str:" end
    return "str:"..__esc(v)
  end
  if kind == "err" then
    return "err:"..v.code..":"..__esc(v.m or "")
  end
  if kind == "ref" or kind == "table" or kind == "array" or kind == "function"
      or kind == "class" then
    return "ref:"..__allocId(v)
  end
  if string.sub(kind, 1, 9) == "nullable:" then
    if v == nil then return "null" end
    return __atom(string.sub(kind, 10), v)
  end
  return "missing"
end
local function __errtext(e)
  if type(e) == "table" and e.__d then
    local parts = {e.code, __esc(e.m or ""), e.o or "-", e.e or "-", e.a or "-",
                   e.f or "-", e.cause and __errtext(e.cause) or "-"}
    return table.concat(parts, ";")
  end
  return "E9999;"..__esc(tostring(e))..";-;-;-;-;-"
end
local function __ev(op, phase, kind, digest, parent, inputs, output, errtext)
  local parts = {__seq, __module, op, phase, kind, digest, parent}
  if inputs then
    for _, inp in ipairs(inputs) do parts[#parts + 1] = inp end
  end
  local line = "T|"..table.concat(parts, "|")
  if output then line = line.."|=>"..output end
  if errtext then line = line.."|!"..errtext end
  io.stderr:write(line.."\\n")
  io.stderr:flush()
  __seq = __seq + 1
end
-- The raw read atom of the OPTIONAL_READ envelope: the value's actual
-- runtime kind — missing → "missing", null → "null", else the actual
-- kind's atom (a wrong-kind present value atomizes as its own kind,
-- exactly the oracle's publish). Numbers render through the declared
-- inner kind (Lua numbers carry no int/number distinction).
local function __rawAtom(kind, v)
  if v == __MISSING then return "missing" end
  if v == nil then return "null" end
  if type(v) == "table" and v.__jn then
    if v.k == "int" then return "int:"..tostring(v.d) end
    return __atom("number", v.d)
  end
  if type(v) == "boolean" then return "bool:"..tostring(v) end
  if type(v) == "number" then
    local inner = kind
    if string.sub(kind, 1, 9) == "nullable:" then inner = string.sub(kind, 10) end
    if inner == "int" then return "int:"..tostring(v) end
    return __atom("number", v)
  end
  if type(v) == "string" then return "str:"..__esc(v) end
  if type(v) == "table" then
    if v.__d then return "err:"..v.code..":"..__esc(v.m or "") end
    return "ref:"..__allocId(v)
  end
  if type(v) == "function" then return "ref:"..__allocId(v) end
  return __atom(kind, v)
end
local function __failExpr(code, msg, o, e, a)
  return {__d = true, code = code, m = msg, o = o, e = e, a = a, f = __framesText(),
          cause = nil}
end
-- The closed failure-arm table (canonical failure-projection authority P1):
-- the registry's arms serialized verbatim. Every failure site renders
-- through the one arm renderer below; a failure site composes no text of
-- its own.
__ARM_TABLE__
""";

    /**
     * The second half of the shared runtime prelude (the closed-arm
     * renderer onward): a separate constant because a single Java string
     * constant may not exceed the class-file's 65535-byte UTF-8 limit;
     * the halves are appended consecutively, so the emitted chunk is
     * byte-identical to the unsplit text.
     */
    private static final String PRELUDE_ARM_RENDERER = """
-- One arm renderer. The arm's own template is the message source; the
-- named-parameter map supplies exactly the arm's declared parameters; the
-- origin operand is the executing op's SourceOrigin. A missing arm, an
-- INNER_ONLY arm rendered at a failure site, a SIBLING_OWNED binding slot
-- (the projection binding, the origin convention, or a parameter source),
-- an absent origin operand, or an expected/actual field that does not match
-- the arm's declared shape is a producer defect — never a fallback text,
-- never a derived or substituted span, and never a composed suffix.
-- One arm template's text instantiated with its named parameter values.
-- The render must supply exactly the arm's declared parameters (its
-- serialized parameter sources): a missing or extra parameter is a
-- producer defect, never a partially composed message.
local function __declaredParameterNames(arm)
  local names = {}
  local count = 0
  for entry in string.gmatch(arm.k or "", "[^,]+") do
    local eq = string.find(entry, "=", 1, true)
    local name = eq ~= nil and string.sub(entry, 1, eq - 1) or entry
    if name ~= "" then
      names[name] = true
      count = count + 1
    end
  end
  return names, count
end
local function __parametersText(arm)
  local parts = {}
  for entry in string.gmatch(arm.k or "", "[^,]+") do
    local eq = string.find(entry, "=", 1, true)
    if eq ~= nil then
      parts[#parts + 1] = string.sub(entry, 1, eq - 1)
    end
  end
  return table.concat(parts, ",")
end
-- The retained sibling-owned markers (W5): no declared arm may carry one
-- in any binding slot, and no render publishes a tuple (or an inner text)
-- of a marked arm. The chunk's arm table is the registry's canonical
-- serialization, and this guard is the closed backstop for any hand-built
-- arm injected into the chunk-level table: a SIBLING_OWNED projection
-- binding, a SIBLING_OWNED origin convention, or a SIBLING_OWNED_ACTUAL
-- parameter source fails closed instead of rendering.
local function __checkMarkedBindingSlots(id, arm)
  if arm.a == "SIBLING_OWNED" then
    error("failure arm '"..id.."' has a SIBLING_OWNED projection binding "
      .."(producer defect)", 0)
  end
  if arm.o == "SIBLING_OWNED" then
    error("failure arm '"..id.."' has a SIBLING_OWNED origin convention "
      .."(producer defect)", 0)
  end
  for entry in string.gmatch(arm.k or "", "[^,]+") do
    local eq = string.find(entry, "=", 1, true)
    if eq ~= nil and string.sub(entry, eq + 1) == "SIBLING_OWNED_ACTUAL" then
      error("failure arm '"..id.."' declares the parameter source "
        .."SIBLING_OWNED_ACTUAL for its parameter '"
        ..string.sub(entry, 1, eq - 1).."' (producer defect)", 0)
    end
  end
end
local function __renderTemplate(id, values)
  local arm = __arms[id]
  if arm == nil then
    error("unknown failure arm '"..tostring(id).."' (producer defect)", 0)
  end
  __checkMarkedBindingSlots(id, arm)
  local declared, count = __declaredParameterNames(arm)
  local supplied = 0
  if values ~= nil then
    for k in pairs(values) do
      if not declared[k] then
        error("failure arm '"..id.."' declares the parameters ["..__parametersText(arm)
          .."] but the render supplied the extra parameter '"..tostring(k)
          .."' (producer defect)", 0)
      end
      supplied = supplied + 1
    end
  end
  if supplied ~= count then
    error("failure arm '"..id.."' declares the parameters ["..__parametersText(arm)
      .."] but the render supplied "..supplied.." (producer defect)", 0)
  end
  -- One single pass over the arm's own template: each {name} placeholder
  -- is replaced by the value the render supplied and the inserted value is
  -- never rescanned, so a parameter value that contains braces or the
  -- literal spelling of another placeholder (a table key such as {actual})
  -- is published byte-for-byte. The fail-closed checks below inspect the
  -- template only, never the inserted data.
  local template = arm.t
  local parts = {}
  local cursor = 1
  while true do
    local open = string.find(template, "{", cursor, true)
    if open == nil then
      local tail = string.sub(template, cursor)
      if string.find(tail, "}", 1, true) ~= nil then
        error("failure arm '"..id.."' left an unbound placeholder: "..template
          .." (a stray closing brace outside a placeholder; producer defect)", 0)
      end
      parts[#parts + 1] = tail
      break
    end
    local close = string.find(template, "}", open + 1, true)
    if close == nil then
      error("failure arm '"..id.."' left an unbound placeholder: "..template
        .." (producer defect)", 0)
    end
    local name = string.sub(template, open + 1, close - 1)
    if not declared[name] then
      error("failure arm '"..id.."' left an unbound placeholder: "..template
        .." (producer defect)", 0)
    end
    local segment = string.sub(template, cursor, open - 1)
    if string.find(segment, "}", 1, true) ~= nil then
      error("failure arm '"..id.."' left an unbound placeholder: "..template
        .." (a stray closing brace outside a placeholder; producer defect)", 0)
    end
    local value = nil
    if values ~= nil then value = values[name] end
    if value == nil then
      error("failure arm '"..id.."' has no value for its parameter {"..name
        .."} (producer defect)", 0)
    end
    parts[#parts + 1] = segment
    parts[#parts + 1] = tostring(value)
    cursor = close + 1
  end
  return table.concat(parts)
end
-- The closed field shapes of the arm declaration (P2): a caller-supplied
-- expected/actual token outside its arm's declared shape is a producer
-- defect — the renderer never publishes a fabricated field.
local __kindTokens = {["null"] = true, ["boolean"] = true, ["int"] = true,
  ["number"] = true, ["string"] = true, ["table"] = true, ["bytes"] = true,
  ["array"] = true, ["function"] = true, ["class"] = true}
-- The closed typed-boundary kind *texts* (P2 item 3): the {kind} parameter
-- of a KIND_TOKEN arm is one of these (a class projects "class instance"
-- while its token is "class"), so an invented kind text never derives a
-- DEAL-visible token through the authority.
local __kindTexts = {["null"] = true, ["boolean"] = true, ["int"] = true,
  ["number"] = true, ["string"] = true, ["table"] = true, ["bytes"] = true,
  ["array"] = true, ["function"] = true, ["class instance"] = true}
local __typedTokens = {["nil"] = true, ["null"] = true, ["boolean"] = true,
  ["int"] = true, ["number"] = true, ["string"] = true,
  ["invalid-unicode"] = true, ["table"] = true, ["array"] = true,
  ["bytes"] = true, ["function"] = true, ["async-operation"] = true,
  ["nothing"] = true, ["NaN"] = true, ["infinity"] = true}
local __completionTokens = {["nil"] = true, ["null"] = true, ["boolean"] = true,
  ["number"] = true, ["string"] = true, ["invalid-unicode"] = true,
  ["table"] = true, ["array"] = true, ["bytes"] = true, ["function"] = true,
  ["async-operation"] = true, ["nothing"] = true, ["NaN"] = true,
  ["infinity"] = true}
local __carrierTokens = {["nil"] = true, ["table"] = true, ["boolean"] = true,
  ["number"] = true, ["string"] = true, ["bytes"] = true, ["function"] = true}
local __descriptorPrimitives = {["null"] = true, ["boolean"] = true, ["int"] = true,
  ["number"] = true, ["string"] = true, ["table"] = true, ["bytes"] = true}
local function __isClassAtom(s)
  if type(s) ~= "string" or string.sub(s, 1, 1) ~= "@" then return false end
  if string.find(s, "/", 1, true) == nil then return false end
  return string.find(s, "[%s{}]") == nil
end
-- A descriptor text: the canonical grammar and the prelude's own internal
-- dialect (array(inner)/nullable(inner)/function(params;result)).
local function __isDescriptorText(s)
  if type(s) ~= "string" or s == "" then return false end
  if __descriptorPrimitives[s] then return true end
  local first = string.sub(s, 1, 1)
  if first == "@" then return __isClassAtom(s) end
  if first == "?" then return __isDescriptorText(string.sub(s, 2)) end
  if first == "[" then
    return string.sub(s, -1) == "]" and #s > 2
      and __isDescriptorText(string.sub(s, 2, -2))
  end
  if first == "(" then
    local arrow = string.find(s, "%)%-%>", 1)
    return arrow ~= nil and __isDescriptorText(string.sub(s, arrow + 3))
  end
  if string.sub(s, 1, 6) == "async(" then
    local arrow = string.find(s, "%)%-%>", 1)
    return arrow ~= nil and __isDescriptorText(string.sub(s, arrow + 3))
  end
  if string.sub(s, 1, 7) == "array(" and string.sub(s, -1) == ")" then
    return __isDescriptorText(string.sub(s, 7, -2))
  end
  if string.sub(s, 1, 10) == "nullable(" and string.sub(s, -1) == ")" then
    return __isDescriptorText(string.sub(s, 11, -2))
  end
  if string.sub(s, 1, 10) == "function(" and string.sub(s, -1) == ")" then
    local inner = string.sub(s, 10, -2)
    local semi = 0
    for i = #inner, 1, -1 do
      if string.sub(inner, i, i) == ";" then semi = i break end
    end
    return semi >= 1 and semi < #inner
      and __isDescriptorText(string.sub(inner, semi + 1))
  end
  return false
end
-- The canonical floating-point text of the failing value (the sqrt arm's
-- actual): Java's hex-float spelling or the special values.
local function __isNumberText(s)
  if type(s) ~= "string" then return false end
  if s == "NaN" or s == "Infinity" or s == "-Infinity" then return true end
  if string.find(s, "^%-?0x[%x%.]+p[%+%-]?%d+$") ~= nil then return true end
  return tonumber(s) ~= nil
end
local function __fieldDefect(id, field, declared, value)
  error("failure arm '"..id.."' declares its "..field.." as "..declared
    .."; the render supplied '"..tostring(value).."' (producer defect)", 0)
end
-- The value of the arm's parameter whose declared source is `source`, or nil.
local function __parameterWithSource(arm, values, source)
  if values == nil then return nil end
  for entry in string.gmatch(arm.k or "", "[^,]+") do
    local eq = string.find(entry, "=", 1, true)
    if eq ~= nil and string.sub(entry, eq + 1) == source then
      return values[string.sub(entry, 1, eq - 1)]
    end
  end
  return nil
end
-- The expected field's declared shape.
local function __checkExpectedField(id, arm, values, expected)
  if arm.e == "NONE" then
    if expected ~= nil then
      error("failure arm '"..id.."' does not declare an expected field "
        .."(producer defect)", 0)
    end
    return
  end
  if expected == nil then
    error("failure arm '"..id.."' declares an expected field but the render "
      .."supplied none (producer defect)", 0)
  end
  if arm.e == "PINNED_TEXT" then
    if expected ~= arm.p then
      __fieldDefect(id, "expected", "the pinned text '"..tostring(arm.p).."'", expected)
    end
  elseif arm.e == "KIND_TOKEN" then
    local kindText = __parameterWithSource(arm, values, "KIND_TEXT")
    if kindText ~= nil then
      if not __kindTexts[kindText] then
        __fieldDefect(id, "kind", "a closed typed-boundary kind text", kindText)
      end
      local token = kindText == "class instance" and "class" or kindText
      if expected ~= token then
        __fieldDefect(id, "expected",
          "the kind token of its kind text '"..tostring(kindText).."'", expected)
      end
    elseif not __kindTokens[expected] then
      __fieldDefect(id, "expected", "a closed typed-boundary kind token", expected)
    end
  elseif arm.e == "CLASS_ATOM" then
    if not __isClassAtom(expected) then
      __fieldDefect(id, "expected", "a canonical class atom", expected)
    end
  elseif arm.e == "ASYNC_OPERATION_TOKEN" then
    if expected ~= "async operation" then
      __fieldDefect(id, "expected", "the async-operation token", expected)
    end
  elseif arm.e == "SIGNATURE" then
    if expected ~= "" and not __isDescriptorText(expected) then
      __fieldDefect(id, "expected", "a canonical descriptor text", expected)
    end
  elseif not __isDescriptorText(expected) then
    __fieldDefect(id, "expected", "a canonical descriptor text", expected)
  end
end
-- The actual field's declared projection shape.
local function __checkActualField(id, arm, actual)
  if arm.a == "NONE" then
    if actual ~= nil then
      error("failure arm '"..id.."' does not declare an actual field "
        .."(producer defect)", 0)
    end
    return
  end
  if actual == nil then
    error("failure arm '"..id.."' declares an actual field but the render "
      .."supplied none (producer defect)", 0)
  end
  if arm.a == "TYPED_BOUNDARY" or arm.a == "COMPLETION" then
    -- The closed vocabulary and the carried canonical class atom only: the
    -- superseded composed class:<ClassId> spelling is never a typed-boundary
    -- token (jsonable-tojson-walk-arm-binding W1), so a caller composing it
    -- fails closed.
    local closed = arm.a == "COMPLETION" and __completionTokens or __typedTokens
    if not closed[actual] and not __isClassAtom(actual) then
      __fieldDefect(id, "actual", "a closed typed-boundary token", actual)
    end
  elseif arm.a == "CARRIER_KIND" then
    if not __carrierTokens[actual] then
      __fieldDefect(id, "actual", "a closed carrier-kind token", actual)
    end
  elseif arm.a == "NOTHING_TOKEN" then
    if actual ~= "nothing" then
      __fieldDefect(id, "actual", "the nothing token", actual)
    end
  elseif arm.a == "CARRIED_CLASS_ATOM" then
    if not __isClassAtom(actual) then
      __fieldDefect(id, "actual", "a canonical class atom", actual)
    end
  elseif arm.a == "CARRIED_SIGNATURE" then
    if actual ~= "" and not __isDescriptorText(actual) then
      __fieldDefect(id, "actual", "a canonical descriptor text", actual)
    end
  elseif arm.a == "CANONICAL_VALUE_TEXT" then
    if not __isNumberText(actual) then
      __fieldDefect(id, "actual", "the canonical floating-point text", actual)
    end
  end
end
local function __arm(id, values, origin, expected, actual)
  local arm = __arms[id]
  if arm == nil then
    error("unknown failure arm '"..tostring(id).."' (producer defect)", 0)
  end
  if arm.s ~= "TOP_LEVEL" then
    error("failure arm '"..id.."' is INNER_ONLY: it renders only into another "
      .."arm's inner reason (producer defect)", 0)
  end
  __checkMarkedBindingSlots(id, arm)
  if origin == nil then
    -- The origin is the render's one operand (the executing op's
    -- SourceOrigin): an absent operand would publish a DEAL-visible
    -- failure tuple without its span, so it is a fail-closed producer
    -- defect and never a derived or substituted origin.
    error("failure arm '"..id.."' is rendered with no origin operand: the "
      .."render must carry the executing op's SourceOrigin (a producer "
      .."defect, never a derived or substituted span)", 0)
  end
  __checkExpectedField(id, arm, values, expected)
  __checkActualField(id, arm, actual)
  return __failExpr(arm.c, __renderTemplate(id, values), origin, expected, actual)
end
-- An inner-only arm's message text (the host arms' inner reasons).
local function __innerArm(id, values)
  local arm = __arms[id]
  if arm == nil or arm.s ~= "INNER_ONLY" then
    error("failure arm '"..tostring(id).."' is not an inner reason "
      .."(producer defect)", 0)
  end
  return __renderTemplate(id, values)
end
-- The Unicode-scalar validity of one string carrier (the typed-boundary
-- projection's invalid-scalar classification): a sequence that is not a
-- valid Unicode scalar sequence projects "invalid-unicode". Declared here
-- (before the projections that use it) rather than in the JSON prelude,
-- which is emitted later and whose locals are not lexically visible here.
local function __jsonValidUtf8(s)
  local i = 1
  local n = #s
  while i <= n do
    local b = string.byte(s, i)
    local len
    if b < 0x80 then len = 1
    elseif b >= 0xC2 and b <= 0xDF then len = 2
    elseif b >= 0xE0 and b <= 0xEF then len = 3
    elseif b >= 0xF0 and b <= 0xF4 then len = 4
    else return false end
    if i + len - 1 > n then return false end
    local cp
    if len == 1 then cp = b
    elseif len == 2 then cp = b - 0xC0
    elseif len == 3 then cp = b - 0xE0
    else cp = b - 0xF0 end
    for k = 1, len - 1 do
      local c = string.byte(s, i + k)
      if c < 0x80 or c > 0xBF then return false end
      cp = cp * 0x40 + (c - 0x80)
    end
    if len == 2 and cp < 0x80 then return false end
    if len == 3 and cp < 0x800 then return false end
    if len == 4 and cp < 0x10000 then return false end
    if cp > 0x10FFFF then return false end
    if cp >= 0xD800 and cp <= 0xDFFF then return false end
    i = i + len
  end
  return true
end
-- The typed-boundary projection (P2 item 1): the closed token of the
-- failing value — the absent marker is nil, the language-null sentinel
-- null, and a class instance its carried canonical class atom (never a
-- target class name, never the class: IR/trace spelling).
local function __typedBoundaryKind(staticKind, v)
  if v == __MISSING then return "nil" end
  -- The deployed runtime's absent-marker sentinel, by identity (its own
  -- value, never the nil a host-free chunk leaves the binding at).
  if __rtMissing ~= nil and v == __rtMissing then return "nil" end
  if v == nil or v == __NULL or v == __rtNull then return "null" end
  if type(v) == "table" and v.__jn then return v.k end
  local t = type(v)
  if t == "boolean" then return "boolean" end
  if t == "number" then
    -- The value-derived numeric classification: a numeric failing value
    -- projects by its own variant — an integral finite value is the int
    -- carrier, a fractional or nonfinite value the number carrier — never
    -- by the declared descriptor text beside the position.
    if v % 1 == 0 then return "int" end
    return "number"
  end
  if t == "string" then
    -- The invalid-scalar classification: a string carrier that is not a
    -- valid Unicode scalar sequence projects invalid-unicode.
    if not __jsonValidUtf8(v) then return "invalid-unicode" end
    return "string"
  end
  if t == "table" then
    -- The bytes carrier keeps its own closed kind: a bytes value never
    -- projects the table spelling, so a non-bytes typed boundary rejects
    -- it instead of admitting it.
    if v.__kind == "bytes" then return "bytes" end
    -- The landed runtime/host ABI carriers (the runtime's own produced
    -- shapes, W1/W6): the async handle deal/runtime.lua's async_create
    -- builds projects the closed async token, and the runtime class
    -- representation class_ builds projects its carried canonical class
    -- atom — never the table spelling. The chunk's own class carrier
    -- (__c/__id) is classified by the next branch; a class carrier
    -- without its atom fails closed exactly like the oracle's projection.
    if v.__kind == "async" then return "async-operation" end
    if v.__kind == "class" then
      if v.__classname == nil then
        error("a class instance without its carried canonical class atom has "
          .. "no typed-boundary token (producer defect)", 0)
      end
      return v.__classname
    end
    if v.__a then return "array" end
    if v.__c then return v.__id end
    -- The DEAL error carrier (the builtin Error class instance, the
    -- __d-tagged shape every failure and raise site publishes) projects
    -- its carried canonical class atom @/Error — never the class:<ClassId>
    -- IR/trace spelling and never the superseded class:@builtin/Error
    -- form (the oracle's and the JVM's class instances project the same
    -- carried canonical atom).
    if v.__d then return "@/Error" end
    if v.__t then return "table" end
    -- Every function-shaped carrier projects the closed function token:
    -- the __fn protocol carriers (closures, adapters, intrinsic and
    -- stdlib callables), the __f mark, and the host ABI/export wrapper's
    -- own __kind mark (the mark __bcheck and __stdJsonKind already
    -- recognize). The wrapper is a required carrier classification here,
    -- never the table spelling.
    if v.__fn ~= nil or v.__f or v.__kind == "function" then
      return "function"
    end
    -- A value without a closed carrier mark projects its own kind: the
    -- declared static kind never replaces the value's own classification
    -- (a non-Error value at an Error-class boundary is a table on the
    -- oracle and the JVM alike).
    if staticKind == "table" then return "table" end
    if staticKind == "array" then return "array" end
    return "table"
  end
  if t == "function" then return "function" end
  return staticKind
end
-- The array-element arm's reference-kind projection (the decoded-array
-- mark, B3): an element carrying the std/json decode mark renders its
-- reference value-model kind (table), every other element keeps the
-- landed refined typed-boundary token (array for a real array carrier).
-- The mark is read here and nowhere else in the emitted prelude.
local function __elemRefKind(staticKind, v)
  if type(v) == "table" and v.__a and v.__da then return "table" end
  return __typedBoundaryKind(staticKind, v)
end
-- The completion variant (P2 item 4): every numeric carrier is the single
-- number kind.
local function __completionBoundaryKind(staticKind, v)
  if type(v) == "number" or (type(v) == "table" and v.__jn) then
    return "number"
  end
  return __typedBoundaryKind(staticKind, v)
end
-- The carrier-kind projection (P2 item 2): the Lua type() shape of the
-- unchanged runtimes — the absent marker is nil, the language-null
-- sentinel is table, every other table-carried value is table, and a raw
-- host-facing function stays function.
local function __carrierKind(v)
  if v == __MISSING then return "nil" end
  if v == nil then return "nil" end
  if v == __NULL then return "table" end
  local t = type(v)
  if t == "table" then
    if v.__jn then return "number" end
    return "table"
  end
  return t
end
-- The std/json rejection arm's actual projection (P2 item 2 with the
-- arm's two pinned members): the carrier-kind projection plus the
-- pinned bytes carrier ("bytes") and DEAL function value ("function").
-- The shared token table's Lua implementation; the failure site holds
-- no token of its own.
local function __stdJsonKind(v)
  if v == __MISSING or v == nil then return "nil" end
  if v == __NULL then return "table" end
  local t = type(v)
  if t == "table" then
    if v.__jn then return "number" end
    if v.__kind == "bytes" then return "bytes" end
    if v.__c or v.__d then return "table" end
    if v.__fn ~= nil or v.__f or v.__csig ~= nil or v.__kind == "function" then
      return "function"
    end
    return "table"
  end
  return t
end
-- The function row's carried-signature check (E8010): the row's own
-- text is the canonical spec text (the declared descriptor beside the
-- carrier's carried metadata), so the pinned corpus projection
-- "function signature mismatch: expected {D}, got {A}" carries the two
-- canonical signature texts on every consumer; a carrier or boundary
-- that carries no canonical text keeps the internal descriptor text
-- (the DEAL carrier's own spelling) unchanged.
local function __fnRow(desc, csig, v, carriedCsig, carriedSig)
  local wanted = csig or desc
  local carried = carriedCsig or carriedSig or ""
  local matches
  if csig ~= nil and carriedCsig ~= nil then
    matches = (carriedCsig == csig)
  else
    matches = (carriedSig == desc)
  end
  if matches then return v end
  return error(__arm("FUNCTION_SIGNATURE_MISMATCH",
    {expected = wanted, actual = carried}, "-", wanted, carried), 0)
end
-- The closed canonical spelling of one prelude-internal descriptor text
-- (the semantic oracle's canonicalSpecText over the same descriptor): a
-- boundary check emitted without the canonical trailer still projects the
-- canonical element text in its E8003 row.
local function __canonDesc(desc)
  if string.sub(desc, 1, 6) == "array(" then
    return "["..__canonDesc(string.sub(desc, 7, -2)).."]"
  end
  if string.sub(desc, 1, 9) == "nullable(" then
    return "?"..__canonDesc(string.sub(desc, 10, -2))
  end
  return desc
end
local function __bcheck(desc, staticKind, v, csig, completion)
  local actual = completion and __completionBoundaryKind(staticKind, v)
    or __typedBoundaryKind(staticKind, v)
  -- The typed-boundary kind arm (the completion cell's own kind arm): the
  -- arm's template is the message, the closed projections are the fields,
  -- and no site composes a suffix. The completion kind arm renders the
  -- descriptor's closed kind text in its message and the kind token as its
  -- expected field (the unchanged runtime matcher's own form
  -- "expected array"/"array" and "expected class instance"/"class").
  local function failKind(kindText, expected)
    if completion then
      return error(__arm("ASYNC_COMPLETION_KIND", {expected = kindText}, "-",
        expected, actual), 0)
    end
    return error(__arm("TYPED_BOUNDARY_KIND", {kind = kindText}, "-", expected,
      actual), 0)
  end
  -- The class arms: a carried atom projects the identity arm, every other
  -- value the closed kind arm.
  local function failClass(descText, carried)
    if carried ~= nil then
      return error(__arm("CLASS_IDENTITY",
        {expected = descText, actual = carried}, "-", descText, carried), 0)
    end
    return failKind("class instance", "class")
  end
  local function fail(expected)
    return failKind(expected, expected)
  end
  if desc == "null" then
    if v == nil then return v end
    return fail("null")
  elseif desc == "boolean" then
    if type(v) == "boolean" then return v end
    return fail("boolean")
  elseif desc == "int" then
    -- The int cell keeps the admitted value's own variant: an int
    -- carrier returns its carrier, a number carrier runs the pinned
    -- int ladder (NaN → infinity → non-integer → E8004 range) with
    -- the exact actual tokens and returns its carrier — the oracle's
    -- and the shared JVM runtime's variant-preserving admission.
    local function failRefinement(token)
      if completion then
        return error(__arm("ASYNC_COMPLETION_REFINEMENT",
          {expected = "int", actual = token}, "-", "int", token), 0)
      end
      return error(__arm("TYPED_BOUNDARY_KIND", {kind = "int"}, "-", "int",
        token), 0)
    end
    local function failRange()
      return error(__arm("INT32_RANGE", nil, "-", nil, nil), 0)
    end
    if type(v) == "table" and v.__jn then
      if v.k == "int" then return v end
      if v.d ~= v.d then return failRefinement("NaN") end
      if v.d == math.huge or v.d == -math.huge then
        return failRefinement("infinity")
      end
      if v.d % 1 ~= 0 then return failRefinement("number") end
      if v.d < -2147483648 or v.d > 2147483647 then return failRange() end
      return v
    end
    if type(v) == "number" then
      if v ~= v then return failRefinement("NaN") end
      if v == math.huge or v == -math.huge then return failRefinement("infinity") end
      if v % 1 ~= 0 then return failRefinement("number") end
      if v < -2147483648 or v > 2147483647 then return failRange() end
      return v
    end
    return fail("int")
  elseif desc == "number" then
    if type(v) == "number" then return v end
    if type(v) == "table" and v.__jn then return v end
    return fail("number")
  elseif desc == "string" then
    if type(v) == "string" then return v end
    return fail("string")
  elseif desc == "table" then
    -- A shared table carrier (__t) and a module export surface (the K15
    -- namespace value an import alias cell holds: a compiled module's
    -- publication table, a stdlib module's cataloged-callable table, or
    -- the runtime-loaded host/FFI module table) both admit. The surface
    -- carries no carrier mark of its own (its namespace state is
    -- identity-keyed), so admission follows the actual-kind projection
    -- (the marked carriers of other kinds keep their own arms and never
    -- pass a table boundary; the two internal sentinels never do).
    if type(v) == "table" and v ~= __MISSING and v ~= __NULL
        and __typedBoundaryKind(staticKind, v) == "table" then
      return v
    end
    return fail("table")
  elseif desc == "@/Error" then
    -- The builtin Error class (the err carrier): an Error table
    -- passes unchanged.
    if type(v) == "table" and v.__d then return v end
    return failClass(desc, (type(v) == "table" and v.__c) and v.__id or nil)
  elseif desc == "bytes" then
    -- The bytes view (K6 item 11): the runtime carrier passes unchanged
    -- (classification only — the view carries no contents); every other
    -- value projects the closed kind arm's bytes text (the arm table is
    -- the text source, never a composed literal).
    if type(v) == "table" and v.__kind == "bytes" then return v end
    return failKind("bytes", "bytes")
  elseif string.sub(desc, 1, 1) == "@" then
    -- A nominal class descriptor (E5): the canonical @module/Class
    -- identity text — the instance must carry the identical tag.
    if type(v) == "table" and v.__c and v.__id == desc then return v end
    return failClass(desc, (type(v) == "table" and v.__c) and v.__id or nil)
  elseif string.sub(desc, 1, 6) == "array(" then
    if type(v) == "table" and v.__a then
      local inner = string.sub(desc, 7, -2)
      local innerSig = nil
      -- The canonical element text of the E8003 projection: the closed
      -- canonical descriptor spelling (the semantic oracle's
      -- canonicalSpecText), never the prelude's internal array(...)
      -- dialect (a boundary check emitted without the canonical trailer
      -- falls back to the prelude's own conversion).
      local innerExpected = __canonDesc(inner)
      if csig ~= nil and string.sub(csig, 1, 1) == "[" then
        innerSig = string.sub(csig, 2, -2)
        innerExpected = innerSig
      end
      for i = 1, v.__n do
        local elem = v[i]
        if elem == __NULL then elem = nil
        elseif elem == nil then elem = __MISSING end
        local ok, checked = pcall(__bcheck, inner,
          (elem == __MISSING) and "missing" or inner, elem, innerSig)
        if not ok then
          local actualKind = __elemRefKind(
            (elem == __MISSING) and "missing" or inner, elem)
          return error(__arm("ARRAY_ELEMENT_KIND", {oneBasedIndex = i}, "-",
            innerExpected, actualKind), 0)
        end
      end
      return v
    end
    return fail("array")
  elseif string.sub(desc, 1, 9) == "nullable(" then
    if v == nil or v == __MISSING then return nil end
    local innerSig = csig
    if innerSig ~= nil and string.sub(innerSig, 1, 1) == "?" then
      innerSig = string.sub(innerSig, 2)
    end
    return __bcheck(string.sub(desc, 10, -2), staticKind, v, innerSig, completion)
  elseif string.sub(desc, 1, 9) == "function(" then
    if type(v) == "function" then
      return __fnRow(desc, csig, v, v.__csig, v.__sig)
    end
    if type(v) == "table" and v.__fn ~= nil then
      return __fnRow(desc, csig, v, v.__csig, v.__sig)
    end
    if type(v) == "table" and v.__kind == "function" then
      -- The host ABI wrapper — the loaded host surface entry a HOST
      -- EXPORT_READ publishes (ISSUE-0653; host-module-load-and-host-call-
      -- realization H5's read value). The entry's own sig metadata is its
      -- declared canonical signature, so the row compares it byte-exact
      -- against the boundary's declared canonical descriptor (the
      -- emitter-supplied csig; the desc spelling is the DEAL carrier's
      -- internal text).
      local wanted = csig or desc
      if v.sig == wanted then return v end
      return error(__arm("FUNCTION_SIGNATURE_MISMATCH",
        {expected = wanted, actual = tostring(v.sig or "nil")}, "-", wanted,
        v.sig), 0)
    end
    return fail("function")
  end
  return v
end
local function __cmp(selector, kindL, l, kindR, r, side)
  local lnil = (l == nil or l == __MISSING)
  local rnil = (r == nil or r == __MISSING)
  if lnil or rnil then
    if selector == "NULLABLE_NULL_EQ" then
      local named = (side == "LEFT") and l or r
      return (named == nil or named == __MISSING)
    elseif selector == "NULLABLE_NULL_NE" then
      local named = (side == "LEFT") and l or r
      return not (named == nil or named == __MISSING)
    end
    local both = lnil and rnil
    if string.sub(selector, -3) == "_EQ" then return both end
    if string.sub(selector, -3) == "_NE" then return not both end
    return false
  end
  if string.sub(selector, 1, 5) == "INT32" or string.sub(selector, 1, 6) == "NUMBER"
      or string.sub(selector, 1, 8) == "NULLABLE" then
    l = __num(l); r = __num(r)
  end
  if selector == "INT32_EQ" then return l == r end
  if selector == "INT32_NE" then return l ~= r end
  if selector == "INT32_LT" then return l < r end
  if selector == "INT32_LE" then return l <= r end
  if selector == "INT32_GT" then return l > r end
  if selector == "INT32_GE" then return l >= r end
  if selector == "NUMBER_EQ" then return l == r end
  if selector == "NUMBER_NE" then return l ~= r end
  if selector == "NUMBER_LT" then return l < r end
  if selector == "NUMBER_LE" then return l <= r end
  if selector == "NUMBER_GT" then return l > r end
  if selector == "NUMBER_GE" then return l >= r end
  if selector == "STRING_EQ" then return l == r end
  if selector == "STRING_NE" then return l ~= r end
  if selector == "STRING_LT" then return l < r end
  if selector == "STRING_LE" then return l <= r end
  if selector == "STRING_GT" then return l > r end
  if selector == "STRING_GE" then return l >= r end
  if selector == "BOOLEAN_EQ" then return l == r end
  if selector == "BOOLEAN_NE" then return l ~= r end
  if selector == "NULL_EQ" then return true end
  if selector == "NULL_NE" then return false end
  if selector == "NULLABLE_EQ" then return l == r end
  if selector == "NULLABLE_NE" then return l ~= r end
  if selector == "REFERENCE_EQ" then return l == r end
  if selector == "REFERENCE_NE" then return l ~= r end
  -- Bytes compare by allocation identity (K6 item 12): an alias compares
  -- equal, two distinct buffers unequal.
  if selector == "BYTES_EQ" then return l == r end
  if selector == "BYTES_NE" then return l ~= r end
  return false
end
local function __unary(selector, v, opKey, digest, parent, origin)
  if selector == "BOOL_NOT" then return not v end
  v = __num(v)
  if selector == "INT32_NEG" then
    local r = -v
    if r < -2147483648 or r > 2147483647 then
      local e = __arm("INT32_RANGE", nil, origin, nil, nil)
      __ev(opKey, "FAILURE", "UNARY", digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    return r
  end
  return -v
end
local function __arith(selector, l, r, opKey, digest, parent, origin)
  l = __num(l); r = __num(r)
  local function rng(v)
    if v < -2147483648 or v > 2147483647 then
      local e = __arm("INT32_RANGE", nil, origin, nil, nil)
      __ev(opKey, "FAILURE", "BINARY", digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    return v
  end
  if selector == "INT32_ADD" then return rng(l + r) end
  if selector == "INT32_SUB" then return rng(l - r) end
  if selector == "INT32_MUL" then return rng(l * r) end
  if selector == "INT32_DIV_TRUNC" then
    if r == 0 then
      local e = __arm("INT32_DIVISION_BY_ZERO", nil, origin, nil, nil)
      __ev(opKey, "FAILURE", "BINARY", digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    local q = l / r
    local t = math.floor(math.abs(q))
    if q < 0 then t = -t end
    return rng(t)
  end
  if selector == "INT32_MOD_TRUNC" then
    if r == 0 then
      local e = __arm("INT32_DIVISION_BY_ZERO", nil, origin, nil, nil)
      __ev(opKey, "FAILURE", "BINARY", digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    -- The truncated remainder: the quotient truncates toward zero with
    -- the same computation the INT32_DIV_TRUNC arm uses, so the
    -- remainder's sign follows the dividend (Lua's floor % follows the
    -- divisor). The remainder is always inside the int32 range.
    local q = l / r
    local t = math.floor(math.abs(q))
    if q < 0 then t = -t end
    return rng(l - t * r)
  end
  if selector == "INT32_POW" then
    if r < 0 then
      local e = __arm("INT32_NEGATIVE_EXPONENT", nil, origin,
        nil, nil)
      __ev(opKey, "FAILURE", "BINARY", digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    local result = 1
    for _ = 1, r do result = result * l end
    return rng(result)
  end
  if selector == "NUMBER_ADD" then return l + r end
  if selector == "NUMBER_SUB" then return l - r end
  if selector == "NUMBER_MUL" then return l * r end
  if selector == "NUMBER_DIV_IEEE" then return l / r end
  if selector == "NUMBER_MOD_FLOOR" then return l - math.floor(l / r) * r end
  if selector == "NUMBER_POW_IEEE" then return l ^ r end
  return 0
end
local function __intConv(v, evKind, kind, opKey, digest, parent, origin)
  if v == nil then
    local e = __arm("INT_CONVERSION_NULL", nil, origin, "int", "null")
    __ev(opKey, "FAILURE", evKind, digest, parent, {}, nil, __errtext(e))
    error(e, 0)
  end
  v = __num(v)
  if type(v) == "number" then
    if v ~= v then
      local e = __arm("INT_CONVERSION_NAN", nil, origin, "int", "NaN")
      __ev(opKey, "FAILURE", evKind, digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    if v == math.huge or v == -math.huge then
      local e = __arm("INT_CONVERSION_INFINITY", nil, origin, "int", "infinity")
      __ev(opKey, "FAILURE", evKind, digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    if v % 1 ~= 0 then
      local e = __arm("INT_CONVERSION_FRACTIONAL", nil, origin, "int", "number")
      __ev(opKey, "FAILURE", evKind, digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    if v < -2147483648 or v > 2147483647 then
      local e = __arm("INT32_RANGE", nil, origin, nil, nil)
      __ev(opKey, "FAILURE", evKind, digest, parent, {}, nil, __errtext(e))
      error(e, 0)
    end
    return v
  end
  local token = __typedBoundaryKind(kind, v)
  local e = __arm("TYPED_BOUNDARY_KIND", {kind = "int"}, origin, "int", token)
  __ev(opKey, "FAILURE", evKind, digest, parent, {}, nil, __errtext(e))
  error(e, 0)
end
local function __numConv(v, evKind, kind, opKey, digest, parent, origin)
  if v == nil then
    local e = __arm("NUMBER_CONVERSION_NULL", nil, origin, "number", "null")
    __ev(opKey, "FAILURE", evKind, digest, parent, {}, nil, __errtext(e))
    error(e, 0)
  end
  v = __num(v)
  if type(v) == "number" then return v end
  local token = __typedBoundaryKind(kind, v)
  local e = __arm("TYPED_BOUNDARY_KIND", {kind = "number"}, origin, "number", token)
  __ev(opKey, "FAILURE", evKind, digest, parent, {}, nil, __errtext(e))
  error(e, 0)
end
-- The intrinsic carrier's generic invoker (J2): the conversion ladder
-- of the carrier's kind over the caller's first argument, with the
-- invoking call's context when one is supplied (the DEAL convention
-- packs the invoking op's kind label, the static kind, the op key, the
-- digest, the parent key, and the origin after the value) and the
-- absent context otherwise — total and deterministic for any non-DEAL
-- caller (the host bridge and any generic unwrap). DEAL call sites run
-- the ladder directly with the invoking op's own context and kind
-- (ISSUE-0679 J4); the carrier's invoker never invents a call site's
-- origin, and its declared-parameter kind is the default static kind
-- (the intrinsic's declared signature is the only descriptor source).
__intrinsicInvoke = function(it, v, evKind, kind, opKey, digest, parent, origin)
  if evKind == nil then evKind = "INTRINSIC_CALL" end
  if kind == nil then kind = (it == "INT_CONVERT") and "number" or "int" end
  if opKey == nil then opKey = "-" end
  if digest == nil then digest = "-" end
  if parent == nil then parent = "-" end
  if origin == nil then origin = "-" end
  if it == "INT_CONVERT" then
    return __intConv(v, evKind, kind, opKey, digest, parent, origin)
  end
  if it == "BYTES_NEW" then
    -- The allocation ladder with the generic caller's context and no
    -- call-site span (a non-DEAL caller owns no call expression): the
    -- runtime entry's own coordinates stay the diagnostic origin.
    return __bytesNew(v, evKind, opKey, digest, parent, origin, "-", 0, 0)
  end
  return __numConv(v, evKind, kind, opKey, digest, parent, origin)
end
local function __arrayRead(opKey, digest, parent, bKey, bDigest, bParent, desc, inner,
                           container, slotName, nullable, wantsNumber, origin)
  local index = slotName.i
  local elem = __MISSING
  if index < 0 then
    __ev(bKey, "START", "BOUNDARY", bDigest, bParent, {__atom("missing", __MISSING)},
      nil, nil)
    local e = __arm("ARRAY_READ_NEGATIVE_INDEX", nil, origin, nil, nil)
    __ev(bKey, "FAILURE", "BOUNDARY", bDigest, bParent, {}, nil, __errtext(e))
    __ev(opKey, "FAILURE", "INDEX_READ", digest, parent, {}, nil, __errtext(e))
    error(e, 0)
  end
  if index < container.__n then
    elem = container[index + 1]
    if elem == __NULL then elem = nil
    elseif elem == nil then elem = __MISSING end
  end
  -- The read-side variant materialization (the element's own slot mark
  -- and the read's declared element descriptor).
  elem = __readVar(container, index + 1, elem, wantsNumber)
  local elemKind
  if elem == __MISSING then elemKind = "missing"
  elseif elem == nil then elemKind = "null"
  else elemKind = inner end
  __ev(bKey, "START", "BOUNDARY", bDigest, bParent, {__atom(elemKind, elem)},
    nil, nil)
  __ev(bKey, "SUCCESS", "BOUNDARY", bDigest, bParent, {}, __atom(elemKind, elem),
    nil)
  if elem == __MISSING then
    if nullable then
      return nil
    end
    return __MISSING
  end
  return elem
end
local function __arrayBounds(bKey, bDigest, bParent, input, slotName, lengthSlot,
                             desc, staticKind, isAssign, origin)
  local index = slotName.i
  __ev(bKey, "START", "BOUNDARY", bDigest, bParent, {__atom(staticKind, input)},
    nil, nil)
  if index < 0 or index > lengthSlot then
    local e = __arm("ARRAY_WRITE_BOUNDS", nil, origin, nil, nil)
    __ev(bKey, "FAILURE", "BOUNDARY", bDigest, bParent, {}, nil, __errtext(e))
    error(e, 0)
  end
  if isAssign then
    local ok, checked = pcall(__bcheck, desc, staticKind, input)
    if not ok then
      __ev(bKey, "FAILURE", "BOUNDARY", bDigest, bParent, {}, nil, __errtext(checked))
      error(checked, 0)
    end
  end
  __ev(bKey, "SUCCESS", "BOUNDARY", bDigest, bParent, {}, __atom(staticKind, input),
    nil)
end
local function __slotAtom(s)
  if s.slot == "a" then
    return "slot:"..s.i.."/"..tostring(s.i < s.n).."/"..tostring(s.a and (s.i == s.n))
  end
  if s.slot == "b" then
    return "byteslot:"..s.i.."/"..tostring(s.i < s.n)
  end
  return "keyslot:"..__esc(s.k)
end
local function __arrayBoundsSlot(bKey, bDigest, bParent, slotName, lengthSlot,
                                    isAssign, origin)
  local index = slotName.i
  __ev(bKey, "START", "BOUNDARY", bDigest, bParent, {__slotAtom(slotName)}, nil, nil)
  if index < 0 or index > lengthSlot then
    local e = __arm("ARRAY_DELETE_BOUNDS", nil, origin, nil, nil)
    __ev(bKey, "FAILURE", "BOUNDARY", bDigest, bParent, {}, nil, __errtext(e))
    error(e, 0)
  end
  __ev(bKey, "SUCCESS", "BOUNDARY", bDigest, bParent, {}, __slotAtom(slotName), nil)
end
local function __foreachCheck(opKey, digest, parent, desc, elem, origin)
  -- The op's own TYPE_DESCRIPTOR terminal check renders the typed-boundary
  -- kind arm on every element: an absent (deleted) slot projects the
  -- suffix-less kind text with the absent marker's token, like every other
  -- boundary of the check.
  local ok, checked = pcall(__bcheck, desc, desc, elem)
  if not ok then
    -- The op's own origin is the failure's origin (the op-level boundary
    -- cell), never the prelude's internal placeholder.
    checked.o = origin
    __ev(opKey, "FAILURE", "FOR_EACH", digest, parent, {}, nil, __errtext(checked))
    error(checked, 0)
  end
end
local function __normalizeEvent(opKey, digest, parent, slotName)
  local atom
  if slotName.slot == "a" then
    atom = "slot:"..slotName.i.."/"..tostring(slotName.i < slotName.n).."/"
      ..tostring(slotName.a and (slotName.i == slotName.n))
  elseif slotName.slot == "b" then
    atom = "byteslot:"..slotName.i.."/"..tostring(slotName.i < slotName.n)
  else
    atom = "keyslot:"..__esc(slotName.k)
  end
  __ev(opKey, "SUCCESS", "INDEX_NORMALIZE", digest, parent, {}, atom, nil)
end
local function __u8sub(s, i, j)
  return string.sub(s, i, j)
end
-- The wrapper unwrapper: a table carrying an __fn field exposes the
-- underlying callable (the closure or the adapter protocol).
local function __unfn(v)
  if type(v) == "table" and v.__fn ~= nil then return v.__fn end
  return v
end
-- The dynamic dispatch's carrier-class resolution (ISSUE-0658;
-- dynamic-call-shape-production-and-emission Y2/Y3/Y5; ISSUE-0678 for the
-- cataloged callable's tag; ISSUE-0679 for the seeded intrinsic's): the
-- carrier's own tag selects exactly one closed resolution class — never the
-- checked descriptor, the callee spelling, or an argument value. A DEAL
-- closure or compiled export read carries its function id (__fid); an adapter
-- carries its capture mode (__mode); a loaded host surface entry carries the
-- host ABI wrapper kind (__kind == "function"); the cataloged stdlib callable
-- carries its closed catalog row tag (__sid) and resolves the HOST class,
-- whose catalog row invoker the call site runs; a seeded intrinsic
-- (int/number/bytes) carries its closed kind tag (__it) and resolves the HOST
-- class, whose conversion/allocation ladder the call site runs. Every other value identifies no class
-- (nil) and the dynamic call fails closed at its origin.
local function __dynClass(v)
  if type(v) ~= "table" then return nil end
  if v.__fid ~= nil then return "DEAL_BODY" end
  if v.__mode ~= nil then return "ADAPTER" end
  if v.__kind == "function" then return "HOST" end
  if v.__sid ~= nil then return "HOST" end
  if v.__it ~= nil then return "HOST" end
  return nil
end
-- The declared identity (module path, export name) of one loaded host
-- surface entry: the program's export-surface registry is the
-- identity-indexed home of the entries the host load published (H1), so
-- the carrier itself names the declared host export the dynamic dispatch's
-- host operation start derives its operation label and its
-- ASYNC_OPERATION_HANDLE terminal from. Exactly one home is required: a
-- shared entry (never a loaded surface entry) resolves no declared
-- identity and the dispatch fails closed.
local function __surfaceNameOf(v)
  local found = nil
  for __m, __s in pairs(__exportSurfaces) do
    for __n, __e in pairs(__s) do
      if __e == v then
        if found ~= nil then return nil end
        found = {__m, __n}
      end
    end
  end
  return found
end
-- The actual-kind atom of one host-supplied argument (the callback
-- dispatch surface): null/boolean/int/number/string by the runtime
-- carrier — identical to the semantic oracle's scripted-argument
-- atomization.
local function __hostAtom(v)
  if v == nil then return "null" end
  local t = type(v)
  if t == "boolean" then return "bool:"..tostring(v) end
  if t == "number" then
    if v % 1 == 0 then return "int:"..tostring(v) end
    return __atom("number", v)
  end
  if t == "string" then return "str:"..__esc(v) end
  return "ref:"..__allocId(v)
end
-- The adapter's source-signature check (D15): the resolved source's
-- carried canonical spec text must equal the recorded source signature;
-- a mismatch is E8010 FUNCTION_SIGNATURE at the invoking op's origin
-- with the active frames (the check runs before any source-frame push).
local function __fncheck(v, expected, origin)
  local carried = ""
  -- A DEAL carrier carries its canonical spec text in __csig; a loaded
  -- host surface entry (the host ABI wrapper a HOST read publishes,
  -- ISSUE-0653) carries its declared canonical signature in sig.
  if type(v) == "table" then carried = v.__csig or v.sig or "" end
  if type(v) == "function" then carried = v.__csig or "" end
  if carried == expected then return v end
  return error(__arm("FUNCTION_SIGNATURE_MISMATCH",
    {expected = expected, actual = carried}, origin, expected, carried), 0)
end
-- The adapter's source resolution per the closed capture mode
-- (D15 creation half): 0 VALUE, 1 SHARED_CELL, 2 REEVALUATE_THUNK.
local function __adaptSource(w)
  if w.__mode == 0 then return w.__value
  elseif w.__mode == 1 then return w.__cell[1]
  else return w.__thunk() end
end
-- The D15 adapter invocation protocol: resolve the source per the
-- closed capture mode (0 VALUE, 1 SHARED_CELL, 2 REEVALUATE_THUNK),
-- the source-signature check, then the source invocation with the
-- leading M arguments only. A DEAL-body source pushes its function id
-- onto the active frames for the invocation (popped on every path); the
-- identical completion error propagates unchanged.
local function __adaptInvoke(w, origin, ...)
  local __cargs = {...}
  local src = __adaptSource(w)
  __fncheck(src, w.__csrc, origin)
  local __fid = nil
  if type(src) == "table" then __fid = src.__fid end
  if type(src) == "function" then __fid = src.__fid end
  local __pushed = false
  local __savedModule = __module
  local __switched = false
  if __fid ~= nil then
    -- The resolved source's own module carries the events of its body
    -- (the oracle's owning-unit body run): a cross-module adapted
    -- source reports its own module, never the invoking module's tag.
    local __srcModule = __fnModules[__fid]
    if __srcModule ~= nil then
      __module = __srcModule
      __switched = true
    end
    table.insert(__frames, 1, tostring(__fid))
    __pushed = true
  end
  -- The invoking call's origin: a failure inside the adapted source
  -- whose arm renders the call origin (the @jsonable toJson walk) reads
  -- the innermost active call — the adapter call expression, never an
  -- enclosing static call. The host-facing bridge passes "-" (no call
  -- expression), so only a real call origin is published.
  local __originPushed = origin ~= nil and origin ~= "-"
  if __originPushed then __callOrigins[#__callOrigins + 1] = origin end
  local __okA, __vA = pcall(__unfn(src), unpack(__cargs, 1, w.__m))
  if __originPushed then __callOrigins[#__callOrigins] = nil end
  if __pushed then table.remove(__frames, 1) end
  if __switched then __module = __savedModule end
  if not __okA then error(__vA, 0) end
  return __vA
end
""";

    /**
     * The second half of the runtime prelude (the D13 async machine
     * onward): a separate constant because a single Java string constant
     * may not exceed the class-file's 65535-byte UTF-8 limit; both halves
     * are appended consecutively, so the emitted chunk is byte-identical
     * to the unsplit text.
     */
    private static final String PRELUDE_ASYNC = """
-- ==== the D13 async machine (ASYNC_START/AWAIT) ====
-- The canonical-token task registry and the deterministic FIFO queue —
-- chunk-global (one execution state across a multi-module drive).
__tasks = __tasks or {}
__ready = __ready or {}
-- One DEAL body task record: the coroutine wrapping the body-task
-- closure (the canonical AsyncTokenId is the task record).
local function __asyncStartTask(tokenId, owner, co, args)
  __tasks[tokenId] = {token = tokenId, owner = owner, co = co, args = args,
                      status = 0, value = nil, err = nil, label = nil}
  table.insert(__ready, tokenId)
  return tokenId
end
-- One async host operation record: a production operation carries the
-- operation handle the loaded declared async export returned (its
-- completion is driven at the awaiting site through the landed
-- __rt.async_step machinery, never through the body drain); a
-- scenario/seam record carries no handle and completes through the
-- deterministic host seam at the awaiting site.
local function __asyncStartHost(tokenId, label, handle)
  __tasks[tokenId] = {token = tokenId, owner = "HOST_OPERATION", co = nil,
                      args = nil, status = 2, value = nil, err = nil,
                      label = label, handle = handle}
  table.insert(__ready, tokenId)
  return tokenId
end
-- The deterministic FIFO drain (the oracle's drainReadyTasks): every
-- pending body task resumes to completion in submission order (a task
-- body's nested starts re-evaluate the queue length — the while form
-- drains them too). A DEAL failure completes the task record; an
-- infrastructure failure is never reified. Host operations complete
-- only at their own AWAIT through the seam.
local function __asyncDrain()
  local i = 1
  while i <= #__ready do
    local id = __ready[i]
    local t = __tasks[id]
    if t ~= nil and t.status == 0 then
      t.status = 1
      local ok, v = coroutine.resume(t.co, unpack(t.args, 1, #t.args))
      if not ok then
        if type(v) == "table" and v.__d then
          t.err = v
        else
          error(v, 0)
        end
      else
        t.value = v
      end
    end
    i = i + 1
  end
  __ready = {}
end
-- The first-insertion order discipline of the shared table carrier
-- (the SemanticTable order contract): TABLE_NEW / MEMBER_WRITE /
-- INDEX_WRITE append a key on first insertion, MEMBER_DELETE /
-- INDEX_DELETE remove the order slot, and a reinsertion appends it.
-- TABLE_KEYS and JSON_STRINGIFY consume exactly this order.
local function __orderAdd(t, k)
  local ns = __nsSurfaces[t]
  if ns ~= nil then
    if not ns.keys[k] then
      ns.order[#ns.order + 1] = k
    end
    ns.keys[k] = true
    return
  end
  local __keys = t.__keys
  if __keys == nil then
    -- The fail-safe marker creation for a table built without the shared
    -- carrier's marks (a plain table the value model produced outside
    -- TABLE_NEW / JSON_PARSE): the first write gives it the key/order
    -- marks, so its insertion order and presence discipline match every
    -- other table value from then on.
    __keys = {}
    t.__keys = __keys
    t.__order = {}
  end
  if not __keys[k] then
    t.__order[#t.__order + 1] = k
  end
  __keys[k] = true
end
local function __orderRemove(t, k)
  local ns = __nsSurfaces[t]
  if ns ~= nil then
    ns.keys[k] = nil
    for i = 1, #ns.order do
      if ns.order[i] == k then
        table.remove(ns.order, i)
        return
      end
    end
    return
  end
  if t.__keys == nil then return end
  t.__keys[k] = nil
  for i = 1, #t.__order do
    if t.__order[i] == k then
      table.remove(t.__order, i)
      return
    end
  end
end
-- ==== stdlib realization (the closed 21-operation table) ====
local function __u8next(s, i)
  local b = string.byte(s, i)
  if b == nil then return nil, nil end
  if b < 128 then return b, i + 1 end
  local n = 2
  if b >= 224 then n = 3 end
  if b >= 240 then n = 4 end
  local cp = b % (2 ^ (8 - n))
  for k = 1, n - 1 do
    cp = cp * 64 + (string.byte(s, i + k) % 64)
  end
  return cp, i + n
end
local function __u8len(cp)
  if cp < 128 then return 1 end
  if cp < 2048 then return 2 end
  if cp < 65536 then return 3 end
  return 4
end
local function __cps(s)
  local out = {}
  local pos = 1
  while true do
    local cp, np = __u8next(s, pos)
    if cp == nil then return out end
    out[#out + 1] = cp
    pos = np
  end
end
local function __u8enc(cp)
  if cp < 128 then return string.char(cp) end
  if cp < 2048 then
    return string.char(192 + math.floor(cp / 64), 128 + cp % 64)
  end
  if cp < 65536 then
    return string.char(224 + math.floor(cp / 4096),
      128 + math.floor(cp / 64) % 64, 128 + cp % 64)
  end
  return string.char(240 + math.floor(cp / 262144),
    128 + math.floor(cp / 4096) % 64, 128 + math.floor(cp / 64) % 64,
    128 + cp % 64)
end
-- The raw argument/result atom of the STDLIB_CALL surfaces (the op
-- START and the STDLIB_PARAMETER/STDLIB_RETURN boundary STARTs): the
-- value's actual kind, exactly the oracle's atomOf — a dynamic argument
-- at a declared boundary atomizes as its own kind (an int-typed number
-- renders the int atom, a number-typed value the hex float, a wrong-kind
-- value its own kind) instead of crashing on the declared-kind
-- rendering.
local function __rawArgAtom(kind, v)
  if v == __MISSING then return "missing" end
  if v == nil then return "null" end
  local t = type(v)
  if t == "boolean" then return "bool:"..tostring(v) end
  if t == "number" then
    local inner = kind
    if string.sub(kind, 1, 9) == "nullable:" then inner = string.sub(kind, 10) end
    if inner == "int" then return "int:"..tostring(v) end
    if inner == "number" then return __atom("number", v) end
    if v % 1 == 0 then return "int:"..tostring(v) end
    return __atom("number", v)
  end
  if t == "string" then return "str:"..__esc(v) end
  if t == "table" then
    if v.__jn then
      if v.k == "int" then return "int:"..tostring(v.d) end
      return __atom("number", v.d)
    end
    if v.__d then return "err:"..v.code..":"..__esc(v.m or "") end
    return "ref:"..__allocId(v)
  end
  if t == "function" then return "ref:"..__allocId(v) end
  return __atom(kind, v)
end
-- The Java Double.toString notation of one finite nonzero double: the
-- shortest round-tripping digit string (at least two significant
-- digits — the printed form always carries one fractional digit), the
-- closest such decimal, and the even last digit when the value is
-- exactly equidistant between two of them — the JDK's rule, which the
-- C library's %g rounding (ties away from zero) does not reproduce on
-- its own. Plain notation when the decimal exponent is in [-3, 6],
-- scientific d.dddE±dd otherwise, and a pinned ".0" suffix on integral
-- plain forms. Named distinctly from the class-encoder renderer
-- (JSON_PRELUDE's __jsonNumText), so the emitted chunk carries one name
-- per renderer.
--
-- The steps below: __sfParts reads a %g rendering as (digit integer,
-- exponent of its last digit); __sfBump steps that digit integer by one
-- grid unit; __sfDec renders a (digits, exponent) pair back to a
-- parseable decimal for the round-trip probe; __sfExpDigits reads the
-- exact expansion through 40 places (a tie candidate has at most 18
-- digits); __sfDigits picks the shortest round-tripping digits.
local function __sfParts(rep)
  local mant, e = string.match(rep, "^(%d+%.?%d*)e([%+%-]?%d+)$")
  if mant then
    local point = string.find(mant, ".", 1, true)
    local intLen = point and (point - 1) or #mant
    local digits = string.gsub(mant, "%.", "")
    return digits, tonumber(e) - (#digits - intLen)
  end
  local point = string.find(rep, ".", 1, true)
  local digits
  local expo
  if point then
    digits = string.gsub(rep, "%.", "")
    expo = point - #rep
  else
    digits = rep
    expo = 0
  end
  digits = string.gsub(digits, "^0+", "")
  if digits == "" then digits = "0" end
  return digits, expo
end
local function __sfBump(digits, up)
  local n = #digits
  local d = {}
  for i = 1, n do d[i] = tonumber(string.sub(digits, i, i)) end
  if up then
    local i = n
    while i >= 1 and d[i] == 9 do d[i] = 0; i = i - 1 end
    if i == 0 then return "1" end
    d[i] = d[i] + 1
  else
    local i = n
    while i >= 1 and d[i] == 0 do d[i] = 9; i = i - 1 end
    if i == 0 then return "9" end
    d[i] = d[i] - 1
  end
  local s = ""
  for i = 1, n do s = s..tostring(d[i]) end
  return s
end
local function __sfDec(digits, expo)
  if #digits == 1 then return digits.."e"..expo end
  return string.sub(digits, 1, 1).."."..string.sub(digits, 2)
    .."e"..(expo + #digits - 1)
end
local function __sfExpDigits(v)
  local mant = string.match(string.format("%.40e", v), "^(%d+%.?%d*)e")
  return (string.gsub(mant or "", "%.", ""))
end
local function __sfDigits(v, N)
  local rep = string.format("%."..N.."g", v)
  local digits, expo = __sfParts(rep)
  local pad = N - #digits
  if pad > 0 then
    digits = digits..string.rep("0", pad)
    expo = expo - pad
  end
  if tonumber(__sfDec(digits, expo)) ~= v then
    -- The correctly rounded decimal lies outside the rounding interval:
    -- the candidate is its in-interval neighbour.
    local up = __sfBump(digits, true)
    if tonumber(__sfDec(up, expo)) == v then return up, expo end
    local dn = __sfBump(digits, false)
    if tonumber(__sfDec(dn, expo)) == v then return dn, expo end
    return nil
  end
  local exp = __sfExpDigits(v)
  local dn = __sfBump(digits, false)
  local dnMid = string.gsub(dn.."5", "^0+", "")
  if string.sub(exp, 1, #dnMid) == dnMid
      and string.gsub(string.sub(exp, #dnMid + 1), "0", "") == ""
      and tonumber(__sfDec(dn, expo)) == v then
    if tonumber(string.sub(dn, #dn, #dn)) % 2 == 0 then return dn, expo end
    return digits, expo
  end
  local up = __sfBump(digits, true)
  local upMid = digits.."5"
  if string.sub(exp, 1, #upMid) == upMid
      and string.gsub(string.sub(exp, #upMid + 1), "0", "") == ""
      and tonumber(__sfDec(up, expo)) == v then
    if tonumber(string.sub(up, #up, #up)) % 2 == 0 then return up, expo end
    return digits, expo
  end
  return digits, expo
end
local function __sfNumText(v)
  local neg = false
  if v < 0 or (v == 0 and 1 / v < 0) then neg = true; v = -v end
  if v == 0 then return neg and "-0.0" or "0.0" end
  local digits, expo = nil, nil
  for N = 2, 17 do
    digits, expo = __sfDigits(v, N)
    if digits ~= nil then break end
  end
  if digits == nil then digits, expo = __sfParts(string.format("%.17g", v)) end
  digits = string.gsub(digits, "^0+", "")
  if digits == "" then digits = "0" end
  local trail = 0
  while #digits > 1 and string.sub(digits, #digits, #digits) == "0" do
    digits = string.sub(digits, 1, #digits - 1)
    trail = trail + 1
  end
  local k = expo + trail + #digits - 1
  local ds = digits
  local text
  if k >= -3 and k <= 6 then
    if k >= 0 then
      if #ds <= k + 1 then
        text = ds..string.rep("0", k + 1 - #ds)..".0"
      else
        text = string.sub(ds, 1, k + 1).."."..string.sub(ds, k + 2)
      end
    else
      text = "0."..string.rep("0", -k - 1)..ds
    end
  else
    local tail = string.sub(ds, 1, 1)
    if #ds > 1 then tail = tail.."."..string.sub(ds, 2) else tail = tail..".0" end
    text = tail.."E"..tostring(k)
  end
  if neg then text = "-"..text end
  return text
end
-- RFC-8259 string escaping: quote/backslash escaped, the named short
-- escapes, every other control scalar as the lowercase four-hex-digit
-- escape, every other byte (surrogate pairs included) raw — built
-- through string.char, so the emitted prelude carries no escape
-- ambiguity.
local function __jsonEscape(s)
  local out = { string.char(34) }
  for i = 1, #s do
    local b = string.byte(s, i)
    if b == 34 then out[#out + 1] = string.char(92, 34)
    elseif b == 92 then out[#out + 1] = string.char(92, 92)
    elseif b == 8 then out[#out + 1] = string.char(92, 98)
    elseif b == 12 then out[#out + 1] = string.char(92, 102)
    elseif b == 10 then out[#out + 1] = string.char(92, 110)
    elseif b == 13 then out[#out + 1] = string.char(92, 114)
    elseif b == 9 then out[#out + 1] = string.char(92, 116)
    elseif b < 32 then
      out[#out + 1] = string.char(92, 117)
      out[#out + 1] = string.format("%04x", b)
    else out[#out + 1] = string.sub(s, i, i) end
  end
  out[#out + 1] = string.char(34)
  return table.concat(out)
end
local function __subseqIndexOf(hay, needle, from)
  if #needle == 0 then
    if from <= #hay then return from end
    return -1
  end
  local limit = #hay - #needle + 1
  for i = math.max(0, from) + 1, limit do
    local ok = true
    for j = 1, #needle do
      if hay[i + j - 1] ~= needle[j] then ok = false; break end
    end
    if ok then return i - 1 end
  end
  return -1
end
-- The in-target realization of the closed stdlib table: the parameter
-- boundaries already ran, so the carriers are boundary-admitted (valid
-- scalar strings, integral numbers, tables). The invoking op's event
-- kind label is the first argument (the direct STDLIB_CALL arm's own
-- kind, or the dynamic dispatch's CALL kind): every algorithm failure
-- publishes the invoking op's FAILURE event under its own kind and
-- raises the exact closed projection at the call origin with the active
-- frames.
local function __stdlib(kind, fn, opKey, digest, parent, origin, ...)
  local __args = {...}
  -- Every numeric parameter carrier unwraps to its number: the parameter
  -- boundaries already admitted the value (a JSON_PARSE carrier passes
  -- the number/int rows through the same __bcheck unwrapping), and the
  -- shared JVM algorithm receives the Long/Double the carrier stands
  -- for — the two representations never diverge inside an algorithm.
  for __i = 1, select("#", ...) do
    local __a = __args[__i]
    if type(__a) == "table" and __a.__jn then __args[__i] = __a.d end
  end
  local function __sfail(armId, values, expected, actual)
    local e = __arm(armId, values, origin, expected, actual)
    __ev(opKey, "FAILURE", kind, digest, parent, {}, nil, __errtext(e))
    error(e, 0)
  end
  local function __int32Gate(value)
    if value < -2147483648 or value > 2147483647 then
      __sfail("INT32_RANGE", nil, nil, nil)
    end
    return value
  end
  if fn == "STRING_LENGTH" then
    return __int32Gate(#__cps(__args[1]))
  elseif fn == "STRING_SUBSTRING" then
    local cps = __cps(__args[1])
    local lo = math.max(0, __args[2])
    local hi = math.min(math.max(0, __args[3]), #cps)
    if lo >= hi then return "" end
    local out = {}
    for i = lo + 1, hi do out[#out + 1] = __u8enc(cps[i]) end
    return table.concat(out)
  elseif fn == "STRING_CONTAINS" then
    return __subseqIndexOf(__cps(__args[1]), __cps(__args[2]), 0) >= 0
  elseif fn == "STRING_STARTS_WITH" then
    local input = __cps(__args[1])
    local part = __cps(__args[2])
    if #part > #input then return false end
    for i = 1, #part do
      if input[i] ~= part[i] then return false end
    end
    return true
  elseif fn == "STRING_ENDS_WITH" then
    local input = __cps(__args[1])
    local part = __cps(__args[2])
    if #part > #input then return false end
    for i = 1, #part do
      if input[#input - #part + i] ~= part[i] then return false end
    end
    return true
  elseif fn == "STRING_REPLACE" then
    local input = __cps(__args[1])
    local from = __cps(__args[2])
    local to = __cps(__args[3])
    if #from == 0 then return __args[1] end
    local out = {}
    local cursor = 1
    while cursor <= #input - #from + 1 do
      local ok = true
      for j = 1, #from do
        if input[cursor + j - 1] ~= from[j] then ok = false; break end
      end
      if ok then
        for j = 1, #to do out[#out + 1] = __u8enc(to[j]) end
        cursor = cursor + #from
      else
        out[#out + 1] = __u8enc(input[cursor])
        cursor = cursor + 1
      end
    end
    for i = cursor, #input do out[#out + 1] = __u8enc(input[i]) end
    return table.concat(out)
  elseif fn == "STRING_SPLIT" then
    local input = __cps(__args[1])
    local sep = __cps(__args[2])
    if #input == 0 then return {__a = true, __n = 0} end
    if #sep == 0 then
      local singles = {__a = true, __n = #input}
      for i = 1, #input do singles[i] = __u8enc(input[i]) end
      return singles
    end
    local parts = {__a = true, __n = 0}
    local cursor = 1
    while true do
      local occ = __subseqIndexOf(input, sep, cursor - 1)
      if occ < 0 then break end
      local piece = {}
      for i = cursor, occ do piece[#piece + 1] = __u8enc(input[i]) end
      parts.__n = parts.__n + 1
      parts[parts.__n] = table.concat(piece)
      cursor = occ + 1 + #sep
    end
    local tail = {}
    for i = cursor, #input do tail[#tail + 1] = __u8enc(input[i]) end
    parts.__n = parts.__n + 1
    parts[parts.__n] = table.concat(tail)
    return parts
  elseif fn == "STRING_TRIM" then
    local cps = __cps(__args[1])
    local function isTrim(cp) return (cp >= 9 and cp <= 13) or cp == 32 end
    local first = 1
    while first <= #cps and isTrim(cps[first]) do first = first + 1 end
    local last = #cps
    while last >= first and isTrim(cps[last]) do last = last - 1 end
    local out = {}
    for i = first, last do out[#out + 1] = __u8enc(cps[i]) end
    return table.concat(out)
  elseif fn == "TABLE_KEYS" then
    local t = __args[1]
    local ns = __nsSurfaces[t]
    local order = ns ~= nil and ns.order or t.__order
    local keys = {__a = true, __n = #order}
    for i = 1, #order do keys[i] = order[i] end
    return keys
  elseif fn == "JSON_PARSE" then
    local s = __args[1]
    local pos = 1
    local consumed = 0
    local function atEnd() return pos > #s end
    local function peek()
      local cp = __u8next(s, pos)
      if cp == nil then return -1 end
      return cp
    end
    local function advance()
      local cp = __u8next(s, pos)
      pos = pos + __u8len(cp)
      consumed = consumed + __u8len(cp)
      return cp
    end
    local function parseFail(reason)
      __sfail("JSON_PARSE_ERROR",
        {oneBasedByteOffset = consumed + 1, reason = reason}, nil, nil)
    end
    local function skipWs()
      while not atEnd() do
        local c = peek()
        if c ~= 32 and c ~= 9 and c ~= 10 and c ~= 13 then return end
        advance()
      end
    end
    local function hexDigit(c)
      if c >= 48 and c <= 57 then return c - 48 end
      if c >= 97 and c <= 102 then return c - 87 end
      if c >= 65 and c <= 70 then return c - 55 end
      return -1
    end
    local parseValue
    local function parseHex4()
      local value = 0
      for i = 1, 4 do
        if atEnd() then parseFail("invalid escape") end
        local d = hexDigit(peek())
        if d < 0 then parseFail("invalid escape") end
        advance()
        value = value * 16 + d
      end
      return value
    end
    local function parseString()
      advance()
      local out = {}
      while true do
        if atEnd() then parseFail("unterminated string") end
        local c = peek()
        if c == 34 then advance(); return table.concat(out) end
        if c == 92 then
          advance()
          if atEnd() then parseFail("unterminated string") end
          local esc = peek()
          if esc == 34 then advance(); out[#out + 1] = string.char(34)
          elseif esc == 92 then advance(); out[#out + 1] = string.char(92)
          elseif esc == 47 then advance(); out[#out + 1] = string.char(47)
          elseif esc == 98 then advance(); out[#out + 1] = string.char(8)
          elseif esc == 102 then advance(); out[#out + 1] = string.char(12)
          elseif esc == 110 then advance(); out[#out + 1] = string.char(10)
          elseif esc == 114 then advance(); out[#out + 1] = string.char(13)
          elseif esc == 116 then advance(); out[#out + 1] = string.char(9)
          elseif esc == 117 then
            advance()
            local cp = parseHex4()
            if cp >= 55296 and cp <= 56319 then
              if atEnd() or peek() ~= 92 then parseFail("unpaired surrogate escape") end
              advance()
              if atEnd() or peek() ~= 117 then parseFail("unpaired surrogate escape") end
              advance()
              local lo = parseHex4()
              if lo < 56320 or lo > 57343 then parseFail("unpaired surrogate escape") end
              cp = 65536 + (cp - 55296) * 1024 + (lo - 56320)
            elseif cp >= 56320 and cp <= 57343 then
              parseFail("unpaired surrogate escape")
            end
            out[#out + 1] = __u8enc(cp)
          else
            parseFail("invalid escape")
          end
        elseif c < 32 then
          parseFail("unexpected character")
        else
          out[#out + 1] = __u8enc(advance())
        end
      end
    end
    local function parseLiteral(word, value)
      for i = 1, #word do
        if atEnd() then parseFail("unexpected end of input") end
        if peek() ~= string.byte(word, i) then parseFail("unexpected character") end
        advance()
      end
      return value
    end
    local function parseObject()
      advance()
      local t = {__t = true, __keys = {}, __order = {}}
      skipWs()
      if not atEnd() and peek() == 125 then advance(); return t end
      while true do
        skipWs()
        if atEnd() then parseFail("unexpected end of input") end
        if peek() ~= 34 then parseFail("missing key") end
        local key = parseString()
        skipWs()
        if atEnd() or peek() ~= 58 then parseFail("missing colon") end
        advance()
        skipWs()
        local value = parseValue()
        t[key] = value
        __orderAdd(t, key)
        skipWs()
        if atEnd() then parseFail("unterminated object") end
        local c = peek()
        if c == 44 then advance()
        elseif c == 125 then advance(); return t
        else parseFail("missing comma") end
      end
    end
    local function parseArray()
      advance()
      -- The decoded-array mark (B3): this realization is the std/json
      -- decode site, so every array it produces — at every nesting depth —
      -- carries the mark; nothing else in the artifact sets it.
      local a = {__a = true, __n = 0, __da = true}
      skipWs()
      if not atEnd() and peek() == 93 then advance(); return a end
      while true do
        skipWs()
        if atEnd() then parseFail("unexpected end of input") end
        local value = parseValue()
        a.__n = a.__n + 1
        if value == nil then a[a.__n] = __NULL else a[a.__n] = value end
        skipWs()
        if atEnd() then parseFail("unterminated array") end
        local c = peek()
        if c == 44 then advance()
        elseif c == 93 then advance(); return a
        else parseFail("missing comma") end
      end
    end
    local function parseNumber()
      local startPos = pos
      local neg = false
      if peek() == 45 then neg = true; advance() end
      if atEnd() then parseFail("invalid number") end
      local c = peek()
      if c < 48 or c > 57 then parseFail("invalid number") end
      local integerForm = true
      if c == 48 then
        advance()
        if not atEnd() and peek() >= 48 and peek() <= 57 then
          parseFail("leading zero")
        end
      else
        advance()
        while not atEnd() and peek() >= 48 and peek() <= 57 do advance() end
      end
      if not atEnd() and peek() == 46 then
        integerForm = false
        advance()
        if atEnd() or peek() < 48 or peek() > 57 then parseFail("invalid number") end
        while not atEnd() and peek() >= 48 and peek() <= 57 do advance() end
      end
      if not atEnd() and (peek() == 101 or peek() == 69) then
        integerForm = false
        advance()
        if not atEnd() and (peek() == 43 or peek() == 45) then advance() end
        if atEnd() or peek() < 48 or peek() > 57 then parseFail("invalid number") end
        while not atEnd() and peek() >= 48 and peek() <= 57 do advance() end
      end
      local text = string.sub(s, startPos, pos - 1)
      if integerForm then
        local ds = text
        if neg then ds = string.sub(text, 2) end
        local sig = ds
        while #sig > 1 and string.sub(sig, 1, 1) == "0" do
          sig = string.sub(sig, 2)
        end
        local outOfRange = false
        if #sig > 10 then
          outOfRange = true
        elseif #sig == 10 then
          local limit = neg and "2147483648" or "2147483647"
          outOfRange = sig > limit
        end
        if not outOfRange then
          local value = tonumber(text)
          if value == 0 then value = 0 end
          return {__jn = true, k = "int", d = value}
        end
      end
      return {__jn = true, k = "number", d = tonumber(text)}
    end
    parseValue = function()
      if atEnd() then parseFail("unexpected end of input") end
      local c = peek()
      if c == 123 then return parseObject() end
      if c == 91 then return parseArray() end
      if c == 34 then return parseString() end
      if c == 116 then return parseLiteral("true", true) end
      if c == 102 then return parseLiteral("false", false) end
      if c == 110 then return parseLiteral("null", nil) end
      if c == 45 or (c >= 48 and c <= 57) then return parseNumber() end
      parseFail("unexpected character")
    end
    skipWs()
    local value = parseValue()
    skipWs()
    if not atEnd() then parseFail("trailing content") end
    return value
  elseif fn == "JSON_STRINGIFY" then
    local out = {}
    local path = {}
    -- The pinned STDLIB_CALL(JSON_STRINGIFY) rejection: the
    -- JSON_STRINGIFY_UNSUPPORTED arm's own render — its pinned expected
    -- text (the serialized arm table's p field) and the closed std/json
    -- carrier-kind projection of the failing value (the shared walker's
    -- internal fieldPath never surfaces). The failure site holds no
    -- token or text of its own.
    local function sfFail(v)
      local actual = __stdJsonKind(v)
      __sfail("JSON_STRINGIFY_UNSUPPORTED", {actual = actual},
        __arms.JSON_STRINGIFY_UNSUPPORTED.p, actual)
    end
    local sfValue
    -- The number slot flag is the written slot's recorded static kind
    -- (__numKey): a number-typed slot — integral values included —
    -- serializes through the closed decimal spelling, an int-typed
    -- slot through the integer spelling. Without the mark (a dynamic
    -- position, a __jn carrier, or a slot no shared write path wrote)
    -- the int spelling stands, exactly the JSON_PARSE carrier split.
    sfValue = function(v, isNumber)
      if v == nil then out[#out + 1] = "null"; return end
      if v == __MISSING then sfFail(v); return end
      local t = type(v)
      if t == "boolean" then
        out[#out + 1] = (v and "true" or "false")
        return
      end
      if t == "number" then
        if v ~= v or v == math.huge or v == -math.huge then
          sfFail(v)
        end
        if isNumber then out[#out + 1] = __sfNumText(v)
        else out[#out + 1] = tostring(v) end
        return
      end
      if t == "string" then out[#out + 1] = __jsonEscape(v); return end
      if t == "table" then
        if v.__jn then
          if v.k == "int" then out[#out + 1] = tostring(v.d)
          else
            if v.d ~= v.d or v.d == math.huge or v.d == -math.huge then
              sfFail(v)
            end
            out[#out + 1] = __sfNumText(v.d)
          end
          return
        end
        if v.__a then
          if path[v] then sfFail(v); return end
          path[v] = true
          out[#out + 1] = "["
          local marks = v.__nK
          if type(marks) ~= "table" then marks = nil end
          for i = 1, v.__n do
            if i > 1 then out[#out + 1] = "," end
            local elem = v[i]
            -- A raw nil slot is the internal missing (the deleted
            -- element model, exactly the read-side __arrayRead mapping);
            -- a present null is the __NULL marker. The deleted element is
            -- never serialized as JSON null.
            if elem == __NULL then elem = nil
            elseif elem == nil then sfFail(__MISSING) end
            sfValue(elem, marks ~= nil and marks[i] == true)
          end
          out[#out + 1] = "]"
          path[v] = nil
          return
        end
        local ns = __nsSurfaces[v]
        if ns ~= nil then
          if path[v] then sfFail(v); return end
          path[v] = true
          out[#out + 1] = "{"
          for i = 1, #ns.order do
            if i > 1 then out[#out + 1] = "," end
            local k = ns.order[i]
            out[#out + 1] = __jsonEscape(k)
            out[#out + 1] = ":"
            -- A module namespace surface's entry is projected through
            -- __nsEntry (a compiled module's ABI wrapper unwraps to its
            -- published value), so the walker rejects the module's own
            -- entries exactly like the oracle's and the JVM's value.
            sfValue(__nsEntry(v, v[k]), false)
          end
          out[#out + 1] = "}"
          path[v] = nil
          return
        end
        if v.__t then
          if path[v] then sfFail(v); return end
          path[v] = true
          out[#out + 1] = "{"
          local marks = v.__nK
          if type(marks) ~= "table" then marks = nil end
          for i = 1, #v.__order do
            if i > 1 then out[#out + 1] = "," end
            local k = v.__order[i]
            out[#out + 1] = __jsonEscape(k)
            out[#out + 1] = ":"
            sfValue(v[k], marks ~= nil and marks[k] == true)
          end
          out[#out + 1] = "}"
          path[v] = nil
          return
        end
        -- The class instance is a table-carried value on the carrier-kind
        -- projection (the std/json member included), never its canonical
        -- identity: the marker is checked before the function arm (every
        -- class instance carries the field map __f).
        if v.__c or v.__d then sfFail(v); return end
        if v.__fn ~= nil or v.__f or v.__kind == "function" then
          sfFail(v); return
        end
        -- The bytes carrier keeps its own closed kind: a stringified
        -- bytes value renders the pinned rejection (the std/json
        -- carrier-kind projection's bytes member) through the same sfFail
        -- render, never the generic table fallback.
        if v.__kind == "bytes" then sfFail(v); return end
        sfFail(v)
        return
      end
      if t == "function" then sfFail(v); return end
      sfFail(v)
    end
    sfValue(__args[1], false)
    return table.concat(out)
  elseif fn == "MATH_FLOOR" then
    return math.floor(__args[1])
  elseif fn == "MATH_CEIL" then
    return math.ceil(__args[1])
  elseif fn == "MATH_SQRT" then
    if __args[1] < 0 then
      __sfail("SQRT_NEGATIVE", nil, nil, __numHex(__args[1]))
    end
    return math.sqrt(__args[1])
  elseif fn == "MATH_ABS_INT" then
    local v = __args[1]
    return __int32Gate(v < 0 and -v or v)
  elseif fn == "MATH_ABS_NUMBER" then
    local v = __args[1]
    if v == 0 then return 0 end
    return math.abs(v)
  elseif fn == "MATH_MIN_INT" then
    return math.min(__args[1], __args[2])
  elseif fn == "MATH_MAX_INT" then
    return math.max(__args[1], __args[2])
  elseif fn == "TIME_NOW_MILLIS" then
    return os.time() * 1000
  end
  error("unknown stdlib call "..tostring(fn).." (producer defect)", 0)
end
-- The mode-gated console effect of the two cataloged console rows (M4):
-- the row invoker's single-effect write, shared by the direct
-- STDLIB_CALL arm and the cataloged callable. STDOUT always carries the
-- real effect bytes (plus, in trace mode, the protocol record on the
-- dedicated trace stream — the two never collide); the STDERR channel
-- shares the trace stream, so trace mode publishes only the protocol
-- record (the effect's exact scalar text plus its channel) and
-- production mode the exact effect bytes.
local function __consoleEffect(channel, text)
  if channel == "STDOUT" then
    io.write(text.."\\n")
    io.stdout:flush()
    if __traceMode then
      io.stderr:write("F|CONSOLE_WRITE|STDOUT|"..__esc(text).."\\n")
      io.stderr:flush()
    end
  else
    if __traceMode then
      io.stderr:write("F|CONSOLE_WRITE|STDERR|"..__esc(text).."\\n")
    else
      io.stderr:write(text.."\\n")
    end
    io.stderr:flush()
  end
end
-- The text projection of one console invocation: the argument texts in
-- declared order joined by one space (the direct arm's projection
-- verbatim); zero arguments project the empty string.
local function __consoleText(...)
  local n = select("#", ...)
  if n == 0 then return "" end
  local parts = {}
  for i = 1, n do parts[i] = tostring((select(i, ...))) end
  return table.concat(parts, " ")
end
-- The one row invoker of the closed catalog (M4): the single callable
-- realization per catalog row, shared by the direct STDLIB_CALL arm and
-- the cataloged callable (and by the dynamic dispatch's HOST sub-class,
-- which passes the invoking CALL op's own kind label). The two console
-- rows run the single-effect write through __consoleEffect; every
-- algorithmic row delegates to __stdlib (the row identity and the
-- invoking call's context travel through unchanged — one algorithm
-- authority, never two).
local function __stdlibInvoke(kind, fn, opKey, digest, parent, origin, ...)
  if fn == "CONSOLE_LOG" then
    __consoleEffect("STDOUT", __consoleText(...))
    return nil
  elseif fn == "CONSOLE_ERROR" then
    __consoleEffect("STDERR", __consoleText(...))
    return nil
  end
  return __stdlib(kind, fn, opKey, digest, parent, origin, ...)
end
-- The memoized cataloged callable of one catalog row (M4): one carrier
-- per catalog row per module per program — {__fn = the row invoker,
-- __sig = the row's declared descriptor text, __csig = the row's
-- canonical spec text, __fid = nil, __sid = the row tag}, admissible by
-- the landed function row. Creation goes through this one accessor, so
-- a read before any surface population and the later whole-surface
-- population observe one callable object. The registry is chunk-global
-- (one program), exactly like the export-surface registry.
local function __stdlibEntry(module, name, sid, sig, csig)
  local key = module..string.char(1)..name
  local entry = __stdlibEntries[key]
  if entry == nil then
    entry = {__fn = function(kind, opKey, digest, parent, origin, ...)
        return __stdlibInvoke(kind, sid, opKey, digest, parent, origin, ...)
      end,
      __sig = sig, __csig = csig, __fid = nil, __sid = sid}
    __stdlibEntries[key] = entry
  end
  return entry
end
""";
}
