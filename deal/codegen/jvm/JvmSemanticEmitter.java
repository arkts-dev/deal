package deal.codegen.jvm;

import deal.codegen.EmitterSessionBase;
import deal.codegen.SemanticEmitterShared;
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
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.ChainOperandCompletion;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ClassId;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.DefaultOwner;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExternalAsyncLink;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.IntrinsicKind;
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

public final class JvmSemanticEmitter {

    private JvmSemanticEmitter() {
    }

    /** The emitted Java artifact: the class name plus the source text. */
    public record EmissionResult(String className, String source) {
    }

    /** Emits the complete shared-JVM artifact for the validated unit. */
    public static EmissionResult emitModule(LoweredModuleUnit unit,
                                            StructuredBodyTable table) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(table, "table must not be null");
        return new Session(unit, table).emit();
    }

    /**
     * Emits the combined trace artifact of a validated executable
     * project (the cross-module CLASSES surface): one class carries
     * every module's slots/cells, function factories, adapter thunks,
     * detached class-default methods, and the generated class carriers,
     * then runs each module's init walk in dependency order (each under
     * its own {@code MODULE} tag). A caller's
     * {@code CLASS_NEW(SHARED_FACTORY)} arm resolves the owner's factory
     * op and default blocks through the closure, so the owner-side
     * events carry the owner's module path and the factory's cross-unit
     * parent. Single-unit sessions are the singleton closure of the same
     * machinery.
     *
     */
    public static EmissionResult emitProject(ExecutableLoweredProject project,
                                             Map<ModuleId, StructuredBodyTable> tables,
                                             Map<ModuleId, ClassFactoryRegistry> registries) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(registries, "registries must not be null");
        return new Session(project, tables, registries, true, null, null).emit();
    }

    public static EmissionResult emitProject(ExecutableLoweredProject project,
                                             Map<ModuleId, StructuredBodyTable> tables,
                                             Map<ModuleId, ClassFactoryRegistry> registries,
                                             HostDeclarationSurface declarationSurface) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(registries, "registries must not be null");
        Objects.requireNonNull(declarationSurface,
            "declarationSurface must not be null");
        return new Session(project, tables, registries, true, null,
            declarationSurface).emit();
    }

    public static EmissionResult emitProductionProject(ExecutableLoweredProject project,
                                                       Map<ModuleId, StructuredBodyTable> tables,
                                                       Map<ModuleId, ClassFactoryRegistry> registries,
                                                       String className,
                                                       HostDeclarationSurface declarationSurface) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(registries, "registries must not be null");
        Objects.requireNonNull(className, "className must not be null");
        Objects.requireNonNull(declarationSurface,
            "declarationSurface must not be null");
        return new Session(project, tables, registries, false, className,
            declarationSurface).emit();
    }

    public static EmissionResult emitProductionModule(LoweredModuleUnit unit,
                                                      StructuredBodyTable table,
                                                      boolean entryModule,
                                                      String className) {
        Objects.requireNonNull(unit, "unit must not be null");
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(className, "className must not be null");
        return new Session(unit, table, false, entryModule, className).emit();
    }

    // =========================================================================
    // Session
    // =========================================================================

    /**
     * The runtime-side descriptor text of one descriptor (the closed
     * {@code null|boolean|int|number|string|table|array(INNER)|
     * nullable(INNER)|function(PARAMS;RETURN)} form the runtime checks
     * dispatch on): the single producer of the text the emitted boundary
     * rows, the host ABI cell checks, and a bridged host surface entry's
     * carried signature all use.
     */
    static String runtimeDescriptorText(RuntimeDescriptor descriptor) {
        return SemanticEmitterShared.descriptorText(descriptor);
    }

    /**
     * The production projection of one checked host return held as an
     * {@code java.lang.Object} (the host-check seam's result, the
     * reflective host invocation's result): the declared carrier is
     * reconciled with the production value carrier — a numeric or boolean
     * position unboxes through its boxed form (a {@code null}-safe form for
     * a {@code ?} position), a declared array or function return crosses
     * back into the production carriers through the host-to-DEAL
     * projection, and every other reference carrier is the production value
     * itself.
     *
     */
    static String checkedResultOf(Type declaredReturn, String expression) {
        Type inner = declaredReturn instanceof Type.Nullable nullable
            ? nullable.inner() : declaredReturn;
        boolean nullable = declaredReturn instanceof Type.Nullable;
        if (inner instanceof Type.Int) {
            String unbox = "java.lang.Long.valueOf(((java.lang.Number) "
                + expression + ").longValue())";
            return nullable
                ? "(" + expression + " == null ? null : " + unbox + ")"
                : unbox;
        }
        if (inner instanceof Type.Number) {
            String unbox = "java.lang.Double.valueOf(((java.lang.Number) "
                + expression + ").doubleValue())";
            return nullable
                ? "(" + expression + " == null ? null : " + unbox + ")"
                : unbox;
        }
        if (inner instanceof Type.Boolean && !nullable) {
            return "java.lang.Boolean.valueOf(((java.lang.Boolean) "
                + expression + ").booleanValue())";
        }
        if (inner instanceof Type.Array || inner instanceof Type.Func
                || inner instanceof Type.Bytes) {
            return "__hostToDeal("
                + Session.javaString(JvmHostAbiEmission.descriptorText(
                    declaredReturn))
                + ", " + expression + ")";
        }
        return expression;
    }

    /**
     * The production value carrier of one declared host position (the host
     * ABI's shared scalar rule): the host-facing scalar carriers differ from
     * the production carriers for the signed32 int (a host primitive/boxed
     * {@code int} is the production {@code Long}), the projected
     * array/function returns are already production values, and a nullable
     * position keeps the language null.
     *
     */
    static String productionValueOf(Type declaredReturn, String expression) {
        Type inner = declaredReturn instanceof Type.Nullable nullable
            ? nullable.inner() : declaredReturn;
        boolean nullable = declaredReturn instanceof Type.Nullable;
        if (inner instanceof Type.Int) {
            return nullable
                ? "(" + expression + " == null ? null :"
                    + " java.lang.Long.valueOf(((java.lang.Number) "
                    + expression + ").longValue()))"
                : "java.lang.Long.valueOf(" + expression + ")";
        }
        if (inner instanceof Type.Number) {
            return nullable
                ? "(" + expression + " == null ? null : java.lang.Double.valueOf("
                    + "((java.lang.Number) " + expression + ").doubleValue()))"
                : "java.lang.Double.valueOf(" + expression + ")";
        }
        if (inner instanceof Type.Boolean && !nullable) {
            return "java.lang.Boolean.valueOf(" + expression + ")";
        }
        return expression;
    }

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
        /** The selected entry module runs the ENTRY_INVOKE delegation. */
        final boolean entryModule;
        final String className;

        final JvmHostAbiEmission hostAbi;

        Session(LoweredModuleUnit unit, StructuredBodyTable table) {
            this(unit, table, true, true, null);
        }

        Session(LoweredModuleUnit unit, StructuredBodyTable table, boolean trace,
                boolean entryModule, String className) {
            super(unit, table);
            this.trace = trace;
            this.projectSession = false;
            this.entryModule = entryModule;
            // The artifact class name resolves before the host seam: the
            // seam's emitted members delegate to the artifact class's
            // crossing helpers, so the seam needs the name.
            if (className != null) {
                this.className = className;
            } else {
                this.className = sharedClassName(unit.moduleId().path());
            }

            this.hostAbi = !unitHasHostImports(unit)
                    && unitHasHostCellBoundaries(unit)
                ? new JvmHostAbiEmission(java.util.List.of(), this.className)
                : null;
            registerUnit(unit, table, new ClassFactoryRegistry(Map.of()));
            if (!entryModule) {
                // A non-entry module never runs its ENTRY_INVOKE delegation
                // (the retained emitter invokes main() only from the entry
                // module): skip the entry op and its delegated CALL.
                markSkippedEntryOps(unit);
            }
        }

        /**
         * Whether the closure's op walk carries a HOST-kind import (a host or
         * extern-C declaration module).
         */
        private static boolean closureHasHostImports(ExecutableLoweredProject project) {
            for (LoweredModuleUnit moduleUnit : project.modules().values()) {
                for (SemanticOp op : moduleUnit.ops()) {
                    if (op.kind() == SemanticOpKind.MODULE_IMPORT
                            && ((KindPayload.ModuleImportPayload) op.payload()).kind()
                                == deal.semantic.ir.ModuleImportKind.HOST) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Whether the closure's op walk carries at least one host-boundary cell
         * (the DEAL_TO_HOST/HOST_PARAMETER/HOST_TO_DEAL/HOST_SYNC_RETURN family
         * the emitted host seam's checks realize).
         */
        private static boolean closureHasHostCellBoundaries(
                ExecutableLoweredProject project) {
            for (LoweredModuleUnit moduleUnit : project.modules().values()) {
                if (unitHasHostCellBoundaries(moduleUnit)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether one unit's op walk carries at least one host-boundary cell
         * (the single-unit twin of {@link #closureHasHostCellBoundaries}).
         */
        private static boolean unitHasHostCellBoundaries(LoweredModuleUnit unit) {
            for (SemanticOp op : unit.ops()) {
                if (!(op.payload() instanceof KindPayload.BoundaryPayload boundary)) {
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
            return false;
        }

        /** Whether one unit's op walk carries a HOST-kind import. */
        private static boolean unitHasHostImports(LoweredModuleUnit unit) {
            for (SemanticOp op : unit.ops()) {
                if (op.kind() == SemanticOpKind.MODULE_IMPORT
                        && ((KindPayload.ModuleImportPayload) op.payload()).kind()
                            == ModuleImportKind.HOST) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The project-mode session (the cross-module factory surface):
         * every module's unit, table, and class-factory registry in one
         * combined artifact — the CLASS_NEW(SHARED_FACTORY) arm resolves
         * the owner's factory op and default blocks through the closure.
         */
        Session(ExecutableLoweredProject project, Map<ModuleId, StructuredBodyTable> tables,
                Map<ModuleId, ClassFactoryRegistry> registries, boolean trace,
                String className) {
            this(project, tables, registries, trace, className, null);
        }

        Session(ExecutableLoweredProject project, Map<ModuleId, StructuredBodyTable> tables,
                Map<ModuleId, ClassFactoryRegistry> registries, boolean trace,
                String className, HostDeclarationSurface hostSurface) {
            super(project.modules().get(project.entryModule()),
                tables.get(project.entryModule()));
            if (unit == null || table == null) {
                throw new IllegalArgumentException(
                    "the entry module is not in the executable closure");
            }

            String resolvedClassName = className != null ? className
                : sharedClassName(project.modules().containsKey(project.entryModule())
                    ? project.modules().get(project.entryModule()).moduleId().path()
                    : project.entryModule().path());

            this.hostAbi = hostSurface != null
                ? new JvmHostAbiEmission(JvmHostAbiEmission.collect(project, hostSurface),
                    resolvedClassName)
                : (!closureHasHostImports(project) && closureHasHostCellBoundaries(project)
                    ? new JvmHostAbiEmission(java.util.List.of(), resolvedClassName)
                    : null);
            this.trace = trace;
            this.projectSession = true;
            this.entryModule = true;
            // A production project entry passes its class name verbatim
            // (the shared JvmNames.classNameFor(entry path) derivation); the
            // trace project session keeps the shared conformance name.
            this.className = resolvedClassName;
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

        /** The shared conformance class name of a module path (cross-module ABI). */
        static String sharedClassName(String modulePath) {
            StringBuilder name = new StringBuilder("SharedM");
            for (char c : modulePath.toCharArray()) {
                name.append(Character.isJavaIdentifierPart(c) ? c : '_');
            }
            return name.toString();
        }

        // -- naming ---------------------------------------------------------------

        protected String slot(ValueId id) {
            return "v" + id.id();
        }

        protected String cell(BindingId id, long generation) {
            return "b" + id.id() + "g" + generation;
        }

        String fnFactory(FunctionId id) {
            return "F" + id.id();
        }

        String loopLabel(OpId opId) {
            return "LOOP" + opId.id();
        }

        // -- java literals -----------------------------------------------------------

        /**
         * The expression of one prelude boundary check: the descriptor
         * text, the static kind, the value, and — when the descriptor
         * carries a function position — the descriptor's canonical spec
         * text as the trailing argument, so the function row projects the
         * pinned canonical signature texts ({@code function signature
         * mismatch: expected (int)->int, got (int)->string}) on every
         * consumer. The text of every non-function check is emitted
         * unchanged (no canonical trailer).
         */
        static String bcheckArgs(RuntimeDescriptor descriptor, String value) {
            return bcheckArgs("JvmRuntime.bcheck", descriptor, value);
        }

        /**
         * The completion cell's expression over one awaited function-typed
         * completion (the {@code AWAIT} boundary): the same canonical trailer
         * as {@link #bcheckArgs(RuntimeDescriptor, String)}.
         */
        static String bcheckCompletionArgs(RuntimeDescriptor descriptor,
                                           String value) {
            return bcheckArgs("JvmRuntime.bcheckCompletion", descriptor, value);
        }

        private static String bcheckArgs(String method, RuntimeDescriptor descriptor,
                                         String value) {
            String args = javaString(descriptorText(descriptor)) + ", "
                + javaString(staticKind(descriptor)) + ", " + value;
            if (containsFunction(descriptor)) {
                args += ", " + javaString(descriptor.canonicalSpecText());
            }
            return method + "(" + args + ")";
        }

        /** Whether one descriptor carries a function position (recursively). */
        static boolean containsFunction(RuntimeDescriptor descriptor) {
            if (descriptor instanceof RuntimeDescriptor.Func) {
                return true;
            }
            if (descriptor instanceof RuntimeDescriptor.Array array) {
                return containsFunction(array.element());
            }
            if (descriptor instanceof RuntimeDescriptor.Nullable nullable) {
                return containsFunction(nullable.inner());
            }
            return false;
        }

        static String javaString(String text) {
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
                        if (c < 0x20 || c > 0x7e) {
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

        // -- emission ----------------------------------------------------------------

        EmissionResult emit() {
            out.append("import deal.codegen.jvm.JvmRuntime;\n");
            out.append("import deal.codegen.jvm.JvmJson;\n");
            out.append("import java.util.List;\n");
            out.append("\npublic final class ").append(className).append(" {\n");
            // Mutable so the combined walk and the cross-unit factory
            // transfer can switch the current module per module walk
            // (each event carries its op's module path).
            out.append("  public static String MODULE = ")
                .append(javaString(unit.moduleId().path())).append(";\n");
            // The per-module export-surface registry (K15 item 1: the
            // module's namespace value): one JvmRuntime.Table per module of
            // the closure, keyed by the module identity (the dotted module
            // path), created idempotently before the module walks so a
            // repeated dealMain() drive never wipes a published surface.
            // The registry is hosted by the runtime (M2), so every
            // generated class of one program resolves the same per-module
            // surfaces; the class-level field and accessor are the
            // compatible per-class view of that program-scoped registry
            // (the compiled fixture runners reference the field).
            out.append("  static final java.util.LinkedHashMap<String, "
                + "JvmRuntime.Table> EXPORT_SURFACES = JvmRuntime.EXPORT_SURFACES;\n");

            out.append("  static final java.util.HashSet<Long> __bodyActive = "
                + "JvmRuntime.BODY_ACTIVE;\n");
            out.append("\n  static JvmRuntime.Table exportSurface(String module) {\n");
            out.append("    return JvmRuntime.exportSurface(module);\n");
            out.append("  }\n");

            out.append("\n  /**\n   * The owning module path of one lowered function id of this\n"
                + "   * closure, or null for an id outside the closure.\n   */\n");
            out.append("  static String dealModuleOfFunction(String fid) {\n");
            for (LoweredModuleUnit moduleUnit : units.values()) {
                for (LoweredFunction function : moduleUnit.functions().values()) {
                    out.append("    if (").append(javaString(
                            String.valueOf(function.functionId().id())))
                        .append(".equals(fid)) return ")
                        .append(javaString(moduleUnit.moduleId().path()))
                        .append(";\n");
                }
            }
            out.append("    return null;\n");
            out.append("  }\n");
            // Slots and cells (every module; ids are globally unique). A
            // value slot is declared per op result and per op operand: an
            // operand whose identity no op of the closure produces (the
            // producer-less seeded intrinsic identity) is still read by its
            // consuming op's START atom and by the enclosing body's
            // re-entrant state snapshot, so its field must exist.
            java.util.LinkedHashSet<String> fields = new java.util.LinkedHashSet<>();
            for (LoweredModuleUnit moduleUnit : units.values()) {
                for (SemanticOp op : moduleUnit.ops()) {
                    if (op.result() instanceof ValueId valueId) {
                        fields.add(slot(valueId));
                    }
                    for (ValueId operand : op.operands()) {
                        fields.add(slot(operand));
                    }
                    switch (op.payload()) {
                        case KindPayload.BindingAllocPayload payload ->
                            fields.add(cell(payload.binding(), payload.generation()));
                        case KindPayload.BindingInitPayload payload ->
                            fields.add(cell(payload.binding(), payload.generation()));
                        case KindPayload.BindingLoadPayload payload ->
                            fields.add(cell(payload.binding(), payload.generation()));
                        case KindPayload.BindingStorePayload payload ->
                            fields.add(cell(payload.binding(), payload.generation()));
                        case KindPayload.RecursiveGroupInitPayload payload -> {
                            for (BindingId binding : payload.bindings()) {
                                fields.add(cell(binding, 0));
                            }
                        }
                        case KindPayload.ForEachPayload payload ->
                            fields.add(cell(payload.binding(), payload.generation()));
                        case KindPayload.TryCatchPayload payload ->
                            fields.add(cell(payload.catchBinding(), 0));
                        default -> {
                        }
                    }
                }
            }
            for (String field : fields) {
                out.append("  static Object ").append(field).append(";\n");
            }

            if (hostAbi != null) {
                hostAbi.emitClassMembers(out);
            }
            // Function factories (every module).
            for (LoweredModuleUnit moduleUnit : units.values()) {
                for (LoweredFunction function : moduleUnit.functions().values()) {
                    emitFunctionFactory(function);
                }
            }
            // The REEVALUATE_THUNK re-executor methods: one detached
            // thunk method per FUNCTION_ADAPT op with a thunk source.
            // The thunk ops are members only of the detached thunk block
            // (the lowerer's single-membership rule), so the module walk
            // never executes them; each invocation re-executes them here.
            for (LoweredModuleUnit moduleUnit : units.values()) {
                for (SemanticOp op : moduleUnit.ops()) {
                    if (op.kind() != SemanticOpKind.FUNCTION_ADAPT) {
                        continue;
                    }
                    KindPayload.FunctionAdaptPayload payload =
                        (KindPayload.FunctionAdaptPayload) op.payload();
                    if (payload.source() instanceof AdaptSourceRef.Thunk thunk) {
                        emitThunkMethod(op, thunk.blockId());
                    }
                }
            }
            // The detached class-default methods (E5): one per
            // CLASS_DEFAULT op — the default block's ops (the default op
            // itself skipped) re-execute per invocation, returning the
            // block's final producing value (the op's result slot), so
            // every triggering construction gets a fresh default
            // (mutable defaults allocate freshly per attempt).
            for (LoweredModuleUnit moduleUnit : units.values()) {
                for (SemanticOp op : moduleUnit.ops()) {
                    if (op.kind() == SemanticOpKind.CLASS_DEFAULT) {
                        emitClassDefaultMethod(op);
                    }
                }
            }
            // The generated class carriers (E5): one static nested class
            // per ClassId of the resolution context with per-field value
            // slots plus boolean presence flags, tagged with the
            // canonical class identity.
            emitClassCarriers();

            for (ClassLayout layout : classLayouts.values()) {
                emitJsonPlan(layout);
            }
            // The module-init walk, exposed as the deferred-main entry (the
            // scenario host drives it explicitly for an async-entry
            // invocation or a multi-module drive): setup plus the walk;
            // main publishes the retained terminal contract around it.
            out.append("  public static void dealMain() {\n");
            out.append("    JvmRuntime.setModule(MODULE);\n");
            out.append("    JvmRuntime.setTraceEnabled(").append(trace).append(");\n");
            // Every closure module's surface exists before any module walk
            // (the idempotent get-or-create keeps a repeated drive's
            // published surfaces intact).
            for (LoweredModuleUnit moduleUnit : units.values()) {
                out.append("    exportSurface(")
                    .append(javaString(moduleUnit.moduleId().path())).append(");\n");
            }
            emitStdlibSurfacePopulation(2);
            emitProjectWalk(2);
            out.append("  }\n");

            out.append("  public static void main(String[] args) {\n");
            if (trace) {
                out.append("    try {\n");
                out.append("      dealMain();\n");
                out.append("      System.err.println(\"R|success|null\");\n");
                out.append("      System.err.flush();\n");
                out.append("    } catch (JvmRuntime.DealError e) {\n");
                out.append("      System.err.println(\"R|failure|\" + JvmRuntime.errtext(e));\n");
                out.append("      System.err.flush();\n");
                out.append("    }\n");
            } else {
                // Production terminal: a DEAL failure publishes the
                // retained DEAL_ERROR_CODE line on stdout and exits 1.
                out.append("    try {\n");
                out.append("      dealMain();\n");
                out.append("    } catch (JvmRuntime.DealError e) {\n");
                out.append("      System.out.println(\"DEAL_ERROR_CODE: \" + e.code);\n");
                out.append("      System.out.flush();\n");
                out.append("      System.exit(1);\n");
                out.append("    }\n");
            }
            out.append("  }\n");
            // The host-driven callback dispatch entries (CALLBACK_INVOKE):
            // one per-unit static entry per recorded invocation. The
            // scenario host invokes the entry top-level with scripted
            // arguments; the module-init walk never runs the unattached
            // records themselves.
            for (LoweredModuleUnit moduleUnit : units.values()) {
                for (SemanticOp op : moduleUnit.ops()) {
                    if (op.kind() == SemanticOpKind.CALLBACK_INVOKE) {
                        emitCallbackInvoke(op, 1);
                    }
                }
            }

            for (LoweredModuleUnit moduleUnit : units.values()) {
                for (SemanticOp op : moduleUnit.ops()) {
                    if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                            && ((KindPayload.ExternalEntryPayload) op.payload()).async()) {
                        emitAsyncEntry(op, 1, moduleUnit);
                    }
                }
            }
            out.append("}\n");

            if (hostAbi != null) {
                hostAbi.emitScope(out);
            }
            return new EmissionResult(className, out.toString());
        }

        /** The combined walk: each module's init block in dependency order. */
        private void emitProjectWalk(int indent) {
            for (LoweredModuleUnit moduleUnit : units.values()) {
                out.append(indent(indent)).append("MODULE = ")
                    .append(javaString(moduleUnit.moduleId().path())).append(";\n");
                // Every MODULE switch is mirrored into JvmRuntime's module
                // context: the runtime helpers (raise/arith/bcheck/...) emit
                // their events through currentModule(), so a helper-raised
                // failure in a non-entry module must carry that module.
                out.append(indent(indent)).append("JvmRuntime.setModule(MODULE);\n");
                SemanticOp moduleInitOp = moduleInitOpOf(moduleUnit);
                if (moduleInitOp == null) {
                    // A hand-built unit without the lowerer-produced op runs
                    // the bare init block, exactly the pre-envelope behavior.
                    emitBlockOps(moduleUnit.moduleInit().initBlock(), indent);
                } else {
                    emitModuleInit(moduleInitOp, indent);
                }
            }
        }

        private void emitModuleInit(SemanticOp op, int indent) {
            KindPayload.ModuleInitPayload payload =
                (KindPayload.ModuleInitPayload) op.payload();
            requireParentlessModuleInit(op);
            String moduleText = javaString(payload.module().path());
            out.append(indent(indent)).append("if (JvmRuntime.moduleInitNeeded(")
                .append(moduleText).append(")) {\n");
            out.append(indent(indent + 1)).append("JvmRuntime.moduleInitBegin(")
                .append(moduleText).append(");\n");
            emitStart(op, indent + 1);
            out.append(indent(indent + 1)).append("try {\n");
            emitBlockOps(payload.initBlock(), indent + 2);
            out.append(indent(indent + 2)).append("JvmRuntime.moduleInitComplete(")
                .append(moduleText).append(");\n");
            emitModuleInitSuccess(op, indent + 2);
            out.append(indent(indent + 1)).append("} catch (JvmRuntime.DealError e) {\n");
            out.append(indent(indent + 2)).append("JvmRuntime.moduleInitFail(")
                .append(moduleText).append(");\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "JvmRuntime.errtext(e)",
                indent + 2);
            out.append(indent(indent + 2)).append("throw e;\n");
            out.append(indent(indent + 1)).append("}\n");
            out.append(indent(indent)).append("} else {\n");
            emitStart(op, indent + 1);
            emitModuleInitSuccess(op, indent + 1);
            out.append(indent(indent)).append("}\n");
        }

        /** The MODULE_INIT SUCCESS terminal (the published {@code INITIALIZED} state). */
        private void emitModuleInitSuccess(SemanticOp op, int indent) {
            if (!trace) {
                return;
            }
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(op.opId()))).append(", \"SUCCESS\", \"MODULE_INIT\", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(), \"state:INITIALIZED\", null);\n");
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
         * Emits one detached class-default method: the default block's
         * ops in order (the CLASS_DEFAULT op itself skipped — its own
         * events are the triggering CLASS_NEW/CLASS_FACTORY arm's) and
         * the final producing value returned.
         */
        private void emitClassDefaultMethod(SemanticOp defaultOp) {
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
            out.append("  private static Object ").append(defaultFn(defaultOp.opId()))
                .append("() {\n");
            for (OpId opId : ops) {
                if (opId.equals(defaultOp.opId()) || ownedChildren.contains(opId)) {
                    continue;
                }
                emitOp(opsById.get(opId), 2);
            }
            out.append("    return ").append(slot(resultId)).append(";\n");
            out.append("  }\n");
        }

        /** One detached class-default method name of a CLASS_DEFAULT op. */
        private String defaultFn(OpId defaultOp) {
            return "default" + defaultOp.id();
        }

        /** The generated carrier class name of one ClassId. */
        private String carrierName(ClassId classId) {
            return "C" + Integer.toHexString(classId.name().hashCode() & 0x7fffffff)
                + "x" + Integer.toHexString(classId.modulePath().hashCode() & 0x7fffffff);
        }

        /**
         * Emits the generated class carriers (E5): one static nested
         * class per ClassId of the union layout context, each with
         * per-field value slots plus boolean presence flags, tagged with
         * the canonical class identity. Present null is {@code f == null
         * && p == true} — never conflated with a missing field.
         */
        private void emitClassCarriers() {
            for (ClassLayout layout : classLayouts.values()) {
                ClassId classId = layout.classId();
                out.append("  static final class ").append(carrierName(classId))
                    .append(" implements JvmRuntime.ClassInstance {\n");
                out.append("    static final String ID = ")
                    .append(javaString(classId.text())).append(";\n");
                for (ClassLayout.FieldLayout field : layout.fields()) {
                    String slotName = fieldSlot(field.name());
                    out.append("    Object ").append(slotName).append(";\n");
                    out.append("    boolean ").append(fieldFlag(field.name())).append(";\n");
                }
                out.append("    @Override public String classIdText() { return ID; }\n");
                out.append("    @Override public boolean isPresent(String key) {\n");
                for (ClassLayout.FieldLayout field : layout.fields()) {
                    out.append("      if (")
                        .append(javaString(field.name())).append(".equals(key)) return ")
                        .append(fieldFlag(field.name())).append(";\n");
                }
                out.append("      return false;\n");
                out.append("    }\n");
                out.append("    @Override public Object read(String key) {\n");
                for (ClassLayout.FieldLayout field : layout.fields()) {
                    out.append("      if (").append(javaString(field.name()))
                        .append(".equals(key)) { if (").append(fieldFlag(field.name()))
                        .append(") return ").append(fieldSlot(field.name()))
                        .append("; return JvmRuntime.MISSING; }\n");
                }
                out.append("      return JvmRuntime.MISSING;\n");
                out.append("    }\n");
                out.append("    @Override public void write(String key, Object v) {\n");
                for (ClassLayout.FieldLayout field : layout.fields()) {
                    out.append("      if (").append(javaString(field.name()))
                        .append(".equals(key)) { ").append(fieldSlot(field.name()))
                        .append(" = v; ").append(fieldFlag(field.name()))
                        .append(" = true; }\n");
                }
                out.append("    }\n");
                out.append("    @Override public void delete(String key) {\n");
                for (ClassLayout.FieldLayout field : layout.fields()) {
                    out.append("      if (").append(javaString(field.name()))
                        .append(".equals(key)) { ").append(fieldSlot(field.name()))
                        .append(" = null; ").append(fieldFlag(field.name()))
                        .append(" = false; }\n");
                }
                out.append("    }\n");
                out.append("  }\n");
            }
        }

        /** One generated carrier's per-field value-slot name. */
        private String fieldSlot(String fieldName) {
            StringBuilder name = new StringBuilder("f");
            for (char c : fieldName.toCharArray()) {
                name.append(Character.isJavaIdentifierPart(c) ? c : '_');
            }
            return name.toString();
        }

        /** One generated carrier's per-field presence-flag name. */
        private String fieldFlag(String fieldName) {
            StringBuilder name = new StringBuilder("p");
            for (char c : fieldName.toCharArray()) {
                name.append(Character.isJavaIdentifierPart(c) ? c : '_');
            }
            return name.toString();
        }

        /** One class plan's static field name. */
        private String planName(ClassId classId) {
            return "PLAN_" + Integer.toHexString(classId.name().hashCode() & 0x7fffffff)
                + "_" + Integer.toHexString(classId.modulePath().hashCode() & 0x7fffffff);
        }

        /**
         * Emits one class's JSON plan: the declaration-order fields with
         * descriptor, optionality, static result kind, and the per-field
         * CLASS_DEFAULT child metadata (key, digest, thunk) of the
         * JSON_FROM_CLASS walk; the plan instantiates the generated
         * carrier.
         */
        private void emitJsonPlan(ClassLayout layout) {
            out.append("  private static final JvmJson.Plan ").append(planName(layout.classId()))
                .append(" = new JvmJson.Plan(")
                .append(javaString(layout.classId().text())).append(",\n");
            out.append("    new JvmJson.Field[]{\n");
            for (ClassLayout.FieldLayout field : layout.fields()) {
                SemanticOp defaultOp = classDefaultOpOf(layout.classId(), field.name());
                out.append("      new JvmJson.Field(").append(javaString(field.name()))
                    .append(", ").append(javaString(jsonDescriptorText(field.descriptor())))
                    .append(", ").append(field.required() ? "false" : "true")
                    .append(", ").append(javaString(staticKind(field.descriptor())));
                if (defaultOp != null && field.required()) {
                    out.append(", ").append(javaString(opKey(defaultOp.opId())))
                        .append(", ")
                        .append(javaString(defaultOp.contract().canonicalDigest()))
                        .append(", () -> ").append(defaultFn(defaultOp.opId())).append("()");
                }
                out.append("),\n");
            }
            out.append("    },\n    () -> new ").append(carrierName(layout.classId()))
                .append("());\n");
        }

        /** The JSON plan descriptor text (the walk's closed kind grammar). */
        private static String jsonDescriptorText(RuntimeDescriptor descriptor) {
            if (descriptor instanceof RuntimeDescriptor.Nullable nullable) {
                return "nullable:" + jsonDescriptorText(nullable.inner());
            }
            if (descriptor instanceof RuntimeDescriptor.Array array) {
                return "array(" + jsonDescriptorText(array.element()) + ")";
            }
            if (descriptor instanceof RuntimeDescriptor.Class cls) {
                return cls.classId().text();
            }
            return switch (descriptor) {
                case RuntimeDescriptor.Null ignored -> "null";
                case RuntimeDescriptor.Boolean ignored -> "boolean";
                case RuntimeDescriptor.Int ignored -> "int";
                case RuntimeDescriptor.Number ignored -> "number";
                case RuntimeDescriptor.String ignored -> "string";
                case RuntimeDescriptor.Table ignored -> "table";
                case RuntimeDescriptor.Bytes ignored -> "bytes";
                case RuntimeDescriptor.Func ignored -> "function";
                case RuntimeDescriptor.Array ignored -> "array";
                case RuntimeDescriptor.Nullable ignored -> "nullable";
                case RuntimeDescriptor.Class ignored -> "class";
            };
        }

        private void emitJsonFromClass(SemanticOp op, int indent) {
            KindPayload.JsonFromClassPayload payload =
                (KindPayload.JsonFromClassPayload) op.payload();
            emitStart(op, indent);
            String target = slot((ValueId) op.result());
            out.append(indent(indent)).append(target).append(" = JvmJson.fromClass(")
                .append(planName(payload.layout().classId())).append(", (String) ")
                .append(slot(payload.jsonString())).append(");\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitJsonToClass(SemanticOp op, int indent) {
            KindPayload.JsonToClassPayload payload =
                (KindPayload.JsonToClassPayload) op.payload();
            emitStart(op, indent);
            String target = slot((ValueId) op.result());
            out.append(indent(indent)).append("try {\n");
            out.append(indent(indent + 1)).append(target).append(" = JvmJson.toClass(")
                .append(planName(payload.layout().classId())).append(", ")
                .append(slot(payload.classValue())).append(");\n");
            out.append(indent(indent)).append("} catch (JvmJson.Projection projection) {\n");
            // The walk's closed arm selection renders through the authority
            // (jsonable-tojson-walk-arm-binding W6): the walk arm with its
            // {fieldPath}/{actual} parameters, or the cycle arm with none —
            // the catch site composes no text, no token, and no span of its
            // own, and the origin operand stays the executing op's own
            // SourceOrigin.
            out.append(indent(indent + 1)).append("JvmRuntime.DealError __jsonErr = "
                + "projection.cycle\n");
            out.append(indent(indent + 2)).append("? JvmRuntime.arm("
                + "deal.semantic.ir.FailureArmId.JSON_TO_WALK_CYCLE, "
                + "java.util.Map.of(), ")
                .append("JvmRuntime.callOrigin(").append(javaString(originOf(op)))
                .append(")").append(", null, null)\n");
            out.append(indent(indent + 2)).append(": JvmRuntime.arm("
                + "deal.semantic.ir.FailureArmId.JSON_TO_WALK, "
                + "java.util.Map.of(\"fieldPath\", projection.fieldPath, "
                + "\"actual\", projection.actual), ")
                .append("JvmRuntime.callOrigin(").append(javaString(originOf(op)))
                .append(")").append(", null, projection.actual);\n");
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(__jsonErr)", indent + 1);
            out.append(indent(indent + 1)).append("throw __jsonErr;\n");
            out.append(indent(indent)).append("}\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
        }

        /** One thunk re-executor method name of an adapter op. */
        private String thunkFn(OpId adaptOp) {
            return "thunk" + adaptOp.id();
        }

        /**
         * Emits one detached thunk re-executor for a FUNCTION_ADAPT op
         * with a REEVALUATE_THUNK source: the thunk block's ops run in
         * order (payload-owned children included through their owner
         * arms) and the method returns the final producing op's result —
         * the source value the invocation consumes.
         */
        private void emitThunkMethod(SemanticOp adaptOp, BlockId block) {
            StructuredBodyTable ownerTable = blockTableOf.get(block);
            List<OpId> ops = ownerTable == null ? null : ownerTable.blockOps().get(block);
            if (ops == null) {
                throw new IllegalStateException("the adapter thunk block " + block
                    + " has no membership row (producer defect)");
            }
            out.append("  private static Object ").append(thunkFn(adaptOp.opId()))
                .append("() {\n");
            emitBlockOps(block, 2);
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
            out.append("    return ").append(slot(produced)).append(";\n");
            out.append("  }\n");
        }

        /** Emits one function factory: a FunctionValue over the passed capture cells. */
        private void emitFunctionFactory(LoweredFunction function) {
            FunctionId functionId = function.functionId();
            List<BindingGeneration> captures = function.captures();
            StringBuilder params = new StringBuilder();
            for (BindingGeneration capture : captures) {
                if (params.length() > 0) {
                    params.append(", ");
                }
                params.append("Object c" + capture.binding().id());
            }
            out.append("  static JvmRuntime.FunctionValue ").append(fnFactory(functionId))
                .append('(').append(params).append(") {\n");
            out.append("    return new JvmRuntime.FunctionValue(args -> {\n");
            List<OpId> bodyOps = tableOfFunction(function).blockOps().get(function.body());
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
                out.append("      ").append(cell(payload.binding(), payload.generation())).append(" = ")
                    .append(kind == BindingCellKind.SHARED_CELL
                        ? "new Object[]{args[" + i + "]}" : "args[" + i + "]")
                    .append(";\n");
            }
            boolean reachable = true;
            boolean unreachableReported = false;
            for (int i = paramCount; i < bodyOps.size(); i++) {
                OpId opId = bodyOps.get(i);
                if (ownedChildren.contains(opId) || skippedOps.contains(opId)) {
                    continue;
                }
                SemanticOp op = opsById.get(opId);
                if (!reachable) {
                    // JLS §14.21: the preceding statement of this body cannot
                    // complete normally, so every later op would be
                    // unreachable Java — javac rejects it and no consumer ever
                    // executes it (the oracle's block walk stops on the same
                    // non-completing path). The skip emits no event, exactly
                    // like the never-taken path.
                    if (!unreachableReported) {
                        out.append("      // unreachable: the preceding statement"
                            + " cannot complete normally\n");
                        unreachableReported = true;
                    }
                    continue;
                }
                emitOp(op, 3);
                reachable = completesNormally(op);
            }
            // A body whose last emitted op is not a RETURN (the
            // group-core window's member bodies carry no E7 RETURN
            // production) must still terminate the lambda: the
            // unconditional null return is reachable-safe after any
            // try-catch tail and never follows a directly emitted
            // `return` (tail RETURN emits one). A body whose emitted
            // statements cannot complete normally needs no terminating
            // return (javac admits a non-completing lambda body).
            SemanticOp tail = null;
            if (reachable) {
                for (int i = bodyOps.size() - 1; i >= 0; i--) {
                    OpId candidate = bodyOps.get(i);
                    if (ownedChildren.contains(candidate)
                            || skippedOps.contains(candidate)) {
                        continue;
                    }
                    tail = opsById.get(candidate);
                    break;
                }
            }
            if (reachable && (tail == null || tail.kind() != SemanticOpKind.RETURN)) {
                out.append("      return null;\n");
            }
            out.append("    }, ")
                .append(javaString(descriptorText(function.descriptor())))
                .append(", ")
                .append(javaString(function.descriptor().canonicalSpecText()))
                .append(", ")
                .append(javaString(String.valueOf(functionId.id())))
                .append(");\n");
            out.append("  }\n");
            currentFunction = null;
        }

        private void emitBlockOps(BlockId block, int indent) {
            StructuredBodyTable ownerTable = blockTableOf.get(block);
            if (ownerTable == null) {
                ownerTable = table;
            }
            boolean reachable = true;
            boolean reported = false;
            for (OpId opId : ownerTable.blockOps().get(block)) {
                if (ownedChildren.contains(opId)) {
                    continue;
                }
                if (skippedOps.contains(opId)) {
                    continue;
                }
                SemanticOp op = opsById.get(opId);
                if (!reachable) {
                    // JLS §14.21: the preceding statement of this block
                    // cannot complete normally, so every later op of the
                    // block would be unreachable Java — javac rejects it, no
                    // consumer ever executes it (the oracle's block walk
                    // stops on the same non-completing path), and the
                    // retained emitter applies the same skip rule. The skip
                    // emits no event, exactly like the never-taken path.
                    if (!reported) {
                        out.append(indent(indent)).append("// unreachable: the"
                            + " preceding statement cannot complete normally\n");
                        reported = true;
                    }
                    continue;
                }
                emitOp(op, indent);
                reachable = completesNormally(op);
            }
        }

        /**
         * Whether the emitted Java of one op's statement can complete
         * normally (the JLS §14.21 mirror the retained emitter also
         * applies): {@code THROW}/{@code BREAK}/{@code CONTINUE} /
         * {@code RETURN} transfer unconditionally, a {@code BRANCH} can
         * only when one of its branches can, and a {@code TRY_CATCH} only
         * when its try block or its catch block can (its emitted
         * {@code Transfer} clause always rethrows). Every other op emits a
         * completing statement — the emitted loops carry their own break
         * test.
         */
        private boolean completesNormally(SemanticOp op) {
            return switch (op.kind()) {
                case THROW, BREAK, CONTINUE, RETURN -> false;
                case TRY_CATCH -> {
                    KindPayload.TryCatchPayload payload =
                        (KindPayload.TryCatchPayload) op.payload();
                    yield blockCompletesNormally(payload.tryBlock())
                        || blockCompletesNormally(payload.catchBlock());
                }
                case BRANCH -> {
                    KindPayload.BranchPayload payload =
                        (KindPayload.BranchPayload) op.payload();
                    // An if without an else clause always has the
                    // fall-through path (the emitter emits a plain if), so it
                    // completes; with both branches emitted the statement
                    // completes iff one of them does.
                    yield payload.alternateBlock() == null
                        || blockCompletesNormally(payload.selectedBlock())
                        || blockCompletesNormally(payload.alternateBlock());
                }
                default -> true;
            };
        }

        /**
         * Whether the emitted Java of one block can complete normally:
         * the block's reachable prefix is walked exactly like the
         * emission walk (the non-owned, non-skipped ops in order) and the
         * walk stops at the first op whose statement cannot complete
         * normally, because every op behind it is unreachable in the
         * emitted Java (JLS §14.21) and is skipped. Reading the raw block
         * tail would answer for a represented source tail that is never
         * emitted (a statement behind a terminator, or behind a composite
         * whose every path transfers), so a block whose reachable prefix
         * ends in a transfer would be misreported as completing.
         */
        private boolean blockCompletesNormally(BlockId block) {
            StructuredBodyTable ownerTable = blockTableOf.get(block);
            if (ownerTable == null) {
                ownerTable = table;
            }
            java.util.List<OpId> ops = ownerTable.blockOps().get(block);
            if (ops == null) {
                return true;
            }
            for (OpId opId : ops) {
                if (ownedChildren.contains(opId) || skippedOps.contains(opId)) {
                    continue;
                }
                SemanticOp op = opsById.get(opId);
                if (op != null && !completesNormally(op)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Whether the emitted {@code FOR} body can reach the update block:
         * the emitted {@code CONT: do { body } while (false);} completes
         * normally when the body's reachable prefix can complete normally,
         * or when some reachable path executes a continue targeting this
         * loop — the {@code CONT} label re-tests the constant-false
         * condition and falls through to the update. A body whose every
         * emitted path leaves by {@code break}/{@code return}/{@code throw}
         * never reaches the update, so its emitted update statements would
         * be unreachable Java (JLS &sect;14.21).
         */
        private boolean forUpdateReachable(BlockId bodyBlock, OpId loopId) {
            return blockReachesForUpdate(bodyBlock, loopId, true);
        }

        /**
         * Whether a block's emitted reachable prefix reaches the update:
         * the same walk the emission applies (the non-owned, non-skipped
         * members in order, stopping at the first op whose emitted
         * statement cannot complete normally), with the two update-reaching
         * paths — a reachable {@code continue} targeting this loop exits the
         * do's constant-false test to the update, and a composite carrying
         * such a continue on one of its emitted paths does the same.
         * {@code fallsThrough} is true only for the do statement's own body,
         * whose fall-through reaches the false condition test and so the
         * update. A composite sub-block's fall-through only returns to the
         * enclosing block, whose remaining members the caller keeps walking,
         * so a later unconditional transfer still makes the update
         * unreachable.
         */
        private boolean blockReachesForUpdate(BlockId block, OpId loopId,
                                              boolean fallsThrough) {
            StructuredBodyTable ownerTable = blockTableOf.get(block);
            if (ownerTable == null) {
                ownerTable = table;
            }
            List<OpId> ops = ownerTable.blockOps().get(block);
            if (ops == null) {
                return fallsThrough;
            }
            for (OpId opId : ops) {
                if (ownedChildren.contains(opId) || skippedOps.contains(opId)) {
                    continue;
                }
                SemanticOp op = opsById.get(opId);
                if (op == null) {
                    continue;
                }
                if (reachesForUpdate(op, loopId)) {
                    return true;
                }
                if (!completesNormally(op)) {
                    return false;
                }
            }
            return fallsThrough;
        }

        /**
         * Whether some emitted path of one op reaches the update block: a
         * {@code continue} targeting this loop exits the emitted
         * {@code CONT} do statement to the update, and a composite does so
         * when one of its emitted sub-blocks carries such a path. Ordinary
         * fall-through is deliberately not an immediate answer here — a
         * composite that can complete normally returns to the enclosing
         * block, so the caller keeps walking its remaining members and
         * reports the update unreachable only at the first non-completing
         * statement after it.
         */
        private boolean reachesForUpdate(SemanticOp op, OpId loopId) {
            return switch (op.kind()) {
                case CONTINUE -> ((KindPayload.ContinuePayload) op.payload())
                    .loopId().equals(loopId);
                case TRY_CATCH -> {
                    KindPayload.TryCatchPayload payload =
                        (KindPayload.TryCatchPayload) op.payload();
                    yield blockReachesForUpdate(payload.tryBlock(), loopId, false)
                        || blockReachesForUpdate(payload.catchBlock(), loopId, false);
                }
                case BRANCH -> {
                    KindPayload.BranchPayload payload =
                        (KindPayload.BranchPayload) op.payload();
                    yield blockReachesForUpdate(payload.selectedBlock(), loopId, false)
                        || (payload.alternateBlock() != null
                            && blockReachesForUpdate(payload.alternateBlock(),
                                loopId, false));
                }
                default -> false;
            };
        }

        /**
         * Whether the emission walk of one block emits at least one
         * statement: the same owned/skipped filter {@code emitBlockOps}
         * applies, without the reachability walk.
         */
        private boolean blockHasEmittedOps(BlockId block) {
            StructuredBodyTable ownerTable = blockTableOf.get(block);
            if (ownerTable == null) {
                ownerTable = table;
            }
            List<OpId> ops = ownerTable.blockOps().get(block);
            if (ops == null) {
                return false;
            }
            for (OpId opId : ops) {
                if (!ownedChildren.contains(opId) && !skippedOps.contains(opId)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The {@link LoweredFunction} record of one function id across the
         * session's closure: a non-entry module's function body resolves
         * against its own module's function registry, never the entry
         * unit's (a cross-module call site's capture list is the callee's
         * own).
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

        /** The membership table of the unit owning one lowered function. */
        private StructuredBodyTable tableOfFunction(LoweredFunction function) {
            for (LoweredModuleUnit moduleUnit : units.values()) {
                if (moduleUnit.functions().containsKey(function.functionId())) {
                    return tables.get(moduleUnit.moduleId());
                }
            }
            return table;
        }

        private String indent(int level) {
            return "  ".repeat(level);
        }

        // -- per-op emission ---------------------------------------------------------

        private void emitOp(SemanticOp op, int indent) {
            switch (op.kind()) {
                case CONST -> emitConst(op, indent);
                case UNARY -> emitUnary(op, indent);
                case BINARY -> emitBinary(op, indent);
                case STRING_CONCAT -> emitConcat(op, indent);
                case ARRAY_NEW -> emitArrayNew(op, indent);
                case TABLE_NEW -> emitTableNew(op, indent);
                case ARRAY_LENGTH -> emitArrayLength(op, indent);
                case MEMBER_READ -> emitMemberRead(op, indent);
                case MEMBER_WRITE -> emitMemberWrite(op, indent);
                case MEMBER_DELETE -> emitMemberDelete(op, indent);
                case INDEX_NORMALIZE -> emitNormalize(op, indent);
                case INDEX_READ -> emitIndexRead(op, indent);
                case INDEX_WRITE -> emitIndexWrite(op, indent);
                case INDEX_DELETE -> emitIndexDelete(op, indent);
                case OPTIONAL_READ -> emitOptionalRead(op, indent);
                case HAS_FIELD -> emitHasField(op, indent);
                case FIELD_READ -> emitFieldRead(op, indent);
                case FIELD_WRITE -> emitFieldWrite(op, indent);
                case FIELD_DELETE -> emitFieldDelete(op, indent);
                case BOUNDARY -> emitFreeBoundary(op, indent);
                case BINDING_ALLOC -> emitBindingAlloc(op, indent);
                case BINDING_INIT -> emitBindingInit(op, indent);
                case BINDING_LOAD -> emitBindingLoad(op, indent);
                case BINDING_STORE -> emitBindingStore(op, indent);
                case CLOSURE_NEW -> emitClosureNew(op, indent);
                case RECURSIVE_GROUP_INIT -> emitRecursiveGroupInit(op, indent);
                case FUNCTION_ADAPT -> emitFunctionAdapt(op, indent);
                case CALLBACK_INVOKE -> emitCallbackInvoke(op, indent);
                case ASYNC_START -> emitAsyncStart(op, indent);
                case AWAIT -> emitAwait(op, indent);
                case ASSIGN -> emitAssign(op, indent);
                case DELETE -> emitDelete(op, indent);
                case CALL -> emitCall(op, indent);
                case INTRINSIC_CALL -> emitIntrinsic(op, indent);
                case STDLIB_CALL -> emitStdlib(op, indent);
                case BRANCH -> emitBranch(op, indent);
                case LOOP -> emitLoop(op, indent);
                case FOR_EACH -> emitForEach(op, indent);
                case TRY_CATCH -> emitTryCatch(op, indent);
                case THROW -> emitThrow(op, indent);
                case RETURN -> emitReturn(op, indent);
                case BREAK -> emitBreak(op, indent);
                case CONTINUE -> emitContinue(op, indent);
                case DISCARD -> emitDiscard(op, indent);
                case MODULE_IMPORT -> emitModuleImport(op, indent);
                case EXPORT_READ -> emitExportRead(op, indent);
                case EXPORT_PUBLISH -> emitExportPublish(op, indent);
                case EXTERNAL_ENTRY -> emitExternalEntryRecord(op, indent);
                case ENTRY_INVOKE -> emitEntryInvoke(op, indent);
                case MODULE_INIT -> emitModuleInit(op, indent);
                case JSON_FROM_CLASS -> emitJsonFromClass(op, indent);
                case JSON_TO_CLASS -> emitJsonToClass(op, indent);
                case CLASS_NEW -> emitClassNew(op, indent);
                case CLASS_DEFAULT -> throw new IllegalStateException("a CLASS_DEFAULT "
                    + "executes only under its triggering CLASS_NEW/CLASS_FACTORY "
                    + "(the detached default block's ops are the class-default "
                    + "method's; the block walk never runs the op itself)");
                case CLASS_FACTORY -> throw new IllegalStateException("a CLASS_FACTORY "
                    + "is a detached owner-module entry executed only under the "
                    + "triggering caller's CLASS_NEW (cross-unit parent) — the "
                    + "block walk never runs it)");
                default -> throw new IllegalStateException("op kind " + op.kind()
                    + " has no shared-JVM emission in this decomposition-tail domain");
            }
        }

        private String opKey(OpId id) {
            return id.module().path() + "#" + id.id();
        }

        private String parentKey(OpId parent) {
            return parent == null ? "-" : opKey(parent);
        }

        private void emitStart(SemanticOp op, int indent) {
            if (!trace) {
                return;
            }
            StringBuilder inputs = new StringBuilder();
            for (int i = 0; i < op.operands().size(); i++) {
                if (i > 0) {
                    inputs.append(", ");
                }
                inputs.append("JvmRuntime.atom(").append(slot(op.operands().get(i)))
                    .append(", ").append(javaString(staticKind(op.operandTypes().get(i))))
                    .append(")");
            }
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(op.opId()))).append(", \"START\", ")
                .append(javaString(op.kind().name())).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(").append(inputs).append("), null, null);\n");
        }

        /** START with the closed slot atom for the second operand (slot ops). */
        private void emitSlotOperandStart(SemanticOp op, int indent) {
            if (!trace) {
                return;
            }
            StringBuilder inputs = new StringBuilder();
            inputs.append("JvmRuntime.atom(").append(slot(op.operands().get(0)))
                .append(", ").append(javaString(staticKind(op.operandTypes().get(0))))
                .append("), ");
            inputs.append("\"slot:\" + ((long[]) ").append(slot(op.operands().get(1)))
                .append(")[0] + \"/\" + (((long[]) ").append(slot(op.operands().get(1)))
                .append(")[0] < ((long[]) ").append(slot(op.operands().get(1)))
                .append(")[1]) + \"/\" + (((long[]) ").append(slot(op.operands().get(1)))
                .append(")[2] == 1 && ((long[]) ").append(slot(op.operands().get(1)))
                .append(")[0] == ((long[]) ").append(slot(op.operands().get(1)))
                .append(")[1])");
            for (int i = 2; i < op.operands().size(); i++) {
                inputs.append(", JvmRuntime.atom(").append(slot(op.operands().get(i)))
                    .append(", ").append(javaString(staticKind(op.operandTypes().get(i))))
                    .append(")");
            }
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(op.opId()))).append(", \"START\", ")
                .append(javaString(op.kind().name())).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(").append(inputs).append("), null, null);\n");
        }

        /** The INDEX_READ START of a bytes read: the container atom plus the bytes slot atom. */
        private void emitBytesSlotOperandStart(SemanticOp op, int indent) {
            if (!trace) {
                return;
            }
            StringBuilder inputs = new StringBuilder();
            inputs.append("JvmRuntime.atom(").append(slot(op.operands().get(0)))
                .append(", ").append(javaString(staticKind(op.operandTypes().get(0))))
                .append("), ");
            inputs.append("\"byteslot:\" + ((Object[]) ").append(slot(op.operands().get(1)))
                .append(")[1] + \"/\" + (((Long) ((Object[]) ")
                .append(slot(op.operands().get(1))).append(")[1]) < ((Long) ((Object[]) ")
                .append(slot(op.operands().get(1))).append(")[2]))");
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(op.opId()))).append(", \"START\", ")
                .append(javaString(op.kind().name())).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(").append(inputs).append("), null, null);\n");
        }

        private void emitBoundaryStart(SemanticOp boundary, String inputExpr,
                                       RuntimeDescriptor inputDescriptor, int indent) {
            if (!trace) {
                return;
            }
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(boundary.opId()))).append(", \"START\", ")
                .append("\"BOUNDARY\", ")
                .append(javaString(boundary.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(boundary.origin().parentOpId())))
                .append(", List.of(JvmRuntime.atom(").append(inputExpr).append(", ")
                .append(javaString(staticKind(inputDescriptor)))
                .append(")), null, null);\n");
        }

        /**
         * One SUCCESS event of an op: the shared prefix (the op key, the
         * caller's kind label, the contract digest, and the parent) plus
         * the caller's payload tail (the output atom and the reason).
         */
        private void emitSuccessEvent(SemanticOp op, String kindName, String tail,
                                      int indent) {
            if (!trace) {
                return;
            }
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(op.opId()))).append(", \"SUCCESS\", ")
                .append(javaString(kindName)).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(), ").append(tail).append(");\n");
        }

        private void emitBoundarySuccess(SemanticOp boundary, String valueExpr,
                                         RuntimeDescriptor descriptor, int indent) {
            emitSuccessEvent(boundary, "BOUNDARY",
                "JvmRuntime.atom(" + valueExpr + ", "
                    + javaString(staticKind(descriptor)) + "), null", indent);
        }

        /** A boundary SUCCESS event with a raw output atom expression. */
        private void emitBoundarySuccessAtom(SemanticOp boundary, String atomExpr,
                                             int indent) {
            emitSuccessEvent(boundary, "BOUNDARY", atomExpr + ", null", indent);
        }

        /**
         * The op's SUCCESS event with the value-aware atom (the oracle's
         * {@code atomOf}): a deferred composite read atomizes the value's
         * own kind, never the declared descriptor's kind.
         */
        private void emitResultSuccessAtom(SemanticOp op, String valueExpr, int indent) {
            if (!trace) {
                return;
            }
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(op.opId()))).append(", \"SUCCESS\", ")
                .append(javaString(op.kind().name())).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(), JvmRuntime.rawAtom(").append(valueExpr)
                .append(", \"ref\"), null);\n");
        }

        private void emitResultSuccess(SemanticOp op, String valueExpr,
                                       RuntimeDescriptor resultDescriptor, int indent) {
            emitSuccessEvent(op, op.kind().name(),
                "JvmRuntime.atom(" + valueExpr + ", "
                    + javaString(staticKind(resultDescriptor)) + "), null", indent);
        }

        private void emitPlainSuccess(SemanticOp op, int indent) {
            emitSuccessEvent(op, op.kind().name(), "null, null", indent);
        }

        /** Emits the transfer closures of the ancestors down to (and including)
         *  the target loop; stops at an enclosing TRY_CATCH when requested
         *  (its dispatch closes it). */
        private void emitTransferClosures(SemanticOp transferOp, SemanticOp targetLoop,
                                          boolean stopAtTry, int indent) {
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
                emitPlainSuccess(ancestor, indent);
            }
        }

        /**
         * Whether a transfer's target label is defined at the given op's
         * emission level (the landed Lua rule, {@code transferTargetVisible}):
         * the walk from the emitting op's block outward reaches the target
         * loop before any TRY_CATCH ancestor exactly when the target's
         * labels are defined at the same level — the closer targets are at
         * the current level, while a farther one lies outside the nearest
         * enclosing protected body, where its label is not in scope. A
         * target the walk never reaches is not an enclosing structure — it
         * lives inside a protected body of this level (the transferred loop
         * resets at its own level) — and is equally invisible. A RETURN's
         * label lives at the function's top level, outside every protected
         * body, so it is visible exactly when the level carries no enclosing
         * boundary (the {@code null} target).
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

        /**
         * The emitted jump of one BREAK transfer (the label its target loop
         * defines): a FOR_EACH loop owns {@code FE<id>}, every other loop
         * form owns {@code LOOP<id>}.
         */
        private String breakJump(OpId loopId, SemanticOp target) {
            if (target != null && target.kind() == SemanticOpKind.FOR_EACH) {
                return "break FE" + loopId.id() + ";";
            }
            return "break " + loopLabel(loopId) + ";";
        }

        /**
         * The emitted jump of one CONTINUE transfer (the label its target
         * loop defines): a FOR_EACH loop owns {@code FE<id>}, a FOR loop
         * owns {@code CONT<id>}, and a WHILE loop owns {@code LOOP<id>}.
         */
        private String continueJump(OpId loopId, SemanticOp target) {
            if (target != null && target.kind() == SemanticOpKind.FOR_EACH) {
                return "continue FE" + loopId.id() + ";";
            }
            if (target != null && target.kind() == SemanticOpKind.LOOP
                    && ((KindPayload.LoopPayload) target.payload()).selector()
                        == deal.semantic.ir.ControlSelector.FOR) {
                return "continue CONT" + loopId.id() + ";";
            }
            return "continue " + loopLabel(loopId) + ";";
        }

        private void emitFailureEvent(OpId id, String kindName, SemanticOp op,
                                      String errExpr, int indent) {
            if (!trace) {
                return;
            }
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(id))).append(", \"FAILURE\", ")
                .append(javaString(kindName)).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(), null, ").append(errExpr).append(");\n");
        }

        static String numberLiteral(double value) {
            if (Double.isNaN(value)) {
                return "Double.valueOf(Double.NaN)";
            }
            if (Double.isInfinite(value)) {
                return value > 0 ? "Double.valueOf(Double.POSITIVE_INFINITY)"
                    : "Double.valueOf(Double.NEGATIVE_INFINITY)";
            }
            return "Double.valueOf(" + Double.toHexString(value) + ")";
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

        private void emitConst(SemanticOp op, int indent) {
            KindPayload.ConstPayload payload = (KindPayload.ConstPayload) op.payload();
            String literal = switch (payload.value()) {
                case ScalarValue.Null ignored -> "null";
                case ScalarValue.Boolean bool -> bool.value()
                    ? "Boolean.TRUE" : "Boolean.FALSE";
                case ScalarValue.Int intValue -> "Long.valueOf(" + intValue.value() + "L)";
                case ScalarValue.Number number -> numberLiteral(number.value());
                case ScalarValue.String string -> javaString(string.value());
            };
            emitStart(op, indent);
            out.append(indent(indent)).append(slot((ValueId) op.result())).append(" = ")
                .append(literal).append(";\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitUnary(SemanticOp op, int indent) {
            KindPayload.UnaryPayload payload = (KindPayload.UnaryPayload) op.payload();
            emitStart(op, indent);
            if (payload.selector() == deal.semantic.ir.UnarySelector.BOOL_NOT) {
                out.append(indent(indent)).append(slot((ValueId) op.result()))
                    .append(" = !((Boolean) ").append(slot(op.operands().get(0)))
                    .append(");\n");
            } else {
                out.append(indent(indent)).append(slot((ValueId) op.result()))
                    .append(" = JvmRuntime.unary(")
                    .append(javaString(payload.selector().name())).append(", ")
                    .append(slot(op.operands().get(0))).append(", ")
                    .append(javaString(opKey(op.opId()))).append(", ")
                    .append(javaString(op.contract().canonicalDigest())).append(", ")
                    .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(javaString(originOf(op))).append(");\n");
            }
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitBinary(SemanticOp op, int indent) {
            KindPayload.BinaryPayload payload = (KindPayload.BinaryPayload) op.payload();
            emitStart(op, indent);
            boolean arithmetic = payload.selector().name().startsWith("INT32_")
                || payload.selector().name().startsWith("NUMBER_");
            boolean comparison = payload.selector().name().endsWith("_EQ")
                || payload.selector().name().endsWith("_NE")
                || payload.selector().name().endsWith("_LT")
                || payload.selector().name().endsWith("_LE")
                || payload.selector().name().endsWith("_GT")
                || payload.selector().name().endsWith("_GE");
            if (arithmetic && !comparison) {
                out.append(indent(indent)).append(slot((ValueId) op.result()))
                    .append(" = JvmRuntime.arith(")
                    .append(javaString(payload.selector().name())).append(", ")
                    .append(slot(op.operands().get(0))).append(", ")
                    .append(slot(op.operands().get(1))).append(", ")
                    .append(javaString(opKey(op.opId()))).append(", ")
                    .append(javaString(op.contract().canonicalDigest())).append(", ")
                    .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(javaString(originOf(op))).append(");\n");
                emitResultSuccess(op, slot((ValueId) op.result()),
                    (RuntimeDescriptor) op.resultType(), indent);
                return;
            }
            out.append(indent(indent)).append(slot((ValueId) op.result()))
                .append(" = JvmRuntime.cmp(")
                .append(javaString(payload.selector().name())).append(", ")
                .append(javaString(staticKind(op.operandTypes().get(0)))).append(", ")
                .append(slot(op.operands().get(0))).append(", ")
                .append(javaString(staticKind(op.operandTypes().get(1)))).append(", ")
                .append(slot(op.operands().get(1))).append(", ")
                .append(payload.side() == null ? "null"
                    : javaString(payload.side().name())).append(");\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitConcat(SemanticOp op, int indent) {
            KindPayload.StringConcatPayload payload =
                (KindPayload.StringConcatPayload) op.payload();
            emitStart(op, indent);
            StringBuilder expr = new StringBuilder();
            for (ValueId fragment : payload.fragments()) {
                if (expr.length() > 0) {
                    expr.append(" + ");
                }
                expr.append("((String) ").append(slot(fragment)).append(")");
            }
            out.append(indent(indent)).append(slot((ValueId) op.result())).append(" = ")
                .append(expr.length() == 0 ? "\"\"" : expr).append(";\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitArrayNew(SemanticOp op, int indent) {
            KindPayload.ArrayNewPayload payload = (KindPayload.ArrayNewPayload) op.payload();
            emitStart(op, indent);
            String target = slot((ValueId) op.result());
            out.append(indent(indent)).append(target).append(" = new JvmRuntime.Array(")
                .append(payload.values().size()).append(");\n");
            for (int i = 0; i < payload.values().size(); i++) {
                OpId boundaryId = payload.elementBoundaryOpIds().get(i);
                SemanticOp boundary = opsById.get(boundaryId);
                ValueId input = payload.values().get(i);
                emitBoundaryStart(boundary, slot(input), payload.elementDescriptor(),
                    indent);
                out.append(indent(indent)).append("Object __be_").append(boundary.opId().id())
                    .append(" = ")
                    .append(bcheckArgs(payload.elementDescriptor(), slot(input)))
                    .append(";\n");
                out.append(indent(indent)).append("((JvmRuntime.Array) ").append(target)
                    .append(").elements.add(__be_").append(boundary.opId().id())
                    .append(");\n");
                emitBoundarySuccess(boundary, "__be_" + boundary.opId().id(),
                    payload.elementDescriptor(), indent);
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitTableNew(SemanticOp op, int indent) {
            KindPayload.TableNewPayload payload = (KindPayload.TableNewPayload) op.payload();
            emitStart(op, indent);
            String target = slot((ValueId) op.result());
            out.append(indent(indent)).append(target).append(" = new JvmRuntime.Table();\n");
            for (KindPayload.TableEntry entry : payload.entries()) {
                out.append(indent(indent)).append("((").append(
                        "JvmRuntime.Table) ").append(target).append(").write(")
                    .append(javaString(entry.key())).append(", ")
                    .append(slot(entry.value())).append(");\n");
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitArrayLength(SemanticOp op, int indent) {
            KindPayload.ArrayLengthPayload payload =
                (KindPayload.ArrayLengthPayload) op.payload();
            emitStart(op, indent);
            String target = slot((ValueId) op.result());
            if (isBytesValue(payload.arrayValue())) {
                // K6 item 7: b.length reads the bytes buffer's fixed
                // logical length at the read's own origin.
                out.append(indent(indent)).append(target)
                    .append(" = JvmRuntime.bytesLength(")
                    .append(slot(payload.arrayValue())).append(", ")
                    .append(javaString(opKey(op.opId()))).append(", ")
                    .append(javaString(op.contract().canonicalDigest())).append(", ")
                    .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(javaString(originOf(op))).append(");\n");
            } else {
                out.append(indent(indent)).append(target)
                    .append(" = Long.valueOf(((").append("JvmRuntime.Array) ")
                    .append(slot(payload.arrayValue())).append(").length);\n");
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
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

        private void emitMemberRead(SemanticOp op, int indent) {
            KindPayload.MemberReadPayload payload = (KindPayload.MemberReadPayload) op.payload();
            emitStart(op, indent);
            String target = slot((ValueId) op.result());
            out.append(indent(indent)).append(target).append(" = ((")
                .append("JvmRuntime.Table) ").append(slot(payload.table())).append(").read(")
                .append(javaString(payload.key())).append(");\n");
            SemanticOp boundary = boundaryChildOf(op);
            if (boundary != null) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                if (trace) {
                    // Custom boundary START: the input atom renders the
                    // actual value kind (the oracle's atomOf — a
                    // wrong-kind present value atomizes as its own kind).
                    out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                        .append(javaString(opKey(boundary.opId())))
                        .append(", \"START\", \"BOUNDARY\", ")
                        .append(javaString(boundary.contract().canonicalDigest()))
                        .append(", ")
                        .append(javaString(parentKey(boundary.origin().parentOpId())))
                        .append(", List.of(JvmRuntime.rawAtom(")
                        .append(target).append(", ")
                        .append(javaString(staticKind(boundaryPayload.descriptor())))
                        .append(")), null, null);\n");
                }
                out.append(indent(indent)).append("  Object __mr_")
                    .append(boundary.opId().id()).append(";\n");
                out.append(indent(indent)).append("  try {\n");
                if (defersContextualCheck(boundaryPayload.descriptor())) {

                    out.append(indent(indent)).append("    __mr_")
                        .append(boundary.opId().id()).append(" = ")
                        .append(target).append(";\n");
                } else {
                    out.append(indent(indent)).append("    __mr_")
                        .append(boundary.opId().id())
                        .append(" = ")
                        .append(bcheckArgs(boundaryPayload.descriptor(), target))
                        .append(";\n");
                }
                out.append(indent(indent)).append("  } catch (JvmRuntime.DealError __be) {\n");
                out.append(indent(indent))
                    .append("    JvmRuntime.DealError __bre = new JvmRuntime.DealError("
                        + "__be.code, __be.msg, ")
                    .append(javaString(originOf(boundary)))
                    .append(", __be.expected, __be.actual, __be.frames, null);\n");
                if (trace) {
                    emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                        "JvmRuntime.errtext(__bre)", indent + 1);
                    emitFailureEvent(op.opId(), op.kind().name(), op,
                        "JvmRuntime.errtext(__bre)", indent + 1);
                }
                out.append(indent(indent)).append("    throw __bre;\n");
                out.append(indent(indent)).append("  }\n");
                out.append(indent(indent)).append(target).append(" = __mr_")
                    .append(boundary.opId().id()).append(";\n");
                if (defersContextualCheck(boundaryPayload.descriptor())) {
                    emitBoundarySuccessAtom(boundary, "JvmRuntime.rawAtom("
                        + target + ", "
                        + javaString(staticKind(boundaryPayload.descriptor())) + ")",
                        indent);
                    // The deferred composite read's own SUCCESS atom renders
                    // the value's own kind too (the oracle's value-aware
                    // publish, never the declared kind).
                    emitResultSuccessAtom(op, target, indent);
                } else {
                    emitBoundarySuccess(boundary, target,
                        boundaryPayload.descriptor(), indent);
                    emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(),
                        indent);
                }
                return;
            }
            // The OPTIONAL_READ envelope shape: the raw read publishes the
            // internal missing or the present value (present null included)
            // and its SUCCESS atom renders the actual value kind —
            // missing → "missing", null → "null", else the actual-kind
            // atom, exactly the oracle's publish (a wrong-kind present
            // value atomizes as its own kind, never the declared kind).
            SemanticOp optional = optionalReadOf((ValueId) op.result());
            RuntimeDescriptor inner = optional == null
                ? null
                : ((KindPayload.OptionalReadPayload) optional.payload()).descriptor();
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(op.opId()))).append(", \"SUCCESS\", ")
                .append(javaString(op.kind().name())).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(), JvmRuntime.rawAtom(").append(target)
                .append(", ")
                .append(javaString(inner == null ? "ref" : "nullable:" + staticKind(inner)))
                .append("), null);\n");
        }

        /**
         * OPTIONAL_READ: the internal missing pre-maps to language null
         * before the present branch is validated (the closed table's
         * optional-read rule); the CONTEXTUAL_TABLE_READ boundary child
         * validates the pre-mapped result.
         */
        private void emitOptionalRead(SemanticOp op, int indent) {
            KindPayload.OptionalReadPayload payload =
                (KindPayload.OptionalReadPayload) op.payload();
            if (payload.value() == null) {
                emitStart(op, indent);
            } else {
                // Custom START: the raw operand atom renders the actual
                // value kind (a wrong-kind present value atomizes as its
                // own kind, exactly the oracle's publish).
                out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                    .append(javaString(opKey(op.opId()))).append(", \"START\", ")
                    .append(javaString(op.kind().name())).append(", ")
                    .append(javaString(op.contract().canonicalDigest())).append(", ")
                    .append(javaString(parentKey(op.origin().parentOpId())))
                    .append(", List.of(JvmRuntime.rawAtom(")
                    .append(slot(payload.value())).append(", ")
                    .append(javaString("nullable:" + staticKind(payload.descriptor())))
                    .append(")), null, null);\n");
            }
            String target = slot((ValueId) op.result());
            if (payload.value() == null) {
                out.append(indent(indent)).append(target).append(" = null;\n");
            } else {
                String source = slot(payload.value());
                out.append(indent(indent)).append(target).append(" = ")
                    .append(source).append(" == JvmRuntime.MISSING ? null : ")
                    .append(source).append(";\n");
            }
            SemanticOp boundary = boundaryChildOf(op);
            if (boundary != null) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                // Custom boundary START: the input atom renders the
                // actual value kind (identical for a passing value,
                // actual-kind for a wrong-kind present value).
                out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                    .append(javaString(opKey(boundary.opId())))
                    .append(", \"START\", \"BOUNDARY\", ")
                    .append(javaString(boundary.contract().canonicalDigest())).append(", ")
                    .append(javaString(parentKey(boundary.origin().parentOpId())))
                    .append(", List.of(JvmRuntime.rawAtom(").append(target)
                    .append(", ")
                    .append(javaString(staticKind(boundaryPayload.descriptor())))
                    .append(")), null, null);\n");

                String checkedName = "__orb_" + boundary.opId().id();
                String errorName = "__obe_" + boundary.opId().id();
                String rebuiltName = "__obe2_" + boundary.opId().id();
                out.append(indent(indent)).append("try {\n");
                if (defersContextualCheck(boundaryPayload.descriptor())) {
                    out.append(indent(indent + 1)).append("Object ")
                        .append(checkedName).append(" = ").append(target)
                        .append(";\n");
                } else {
                    out.append(indent(indent + 1)).append("Object ")
                        .append(checkedName)
                        .append(" = ")
                        .append(bcheckArgs(boundaryPayload.descriptor(), target))
                        .append(";\n");
                }
                out.append(indent(indent + 1)).append(target).append(" = ")
                    .append(checkedName).append(";\n");
                out.append(indent(indent)).append("} catch (JvmRuntime.DealError ")
                    .append(errorName).append(") {\n");
                out.append(indent(indent + 1)).append("JvmRuntime.DealError ")
                    .append(rebuiltName).append(" = new JvmRuntime.DealError(")
                    .append(errorName).append(".code, ").append(errorName)
                    .append(".msg, ").append(javaString(originOf(boundary)))
                    .append(", ").append(errorName).append(".expected, ")
                    .append(errorName).append(".actual, ").append(errorName)
                    .append(".frames, ").append(errorName).append(".cause);\n");
                emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                    "JvmRuntime.errtext(" + rebuiltName + ")", indent + 1);
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "JvmRuntime.errtext(" + rebuiltName + ")", indent + 1);
                out.append(indent(indent + 1)).append("throw ").append(rebuiltName)
                    .append(";\n");
                out.append(indent(indent)).append("}\n");
                if (defersContextualCheck(boundaryPayload.descriptor())) {
                    emitBoundarySuccessAtom(boundary, "JvmRuntime.rawAtom("
                        + target + ", "
                        + javaString(staticKind(boundaryPayload.descriptor())) + ")",
                        indent);
                } else {
                    emitBoundarySuccess(boundary, target,
                        boundaryPayload.descriptor(), indent);
                }
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
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
         * present (present null included) → true, absent → false. The
         * runtime helper realizes the table-presence half; the
         * class-instance presence flags are the CLASSES family's
         * realization.
         */
        private void emitHasField(SemanticOp op, int indent) {
            KindPayload.HasFieldPayload payload =
                (KindPayload.HasFieldPayload) op.payload();
            emitStart(op, indent);
            String target = slot((ValueId) op.result());
            out.append(indent(indent)).append(target).append(" = JvmRuntime.hasField(")
                .append(slot(payload.receiver())).append(", ")
                .append(javaString(payload.key())).append(");\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
        }

        // =====================================================================
        // The class field ops (CLASSES step 8, E5): FIELD_READ is
        // presence-aware (missing → the OPTIONAL_FIELD_READ boundary's
        // pre-mapped language null; present null stays distinguishable
        // through the carrier's presence flags); FIELD_WRITE/FIELD_DELETE
        // are commit ops consuming the ASSIGN/DELETE chain's resolved
        // receiver and stored value — exactly one mutation, never a
        // re-evaluated source expression.
        // =====================================================================

        private void emitFieldRead(SemanticOp op, int indent) {
            KindPayload.FieldReadPayload payload =
                (KindPayload.FieldReadPayload) op.payload();
            emitStart(op, indent);
            String receiver = "__frr_" + op.opId().id();
            emitFieldBoundaryCheck(op,
                boundaryChildOfKind(op, BoundaryKind.UNTYPED_CLASS_INPUT),
                slot(payload.classValue()), receiver, indent);
            String read = "__frv_" + op.opId().id();
            out.append(indent(indent)).append("Object ").append(read)
                .append(" = ((").append("JvmRuntime.ClassInstance) ")
                .append(receiver).append(").read(")
                .append(javaString(payload.field())).append(");\n");
            out.append(indent(indent)).append(read).append(" = ").append(read)
                .append(" == JvmRuntime.MISSING ? null : ").append(read)
                .append(";\n");
            emitFieldBoundaryCheck(op,
                boundaryChildOfKind(op, BoundaryKind.OPTIONAL_FIELD_READ),
                read, "__frc_" + op.opId().id(), indent);
            out.append(indent(indent)).append(slot((ValueId) op.result()))
                .append(" = __frc_").append(op.opId().id()).append(";\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitFieldWrite(SemanticOp op, int indent) {
            KindPayload.FieldWritePayload payload =
                (KindPayload.FieldWritePayload) op.payload();
            emitStart(op, indent);
            String receiver = "__fwr_" + op.opId().id();
            emitFieldBoundaryCheck(op,
                boundaryChildOfKind(op, BoundaryKind.UNTYPED_CLASS_INPUT),
                slot(payload.classValue()), receiver, indent);
            String value = "__fwv_" + op.opId().id();
            emitFieldBoundaryCheck(op,
                boundaryChildOfKind(op, BoundaryKind.CLASS_FIELD_ASSIGNMENT),
                slot(payload.value()), value, indent);
            out.append(indent(indent)).append("((").append("JvmRuntime.ClassInstance) ")
                .append(receiver).append(").write(")
                .append(javaString(payload.field())).append(", ").append(value)
                .append(");\n");
            emitPlainSuccess(op, indent);
        }

        private void emitFieldDelete(SemanticOp op, int indent) {
            KindPayload.FieldDeletePayload payload =
                (KindPayload.FieldDeletePayload) op.payload();
            emitStart(op, indent);
            String receiver = "__fdr_" + op.opId().id();
            emitFieldBoundaryCheck(op,
                boundaryChildOfKind(op, BoundaryKind.UNTYPED_CLASS_INPUT),
                slot(payload.classValue()), receiver, indent);
            out.append(indent(indent)).append("((").append("JvmRuntime.ClassInstance) ")
                .append(receiver).append(").delete(")
                .append(javaString(payload.field())).append(");\n");
            emitPlainSuccess(op, indent);
        }

        /**
         * One field-op boundary child: the START carries the input's
         * actual runtime atom (the raw atom — a null receiver renders
         * "null", never an allocation id), the check runs into the given
         * local, and the terminal is the boundary SUCCESS with its
         * published value or the two FAILURE events (boundary then owner)
         * with the boundary's own origin.
         */
        private void emitFieldBoundaryCheck(SemanticOp owner, SemanticOp boundary,
                                            String inputExpr, String checkedName,
                                            int indent) {
            KindPayload.BoundaryPayload payload =
                (KindPayload.BoundaryPayload) boundary.payload();
            if (trace) {
                out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                    .append(javaString(opKey(boundary.opId())))
                    .append(", \"START\", \"BOUNDARY\", ")
                    .append(javaString(boundary.contract().canonicalDigest()))
                    .append(", ")
                    .append(javaString(parentKey(boundary.origin().parentOpId())))
                    .append(", List.of(JvmRuntime.rawAtom(").append(inputExpr)
                    .append(", ")
                    .append(javaString(staticKind(payload.descriptor())))
                    .append(")), null, null);\n");
            }
            out.append(indent(indent)).append("Object ").append(checkedName)
                .append(";\n");
            out.append(indent(indent)).append("try {\n");
            out.append(indent(indent + 1)).append(checkedName)
                .append(" = ")
                .append(bcheckArgs(payload.descriptor(), inputExpr))
                .append(";\n");
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __fbe) {\n");
            out.append(indent(indent + 1))
                .append("JvmRuntime.DealError __fbre = new JvmRuntime.DealError("
                    + "__fbe.code, __fbe.msg, ")
                .append(javaString(originOf(boundary)))
                .append(", __fbe.expected, __fbe.actual, __fbe.frames, null);\n");
            if (trace) {
                emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                    "JvmRuntime.errtext(__fbre)", indent + 1);
                emitFailureEvent(owner.opId(), owner.kind().name(), owner,
                    "JvmRuntime.errtext(__fbre)", indent + 1);
            }
            out.append(indent(indent + 1)).append("throw __fbre;\n");
            out.append(indent(indent)).append("}\n");
            emitBoundarySuccess(boundary, checkedName, payload.descriptor(), indent);
        }

        private void emitMemberWrite(SemanticOp op, int indent) {
            KindPayload.MemberWritePayload payload =
                (KindPayload.MemberWritePayload) op.payload();
            emitStart(op, indent);
            out.append(indent(indent)).append("((").append("JvmRuntime.Table) ")
                .append(slot(payload.table())).append(").write(")
                .append(javaString(payload.key())).append(", ")
                .append(slot(payload.value())).append(");\n");
            emitPlainSuccess(op, indent);
        }

        private void emitMemberDelete(SemanticOp op, int indent) {
            KindPayload.MemberDeletePayload payload =
                (KindPayload.MemberDeletePayload) op.payload();
            emitStart(op, indent);
            out.append(indent(indent)).append("((").append("JvmRuntime.Table) ")
                .append(slot(payload.table())).append(").remove(")
                .append(javaString(payload.key())).append(");\n");
            emitPlainSuccess(op, indent);
        }

        private void emitNormalize(SemanticOp op, int indent) {
            KindPayload.IndexNormalizePayload payload =
                (KindPayload.IndexNormalizePayload) op.payload();
            emitStart(op, indent);
            String target = slot((ValueId) op.result());
            switch (payload.mode()) {
                case ARRAY_READ, ARRAY_WRITE -> {
                    boolean write = payload.mode() == deal.semantic.ir.IndexMode.ARRAY_WRITE;
                    // The normalized index/length are numeric views: a
                    // runtime number carrier (the value model's variant
                    // representation) unwraps, a plain Long passes through.
                    out.append(indent(indent)).append(target)
                        .append(" = new long[]{JvmRuntime.indexOf(")
                        .append(slot(payload.rawKey())).append("), JvmRuntime.indexOf(")
                        .append(slot(payload.currentLength())).append("), ")
                        .append(write ? "1L" : "0L").append("};\n");
                }
                case BYTES_READ, BYTES_WRITE ->
                    // The bytes slot (K6 item 5): the index against the
                    // receiver's length read; no append decision exists.
                    out.append(indent(indent)).append(target)
                        .append(" = new Object[]{\"b\", Long.valueOf(JvmRuntime.indexOf(")
                        .append(slot(payload.rawKey()))
                        .append(")), Long.valueOf(JvmRuntime.indexOf(")
                        .append(slot(payload.currentLength())).append("))};\n");
                case TABLE_READ, TABLE_WRITE ->
                    out.append(indent(indent)).append(target).append(" = new Object[]{")
                        .append("\"t\", ").append(slot(payload.rawKey())).append("};\n");
            }
            // SUCCESS with the closed slot atom.
            out.append(indent(indent)).append("if (").append(target)
                .append(" instanceof long[]) {\n");
            out.append(indent(indent)).append("  long[] __s = (long[]) ").append(target)
                .append(";\n");
            out.append(indent(indent)).append(
                "  JvmRuntime.ev(MODULE, ").append(javaString(opKey(op.opId())))
                .append(", \"SUCCESS\", \"INDEX_NORMALIZE\", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(), \"slot:\" + __s[0] + \"/\" + (__s[0] < __s[1]) "
                    + "+ \"/\" + (__s[2] == 1 && __s[0] == __s[1]), null);\n");
            out.append(indent(indent)).append("} else if (\"b\".equals(((Object[]) ")
                .append(target).append(")[0])) {\n");
            out.append(indent(indent)).append("  Object[] __s = (Object[]) ").append(target)
                .append(";\n");
            out.append(indent(indent)).append(
                "  JvmRuntime.ev(MODULE, ").append(javaString(opKey(op.opId())))
                .append(", \"SUCCESS\", \"INDEX_NORMALIZE\", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(), \"byteslot:\" + __s[1] + \"/\" + (((Long) __s[1]) "
                    + "< ((Long) __s[2])), null);\n");
            out.append(indent(indent)).append("} else {\n");
            out.append(indent(indent)).append("  Object[] __s = (Object[]) ").append(target)
                .append(";\n");
            out.append(indent(indent)).append(
                "  JvmRuntime.ev(MODULE, ").append(javaString(opKey(op.opId())))
                .append(", \"SUCCESS\", \"INDEX_NORMALIZE\", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(), \"keyslot:\" + JvmRuntime.esc((String) __s[1]), "
                    + "null);\n");
            out.append(indent(indent)).append("}\n");
        }

        private void emitIndexRead(SemanticOp op, int indent) {
            KindPayload.IndexReadPayload payload = (KindPayload.IndexReadPayload) op.payload();
            SemanticOp boundary = opsById.get(payload.elementBoundaryOpId());
            RuntimeDescriptor descriptor =
                ((KindPayload.BoundaryPayload) boundary.payload()).descriptor();
            boolean nullable = descriptor instanceof RuntimeDescriptor.Nullable;
            RuntimeDescriptor inner = nullable
                ? ((RuntimeDescriptor.Nullable) descriptor).inner() : descriptor;
            if (!op.operandTypes().isEmpty()
                    && op.operandTypes().get(0) instanceof RuntimeDescriptor.Bytes) {
                // K6 item 2: the bytes element read — the length read's
                // result is the normalize's currentLength and the
                // BYTE_ELEMENT_READ cell consumes {index, length} from the
                // same slot.
                emitBytesSlotOperandStart(op, indent);
                String bytesTarget = slot((ValueId) op.result());
                out.append(indent(indent)).append(bytesTarget)
                    .append(" = JvmRuntime.bytesRead(")
                    .append(javaString(opKey(op.opId()))).append(", ")
                    .append(javaString(op.contract().canonicalDigest())).append(", ")
                    .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(javaString(opKey(boundary.opId()))).append(", ")
                    .append(javaString(boundary.contract().canonicalDigest())).append(", ")
                    .append(javaString(parentKey(boundary.origin().parentOpId())))
                    .append(", ").append(slot(payload.container())).append(", ((Long) ")
                    .append("((Object[]) ").append(slot(payload.slot()))
                    .append(")[1]).longValue(), ((Long) ((Object[]) ")
                    .append(slot(payload.slot())).append(")[2]).longValue(), ")
                    .append(javaString(originOf(boundary))).append(");\n");
                emitResultSuccess(op, bytesTarget, (RuntimeDescriptor) op.resultType(),
                    indent);
                return;
            }
            emitSlotOperandStart(op, indent);
            String target = slot((ValueId) op.result());
            out.append(indent(indent)).append(target).append(" = JvmRuntime.arrayRead(")
                .append(javaString(opKey(op.opId()))).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                .append(javaString(opKey(boundary.opId()))).append(", ")
                .append(javaString(boundary.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(boundary.origin().parentOpId()))).append(", ")
                .append(javaString(descriptorText(descriptor))).append(", ")
                .append(javaString(staticKind(inner))).append(", (JvmRuntime.Array) ")
                .append(slot(payload.container())).append(", ((long[]) ")
                .append(slot(payload.slot())).append(")[0], ")
                .append(nullable ? "true" : "false").append(", ")
                .append(javaString(originOf(op))).append(");\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitIndexWrite(SemanticOp op, int indent) {
            KindPayload.IndexWritePayload payload =
                (KindPayload.IndexWritePayload) op.payload();
            emitStart(op, indent);
            String container = slot(payload.container());
            String slotName = slot(payload.slot());
            String value = slot(payload.value());
            out.append(indent(indent)).append("if (").append(slotName)
                .append(" instanceof long[]) {\n");
            out.append(indent(indent)).append("  long[] __s = (long[]) ").append(slotName)
                .append(";\n");
            out.append(indent(indent)).append("  if (__s[2] == 1 && __s[0] == __s[1]) ((")
                .append("JvmRuntime.Array) ").append(container).append(").length++;\n");
            out.append(indent(indent)).append("  int __wi = (int) __s[0];\n");
            out.append(indent(indent)).append("  while (((")
                .append("JvmRuntime.Array) ").append(container)
                .append(").elements.size() <= __wi) ((")
                .append("JvmRuntime.Array) ").append(container)
                .append(").elements.add(null);\n");
            out.append(indent(indent)).append("  ((")
                .append("JvmRuntime.Array) ").append(container)
                .append(").elements.set(__wi, ").append(value).append(");\n");
            out.append(indent(indent)).append("} else if (\"b\".equals(((Object[]) ")
                .append(slotName).append(")[0])) {\n");
            // The bytes commit (K6 item 4): the E8013 value-range check at
            // the assignment expression and the single in-place mutation;
            // the bounds already passed at the chain's
            // BYTE_ELEMENT_ASSIGNMENT boundary.
            out.append(indent(indent)).append("  Object[] __s = (Object[]) ").append(slotName)
                .append(";\n");
            out.append(indent(indent)).append("  JvmRuntime.bytesCommit(")
                .append(javaString(opKey(op.opId()))).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                .append(container).append(", ((Long) __s[1]).longValue(), ").append(value)
                .append(", ").append(javaString(originOf(op))).append(");\n");
            out.append(indent(indent)).append("} else {\n");
            out.append(indent(indent)).append("  Object[] __s = (Object[]) ").append(slotName)
                .append(";\n");
            out.append(indent(indent)).append("  ((").append("JvmRuntime.Table) ")
                .append(container).append(").write((String) __s[1], ").append(value)
                .append(");\n");
            out.append(indent(indent)).append("}\n");
            emitPlainSuccess(op, indent);
        }

        private void emitIndexDelete(SemanticOp op, int indent) {
            KindPayload.IndexDeletePayload payload =
                (KindPayload.IndexDeletePayload) op.payload();
            emitStart(op, indent);
            String container = slot(payload.container());
            String slotName = slot(payload.slot());
            out.append(indent(indent)).append("if (").append(slotName)
                .append(" instanceof long[]) {\n");
            out.append(indent(indent)).append("  long[] __s = (long[]) ").append(slotName)
                .append(";\n");
            out.append(indent(indent)).append("  if (__s[0] < ((")
                .append("JvmRuntime.Array) ").append(container).append(").length && __s[0] < ((")
                .append("JvmRuntime.Array) ").append(container)
                .append(").elements.size()) ((").append("JvmRuntime.Array) ")
                .append(container).append(").elements.set((int) __s[0], "
                    + "JvmRuntime.MISSING);\n");
            out.append(indent(indent)).append("} else {\n");
            out.append(indent(indent)).append("  Object[] __s = (Object[]) ").append(slotName)
                .append(";\n");
            out.append(indent(indent)).append("  ((").append("JvmRuntime.Table) ")
                .append(container).append(").remove((String) __s[1]);\n");
            out.append(indent(indent)).append("}\n");
            emitPlainSuccess(op, indent);
        }

        /**
         * A free BOUNDARY op: the runtime check over the payload input,
         * run under the boundary's own origin with the boundary's own
         * single FAILURE terminal — the parented arms' contract (no owner
         * terminal exists for an unparented boundary); the success
         * terminal is unchanged.
         */
        private void emitFreeBoundary(SemanticOp op, int indent) {
            KindPayload.BoundaryPayload payload = (KindPayload.BoundaryPayload) op.payload();
            if (trace) {
                out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(op.opId()))).append(", \"START\", ")
                .append("\"BOUNDARY\", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(), null, null);\n");
            }
            String target = "__fb_" + op.opId().id();
            out.append(indent(indent)).append("Object ").append(target).append(";\n");
            out.append(indent(indent)).append("try {\n");
            out.append(indent(indent)).append("  ").append(target)
                .append(" = ")
                .append(bcheckArgs(payload.descriptor(), slot(payload.input())))
                .append(";\n");
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __be) {\n");
            out.append(indent(indent))
                .append("  JvmRuntime.DealError __bre = new JvmRuntime.DealError("
                    + "__be.code, __be.msg, ")
                .append(javaString(originOf(op)))
                .append(", __be.expected, __be.actual, __be.frames, null);\n");
            if (trace) {
                emitFailureEvent(op.opId(), "BOUNDARY", op,
                    "JvmRuntime.errtext(__bre)", indent + 1);
            }
            out.append(indent(indent)).append("  throw __bre;\n");
            out.append(indent(indent)).append("}\n");
            if (op.result() instanceof ValueId valueId) {
                out.append(indent(indent)).append(slot(valueId)).append(" = ")
                    .append(target).append(";\n");
                emitBoundarySuccess(op, slot(valueId), payload.descriptor(), indent);
            } else {
                emitBoundarySuccess(op, target, payload.descriptor(), indent);
            }
        }

        private void emitBindingAlloc(SemanticOp op, int indent) {
            KindPayload.BindingAllocPayload payload =
                (KindPayload.BindingAllocPayload) op.payload();
            emitStart(op, indent);
            if (isCatchBinding(payload.binding())) {

                emitPlainSuccess(op, indent);
                return;
            }
            switch (cellKindOf(payload.binding(), payload.generation())) {
                case DIRECT -> out.append(indent(indent))
                    .append(cell(payload.binding(), payload.generation())).append(" = null;\n");
                case SHARED_CELL -> out.append(indent(indent))
                    .append(cell(payload.binding(), payload.generation())).append(" = new Object[1];\n");
            }
            emitPlainSuccess(op, indent);
        }

        private void emitBindingInit(SemanticOp op, int indent) {
            KindPayload.BindingInitPayload payload =
                (KindPayload.BindingInitPayload) op.payload();
            emitStart(op, indent);
            BindingCellKind kind = cellKindOf(payload.binding(), payload.generation());
            String valueExpr = intrinsicCarrierExpr(payload.value());
            if (valueExpr == null) {
                valueExpr = hasProducer(payload.value())
                    ? slot(payload.value()) : exportPlaceholderCarrier();
            }
            if (kind == BindingCellKind.SHARED_CELL) {
                out.append(indent(indent)).append("((").append("Object[]) ")
                    .append(cellSource(payload.binding(), payload.generation())).append(")[0] = ")
                    .append(valueExpr).append(";\n");
            } else {
                out.append(indent(indent)).append(cellSource(payload.binding(), payload.generation())).append(" = ")
                    .append(valueExpr).append(";\n");
            }
            emitPlainSuccess(op, indent);
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
                    new deal.semantic.ir.FunctionAllocationIdentity(valueId.id()));
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
            return "JvmRuntime.intrinsicExport()";
        }

        /**
         * The memoized carrier accessor of one intrinsic kind: the kind
         * tag and the intrinsic's declared descriptor text and canonical
         * spec text — the only descriptor source (never a call site's or
         * an adapter target's).
         */
        private static String intrinsicAccessor(IntrinsicKind kind) {
            RuntimeDescriptor.Func declared = kind.declaredSignature();
            return "JvmRuntime.intrinsic(" + javaString(kind.name()) + ", "
                + javaString(descriptorText(declared)) + ", "
                + javaString(declared.canonicalSpecText()) + ")";
        }

        private void emitBindingLoad(SemanticOp op, int indent) {
            KindPayload.BindingLoadPayload payload =
                (KindPayload.BindingLoadPayload) op.payload();
            emitStart(op, indent);
            BindingCellKind kind = cellKindOf(payload.binding(), payload.generation());
            out.append(indent(indent)).append(slot((ValueId) op.result())).append(" = ")
                .append(kind == BindingCellKind.SHARED_CELL
                    ? "((Object[]) " + cellSource(payload.binding(), payload.generation()) + ")[0]"
                    : cellSource(payload.binding(), payload.generation()))
                .append(";\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitBindingStore(SemanticOp op, int indent) {
            KindPayload.BindingStorePayload payload =
                (KindPayload.BindingStorePayload) op.payload();
            emitStart(op, indent);
            BindingCellKind kind = cellKindOf(payload.binding(), payload.generation());
            if (kind == BindingCellKind.SHARED_CELL) {
                out.append(indent(indent)).append("((").append("Object[]) ")
                    .append(cellSource(payload.binding(), payload.generation())).append(")[0] = ")
                    .append(slot(payload.value())).append(";\n");
            } else {
                out.append(indent(indent)).append(cellSource(payload.binding(), payload.generation())).append(" = ")
                    .append(slot(payload.value())).append(";\n");
            }
            emitPlainSuccess(op, indent);
        }

        private void emitClosureNew(SemanticOp op, int indent) {
            KindPayload.ClosureNewPayload payload =
                (KindPayload.ClosureNewPayload) op.payload();
            emitStart(op, indent);
            String target = slot((ValueId) op.result());
            StringBuilder args = new StringBuilder();
            for (BindingGeneration capture : payload.captures()) {
                if (args.length() > 0) {
                    args.append(", ");
                }
                args.append(captureArg(capture));
            }
            out.append(indent(indent)).append(target).append(" = ")
                .append(fnFactory(payload.function())).append("(").append(args)
                .append(");\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
        }

        /**
         * FUNCTION_ADAPT (E6, D15 creation): a generated adapter object
         * carrying the closed capture mode — VALUE retains the
         * creation-time source identity; SHARED_CELL records the
         * generation cell re-read per invocation (live reassignment
         * observed); REEVALUATE_THUNK records the detached thunk
         * re-executor method. Creation evaluates no thunk and reads no
         * binding (VALUE's single operand already completed); every
         * invocation runs the D15 sequence through
         * {@code JvmRuntime.invokeAdapter} with the invoking op's
         * origin.
         */
        private void emitFunctionAdapt(SemanticOp op, int indent) {
            KindPayload.FunctionAdaptPayload payload =
                (KindPayload.FunctionAdaptPayload) op.payload();
            // The VALUE operand's carrier expression: for the seeded
            // intrinsic identity no field holds it before this creation, so
            // the START's operand atom and the adapter's own value field read
            // the one local the creation materializes (never an undeclared
            // slot).
            String valueLocal = null;
            if (payload.source() instanceof AdaptSourceRef.Value value) {
                valueLocal = "__iav_" + op.opId().id();
                out.append(indent(indent)).append("Object ").append(valueLocal)
                    .append(" = ").append(adaptValueExpr(value)).append(";\n");
            }
            emitAdaptStart(op, payload, valueLocal, indent);
            String target = slot((ValueId) op.result());
            out.append(indent(indent)).append(target)
                .append(" = new JvmRuntime.AdapterValue(__a -> { throw new "
                    + "IllegalStateException(\"an adapter executes only under its "
                    + "invoking op's D15 protocol\"); }, ")
                .append(javaString(descriptorText(payload.targetSignature())))
                .append(", ")
                .append(javaString(payload.targetSignature().canonicalSpecText()))
                .append(", ");
            switch (payload.mode()) {
                case VALUE -> out.append("0");
                case SHARED_CELL -> out.append("1");
                case REEVALUATE_THUNK -> out.append("2");
            }
            switch (payload.source()) {
                case AdaptSourceRef.Value ignored ->
                    out.append(", ").append(valueLocal).append(", null, null");
                case AdaptSourceRef.SharedCell cell ->
                    // The adapter holds the creation-site incarnation cell by
                    // identity: the enclosing factory's capture parameter when
                    // the creating body captured the binding (the cell travels
                    // the chain), otherwise the class-scoped cell field of the
                    // creation-site incarnation — never a module-global slot
                    // read for a captured binding (R7(b)).
                    out.append(", null, (Object[]) ")
                        .append(cellSource(cell.binding(), cell.generation()))
                        .append(", null");
                case AdaptSourceRef.Thunk thunk ->
                    out.append(", null, null, __t -> ")
                        .append(thunkFn(op.opId())).append("()");
            }
            out.append(", ").append(payload.sourceSignature().paramTypes().size())
                .append(", ")
                .append(javaString(payload.sourceSignature().canonicalSpecText()))
                .append(");\n");
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
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
         * creation's own value local ({@code valueLocal}) exactly when the op
         * records that operand (the oracle atomizes its operand list); the
         * SHARED_CELL and REEVALUATE_THUNK modes carry zero operand atoms
         * (their operands are empty by construction).
         */
        private void emitAdaptStart(SemanticOp op,
                KindPayload.FunctionAdaptPayload payload, String valueLocal,
                int indent) {
            if (!trace) {
                return;
            }
            StringBuilder inputs = new StringBuilder();
            if (valueLocal != null && !op.operands().isEmpty()) {
                inputs.append("JvmRuntime.atom(").append(valueLocal).append(", ")
                    .append(javaString(staticKind(payload.sourceSignature())))
                    .append(")");
            }
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(op.opId()))).append(", \"START\", ")
                .append(javaString(op.kind().name())).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId())))
                .append(", List.of(").append(inputs).append("), null, null);\n");
        }

        /**
         * RECURSIVE_GROUP_INIT (E8, atomic publication): phase 1
         * allocates every member's SHARED_CELL (a fresh one-element
         * array per execution); phase 2 allocates every member identity
         * into a local — the per-member factory invocation over the
         * member's capture cells; phase 3 assigns the member function
         * objects to the group fields in declaration order in one
         * ordered sequence. The publication writes into the
         * already-allocated cell arrays (never a replacement — a
         * sibling's capture holds the cell array by identity), and no
         * member observes a partially initialized group: no member body
         * runs at group execution and every identity is allocated before
         * the first publication.
         */
        private void emitRecursiveGroupInit(SemanticOp op, int indent) {
            KindPayload.RecursiveGroupInitPayload payload =
                (KindPayload.RecursiveGroupInitPayload) op.payload();
            emitStart(op, indent);
            for (BindingId binding : payload.bindings()) {
                out.append(indent(indent)).append(cell(binding, 0))
                    .append(" = new Object[1];\n");
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
                out.append(indent(indent)).append("JvmRuntime.FunctionValue ")
                    .append(groupTemp(op, i)).append(" = ")
                    .append(fnFactory(functionId)).append("(").append(args)
                    .append(");\n");
            }
            for (int i = 0; i < payload.bindings().size(); i++) {
                out.append(indent(indent)).append("((Object[]) ")
                    .append(cell(payload.bindings().get(i), 0)).append(")[0] = ")
                    .append(groupTemp(op, i)).append(";\n");
            }
            emitPlainSuccess(op, indent);
        }

        /** One member identity local of the group op (per-op unique). */
        private String groupTemp(SemanticOp op, int member) {
            return "__gv" + op.opId().id() + "_" + member;
        }

        private void emitAssign(SemanticOp op, int indent) {
            KindPayload.AssignPayload payload = (KindPayload.AssignPayload) op.payload();
            emitStart(op, indent);
            out.append(indent(indent)).append("try {\n");
            for (OpId childId : payload.childOps()) {
                SemanticOp child = opsById.get(childId);
                emitChainOperandProducers(child, indent + 1);
                if (child.kind() == SemanticOpKind.BOUNDARY) {
                    emitChainBoundary(child,
                        (KindPayload.BoundaryPayload) child.payload(), op, indent + 1);
                } else {
                    emitOp(child, indent + 1);
                }
            }
            // The chain's published value lives in the child-local scope
            // the chain declared (a chain child's result slot is a local
            // inside this try block); the ASSIGN's own SUCCESS atom must
            // read it here, before the block closes and the name falls
            // back to the class-field slot the chain never wrote.
            emitResultSuccess(op, slot((ValueId) op.result()),
                producerResultType(op.result()), indent + 1);
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __e) {\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "JvmRuntime.errtext(__e)",
                indent + 1);
            out.append(indent(indent)).append("  throw __e;\n");
            out.append(indent(indent)).append("}\n");
        }

        private void emitDelete(SemanticOp op, int indent) {
            KindPayload.DeletePayload payload = (KindPayload.DeletePayload) op.payload();
            emitStart(op, indent);
            out.append(indent(indent)).append("try {\n");
            for (OpId childId : payload.childOps()) {
                SemanticOp child = opsById.get(childId);
                emitChainOperandProducers(child, indent + 1);
                if (child.kind() == SemanticOpKind.BOUNDARY) {
                    emitChainBoundary(child,
                        (KindPayload.BoundaryPayload) child.payload(), op, indent + 1);
                } else {
                    emitOp(child, indent + 1);
                }
            }
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __e) {\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "JvmRuntime.errtext(__e)",
                indent + 1);
            out.append(indent(indent)).append("  throw __e;\n");
            out.append(indent(indent)).append("}\n");
            emitPlainSuccess(op, indent);
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
        private void emitChainOperandProducers(SemanticOp child, int indent) {
            for (SemanticOp producer : ChainOperandCompletion.operandProducersOf(
                    child, unitOwning(child), structuralOwned)) {
                emitOp(producer, indent);
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

        /**
         * A chain boundary child: the bounds check runs only from this
         * boundary's projection with the chain-supplied {index, length}
         * context (A-D8 — never a target-side re-check that can raise
         * twice).
         */
        private void emitChainBoundary(SemanticOp boundary,
                                       KindPayload.BoundaryPayload payload,
                                       SemanticOp chain, int indent) {
            String input = slot(payload.input());
            switch (payload.kind()) {
                case BYTE_ELEMENT_ASSIGNMENT -> {
                    // K6 item 4: the E8012 bounds cell (index-expression
                    // origin), then the element descriptor check; the
                    // commit owns the E8013 range check.
                    out.append(indent(indent)).append("JvmRuntime.bytesBounds(")
                        .append(javaString(opKey(boundary.opId()))).append(", ")
                        .append(javaString(boundary.contract().canonicalDigest()))
                        .append(", ")
                        .append(javaString(parentKey(boundary.origin().parentOpId())))
                        .append(", ").append(input).append(", ((Long) ((Object[]) ")
                        .append(chainSlotExpr(chain)).append(")[1]).longValue(), ((Long) ")
                        .append("((Object[]) ").append(chainSlotExpr(chain))
                        .append(")[2]).longValue(), ")
                        .append(javaString(descriptorText(payload.descriptor())))
                        .append(", ").append(javaString(staticKind(payload.descriptor())))
                        .append(", ").append(javaString(originOf(boundary)))
                        .append(");\n");
                }
                case ARRAY_ELEMENT_ASSIGNMENT, ARRAY_ELEMENT_DELETE -> {
                    // JvmRuntime.arrayBounds emits the boundary START/terminal
                    // events (the bounds check runs only from this projection);
                    // the delete boundary's input operand is the normalized
                    // slot (the closed slot atom, never a value atom).
                    if (payload.kind() == BoundaryKind.ARRAY_ELEMENT_DELETE
                            && slotEqualsChainSlot(payload.input(), chain)) {
                        out.append(indent(indent))
                            .append("JvmRuntime.arrayBoundsSlot(")
                            .append(javaString(opKey(boundary.opId()))).append(", ")
                            .append(javaString(boundary.contract().canonicalDigest()))
                            .append(", ")
                            .append(javaString(parentKey(boundary.origin().parentOpId())))
                            .append(", (long[]) ").append(chainSlotExpr(chain))
                            .append(", JvmRuntime.indexOf(").append(chainLengthExpr(chain))
                            .append("), ")
                            .append(javaString(originOf(boundary))).append(");\n");
                    } else {
                    out.append(indent(indent)).append("JvmRuntime.arrayBounds(")
                        .append(javaString(opKey(boundary.opId()))).append(", ")
                        .append(javaString(boundary.contract().canonicalDigest()))
                        .append(", ")
                        .append(javaString(parentKey(boundary.origin().parentOpId())))
                        .append(", ").append(input).append(", ((long[]) ")
                        .append(chainSlotExpr(chain)).append(")[0], JvmRuntime.indexOf(")
                        .append(chainLengthExpr(chain)).append("), ")
                        .append(javaString(descriptorText(payload.descriptor())))
                        .append(", ")
                        .append(javaString(staticKind(payload.descriptor())))
                        .append(", ")
                        .append(payload.kind() == BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT
                            ? "true" : "false").append(", ")
                        .append(javaString(originOf(boundary))).append(");\n");
                    }
                }
                default -> {
                    emitBoundaryStart(boundary, input, payload.descriptor(), indent);
                    out.append(indent(indent)).append("Object __cb_")
                        .append(boundary.opId().id())
                        .append(" = ")
                        .append(bcheckArgs(payload.descriptor(), input))
                        .append(";\n");
                    emitBoundarySuccess(boundary, "__cb_" + boundary.opId().id(),
                        payload.descriptor(), indent);
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
            return "null";
        }

        private String chainLengthExpr(SemanticOp chain) {
            for (OpId childId : chainChildOps(chain)) {
                SemanticOp child = opsById.get(childId);
                if (child.kind() == SemanticOpKind.ARRAY_LENGTH
                        && child.result() instanceof ValueId valueId) {
                    return slot(valueId);
                }
            }
            return "null";
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

        private void emitCall(SemanticOp op, int indent) {
            KindPayload.CallPayload payload = (KindPayload.CallPayload) op.payload();
            FunctionExecutionBinding binding = callBinding(payload);
            emitStart(op, indent);

            if (binding instanceof FunctionExecutionBinding.HostFunction host) {
                emitHostCall(op, payload, host.hostModuleId(), host.exportName(), indent);
                return;
            }
            if (binding instanceof FunctionExecutionBinding.HostFunctionValue hostValue) {
                emitHostValueCall(op, payload, hostValue, indent);
                return;
            }

            if (binding instanceof FunctionExecutionBinding.IntrinsicFunction intrinsic) {
                emitIntrinsicValueCall(op, payload, intrinsic, indent);
                return;
            }
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                SemanticOp boundary = opsById.get(boundaryId);
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                emitBoundaryStart(boundary, slot(boundaryPayload.input()),
                    boundaryPayload.descriptor(), indent);
                // The cell runs under the recorded call origin: a failing
                // declared parameter reports the callee's parameter
                // declaration (the pinned corpus span), and the boundary
                // and the CALL each publish their own FAILURE terminal
                // (the oracle's exact sequence).
                String cell = "__pb_" + boundary.opId().id();
                out.append(indent(indent)).append("Object ").append(cell)
                    .append(";\n");
                out.append(indent(indent)).append("try {\n");
                out.append(indent(indent)).append("  ").append(cell).append(" = ")
                    .append(bcheckArgs(boundaryPayload.descriptor(),
                        slot(boundaryPayload.input())))
                    .append(";\n");
                out.append(indent(indent))
                    .append("} catch (JvmRuntime.DealError __be) {\n");
                out.append(indent(indent))
                    .append("  JvmRuntime.DealError __bre = new JvmRuntime.DealError("
                        + "__be.code, __be.msg, ")
                    .append(javaString(originOf(boundary)))
                    .append(", __be.expected, __be.actual, __be.frames, null);\n");
                emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                    "JvmRuntime.errtext(__bre)", indent + 1);
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "JvmRuntime.errtext(__bre)", indent + 1);
                out.append(indent(indent)).append("  throw __bre;\n");
                out.append(indent(indent)).append("}\n");
                emitBoundarySuccess(boundary, cell, boundaryPayload.descriptor(),
                    indent);
            }
            if (payload.callee() instanceof KindPayload.CallCallee.Dynamic dynamic) {

                emitDynamicCall(op, payload, dynamic, indent);
                emitResultSuccess(op, slot((ValueId) op.result()),
                    (RuntimeDescriptor) op.resultType(), indent);
                return;
            }
            switch (binding) {
                case FunctionExecutionBinding.LoweredBody body -> {
                    FunctionId callee = body.functionId();
                    StateSlot savedState = emitInvocationStateSave(callee, op.opId(),
                        indent);
                    // The invocation's result lands in a method-local: the
                    // callee's own private state is restored by the finally
                    // below, and a recursive call shares the result slot's
                    // name (the call site's slot belongs to the callee's
                    // body too) — the assignment therefore happens after
                    // the restore.
                    String resultLocal = "__res_" + op.opId().id();
                    out.append(indent(indent)).append("Object ").append(resultLocal)
                        .append(" = null;\n");
                    out.append(indent(indent)).append("try {\n");
                    out.append(indent(indent + 1)).append("JvmRuntime.pushFrame(")
                        .append(javaString(String.valueOf(callee.id()))).append(");\n");
                    // The invoking call's origin: a failure inside the body
                    // whose arm renders the call origin (the @jsonable toJson
                    // walk) reads the innermost active call.
                    out.append(indent(indent + 1))
                        .append("JvmRuntime.callOrigins.push(")
                        .append(javaString(originOf(op))).append(");\n");
                    out.append(indent(indent + 1)).append("try {\n");
                    out.append(indent(indent + 1)).append("  ")
                        .append(resultLocal)
                        .append(" = ");
                    if (payload.callee()
                            instanceof KindPayload.CallCallee.Indirect indirect) {
                        // The value-carried invocation (a body with
                        // creation-site captures): the closure value the
                        // binding holds runs its own invoker, whose
                        // captured cells are the ones its creation
                        // published — never a call-site re-resolution of
                        // a per-iteration incarnation.
                        out.append("((JvmRuntime.FunctionValue) ")
                            .append(slot(indirect.callee()))
                            .append(").fn.invoke(new Object[]{");
                    } else {
                        out.append(fnFactory(callee)).append("(");
                        deal.semantic.ir.LoweredFunction calleeFunction =
                            functionOf(callee);
                        List<deal.semantic.ir.BindingGeneration> captures =
                            calleeFunction == null ? List.of()
                                : calleeFunction.captures();
                        for (int i = 0; i < captures.size(); i++) {
                            if (i > 0) {
                                out.append(", ");
                            }
                            out.append(captureArg(captures.get(i)));
                        }
                        out.append(").fn.invoke(new Object[]{");
                    }
                    for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                        if (i > 0) {
                            out.append(", ");
                        }
                        SemanticOp boundary =
                            opsById.get(payload.parameterBoundaryOpIds().get(i));
                        out.append(slot(((KindPayload.BoundaryPayload) boundary.payload())
                            .input()));
                    }
                    out.append("});\n");
                    out.append(indent(indent + 1))
                        .append("} catch (JvmRuntime.DealError __e) {\n");
                    emitFailureEvent(op.opId(), op.kind().name(), op,
                        "JvmRuntime.errtext(__e)", indent + 2);
                    out.append(indent(indent + 1)).append("  throw __e;\n");
                    out.append(indent(indent + 1)).append("} finally {\n");
                    out.append(indent(indent + 1))
                        .append("  JvmRuntime.callOrigins.pop();\n");
                    out.append(indent(indent + 1)).append("  JvmRuntime.popFrame();\n");
                    out.append(indent(indent + 1)).append("}\n");
                    out.append(indent(indent)).append("} finally {\n");
                    emitInvocationStateRestore(callee, savedState, indent + 1);
                    out.append(indent(indent)).append("}\n");
                    out.append(indent(indent)).append(slot((ValueId) op.result()))
                        .append(" = ").append(resultLocal).append(";\n");
                }
                case FunctionExecutionBinding.AdapterBinding adapter -> {

                    if (adapterIntrinsicKind(adapter) != null) {
                        emitAdapterOverIntrinsicRun(op, payload, adapter, indent);
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

                    String adapterSuffix = String.valueOf(op.opId().id());
                    boolean switchAdapterModule =
                        adapter.captureMode() != deal.semantic.ir.CaptureMode
                            .REEVALUATE_THUNK;
                    if (switchAdapterModule) {
                        out.append(indent(indent)).append("Object __as")
                            .append(adapterSuffix).append(" = JvmRuntime.adapterSource("
                                + "(JvmRuntime.AdapterValue) ")
                            .append(adapterSlot).append(");\n");
                        out.append(indent(indent)).append("String __am")
                            .append(adapterSuffix).append(" = (__as")
                            .append(adapterSuffix)
                            .append(" instanceof JvmRuntime.FunctionValue __fv")
                            .append(adapterSuffix).append(" && __fv")
                            .append(adapterSuffix)
                            .append(".fid != null) ? dealModuleOfFunction(__fv")
                            .append(adapterSuffix).append(".fid) : null;\n");
                        out.append(indent(indent)).append("String __pm")
                            .append(adapterSuffix).append(" = MODULE;\n");
                        out.append(indent(indent)).append("String __pr")
                            .append(adapterSuffix)
                            .append(" = JvmRuntime.currentModule();\n");
                        out.append(indent(indent)).append("if (__am")
                            .append(adapterSuffix).append(" != null) { MODULE = __am")
                            .append(adapterSuffix)
                            .append("; JvmRuntime.setModule(MODULE); }\n");
                    }
                    out.append(indent(indent)).append("try {\n");
                    out.append(indent(indent)).append("  ")
                        .append(slot((ValueId) op.result()))
                        .append(" = JvmRuntime.invokeAdapter((JvmRuntime.AdapterValue) ")
                        .append(adapterSlot).append(", ")
                        .append(javaString(originOf(op))).append(", new Object[]{")
                        .append(args).append("});\n");
                    out.append(indent(indent)).append("} catch (JvmRuntime.DealError __e) {\n");
                    emitFailureEvent(op.opId(), op.kind().name(), op,
                        "JvmRuntime.errtext(__e)", indent + 1);
                    out.append(indent(indent)).append("  throw __e;\n");
                    if (switchAdapterModule) {
                        out.append(indent(indent)).append("} finally {\n");
                        out.append(indent(indent)).append("  MODULE = __pm")
                            .append(adapterSuffix).append(";\n");
                        out.append(indent(indent)).append("  JvmRuntime.setModule(__pr")
                            .append(adapterSuffix).append(");\n");
                        out.append(indent(indent)).append("}\n");
                    } else {
                        out.append(indent(indent)).append("}\n");
                    }
                }
                case FunctionExecutionBinding.ExternalFunction external ->
                    emitExternalCall(op, payload, external, indent);
                default -> throw new IllegalStateException("CALL " + op.opId()
                    + " resolves a binding outside the statically-resolved slice: "
                    + binding);
            }
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitExternalCall(SemanticOp op, KindPayload.CallPayload payload,
                FunctionExecutionBinding.ExternalFunction external, int indent) {
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
            String prevMod = "__xmm_" + op.opId().id();
            String prevRun = "__xmr_" + op.opId().id();
            StringBuilder caps = new StringBuilder();
            for (BindingGeneration capture : calleeFunction.captures()) {
                if (caps.length() > 0) {
                    caps.append(", ");
                }
                caps.append(captureArg(capture));
            }
            StringBuilder args = new StringBuilder();
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                if (args.length() > 0) {
                    args.append(", ");
                }
                args.append(slot(((KindPayload.BoundaryPayload)
                    opsById.get(boundaryId).payload()).input()));
            }
            // The saved module context lives in the enclosing block: the
            // try/finally's finally and the caller-level catch below both
            // restore from it (a try block's locals are not in the scope
            // of its own finally), and the caller's CALL FAILURE must
            // carry the caller's module (the oracle's own tagging) — the
            // entry's own terminals carry the callee's.
            out.append(indent(indent)).append("String ").append(prevMod)
                .append(" = MODULE;\n");
            out.append(indent(indent)).append("String ").append(prevRun)
                .append(" = JvmRuntime.currentModule();\n");
            // The invocation's result lands in a method-local: the
            // callee's own private state is restored by the state finally
            // below, and a recursive call shares the result slot's name
            // (the call site's slot belongs to the callee's body too) —
            // the assignment therefore happens after the restore.
            String resultLocal = "__res_" + op.opId().id();
            out.append(indent(indent)).append("Object ").append(resultLocal)
                .append(" = null;\n");
            StateSlot savedState = emitInvocationStateSave(entryPayload.function(),
                op.opId(), indent);
            out.append(indent(indent)).append("try {\n");
            out.append(indent(indent + 1)).append("try {\n");
            out.append(indent(indent + 2)).append("MODULE = ")
                .append(javaString(calleePath)).append(";\n");
            out.append(indent(indent + 2)).append("JvmRuntime.setModule(MODULE);\n");
            if (trace) {
                out.append(indent(indent + 2)).append("JvmRuntime.ev(MODULE, ")
                    .append(javaString(opKey(entry.opId())))
                    .append(", \"START\", \"EXTERNAL_ENTRY\", ")
                    .append(javaString(entry.contract().canonicalDigest()))
                    .append(", ").append(javaString(opKey(op.opId())))
                    .append(", List.of(), null, null);\n");
            }
            out.append(indent(indent + 2)).append("JvmRuntime.pushFrame(")
                .append(javaString(String.valueOf(entryPayload.function().id())))
                .append(");\n");
            out.append(indent(indent + 2)).append("try {\n");
            out.append(indent(indent + 3)).append("try {\n");
            // The invoking call's origin: a failure inside the entry body
            // whose arm renders the call origin (the @jsonable toJson walk)
            // reads the innermost active call.
            out.append(indent(indent + 3)).append("  JvmRuntime.callOrigins.push(")
                .append(javaString(originOf(op))).append(");\n");
            out.append(indent(indent + 3)).append("  try {\n");
            out.append(indent(indent + 4)).append("  ").append(resultLocal)
                .append(" = ").append(fnFactory(entryPayload.function()))
                .append("(").append(caps).append(").fn.invoke(new Object[]{")
                .append(args).append("});\n");
            out.append(indent(indent + 3)).append("  } finally {\n");
            out.append(indent(indent + 3))
                .append("    JvmRuntime.callOrigins.pop();\n");
            out.append(indent(indent + 3)).append("  }\n");
            out.append(indent(indent + 3))
                .append("} catch (JvmRuntime.DealError __ex) {\n");
            if (trace) {
                out.append(indent(indent + 4)).append("JvmRuntime.ev(MODULE, ")
                    .append(javaString(opKey(entry.opId())))
                    .append(", \"FAILURE\", \"EXTERNAL_ENTRY\", ")
                    .append(javaString(entry.contract().canonicalDigest()))
                    .append(", ").append(javaString(opKey(op.opId())))
                    .append(", List.of(), null, JvmRuntime.errtext(__ex));\n");
            }
            out.append(indent(indent + 4)).append("throw __ex;\n");
            out.append(indent(indent + 3)).append("}\n");
            if (trace) {
                out.append(indent(indent + 3)).append("JvmRuntime.ev(MODULE, ")
                    .append(javaString(opKey(entry.opId())))
                    .append(", \"SUCCESS\", \"EXTERNAL_ENTRY\", ")
                    .append(javaString(entry.contract().canonicalDigest()))
                    .append(", ").append(javaString(opKey(op.opId())))
                    .append(", List.of(), JvmRuntime.atom(")
                    .append(resultLocal).append(", ")
                    .append(javaString(staticKind(
                        entryPayload.signature().returnType())))
                    .append("), null);\n");
            }
            out.append(indent(indent + 2)).append("} finally {\n");
            out.append(indent(indent + 3)).append("JvmRuntime.popFrame();\n");
            out.append(indent(indent + 2)).append("}\n");
            out.append(indent(indent + 1)).append("} finally {\n");
            out.append(indent(indent + 2)).append("MODULE = ").append(prevMod)
                .append(";\n");
            out.append(indent(indent + 2)).append("JvmRuntime.setModule(")
                .append(prevRun).append(");\n");
            out.append(indent(indent + 1)).append("}\n");
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __e) {\n");
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(__e)", indent + 1);
            out.append(indent(indent + 1)).append("throw __e;\n");
            out.append(indent(indent)).append("} finally {\n");
            emitInvocationStateRestore(entryPayload.function(), savedState, indent + 1);
            out.append(indent(indent)).append("}\n");
            out.append(indent(indent)).append(slot((ValueId) op.result()))
                .append(" = ").append(resultLocal).append(";\n");
        }

        /**
         * The synchronous invocation's private-state save, or {@code null}
         * when the callee body has no private state. The saved array is
         * built <em>unconditionally</em> and its matching restore
         * ({@link #emitInvocationStateRestore}) copies it back on every
         * path: the enclosing invocation may have been started by a route
         * this call site cannot name at compile time (a dynamic dispatch of
         * an escaped carrier, an entry of another unit), so the active
         * marker cannot gate the save — the copied values are the enclosing
         * invocation's private state whatever started it. The marker itself
         * is still maintained, so a re-entrant invocation leaves it in place
         * and only the outermost clears it.
         */
        private StateSlot emitInvocationStateSave(FunctionId callee, OpId invocation,
                                                  int indent) {
            List<String> keys = bodyStateKeys(callee);
            if (keys.isEmpty()) {
                return null;
            }
            String previous = "__pa_" + invocation.id();
            String saved = "__sv_" + invocation.id();
            out.append(indent(indent)).append("boolean ").append(previous)
                .append(" = __bodyActive.contains(").append(callee.id())
                .append("L);\n");
            out.append(indent(indent)).append("Object[] ").append(saved)
                .append(" = new Object[]{").append(String.join(", ", keys))
                .append("};\n");
            out.append(indent(indent)).append("__bodyActive.add(")
                .append(callee.id()).append("L);\n");
            return new StateSlot(previous, saved);
        }

        /**
         * The matching unconditional restore, executed on every path: the
         * body's own slots and cell references return to the enclosing
         * invocation's state whichever route started it. A re-entrant
         * invocation leaves the enclosing invocation's marker in place; only
         * the outermost one clears it.
         */
        private void emitInvocationStateRestore(FunctionId callee, StateSlot slot,
                                                int indent) {
            if (slot == null) {
                return;
            }
            List<String> keys = bodyStateKeys(callee);
            for (int i = 0; i < keys.size(); i++) {
                out.append(indent(indent)).append(keys.get(i)).append(" = ")
                    .append(slot.saved()).append("[").append(i).append("];\n");
            }
            out.append(indent(indent)).append("if (!").append(slot.previous())
                .append(") {\n");
            out.append(indent(indent + 1)).append("__bodyActive.remove(")
                .append(callee.id()).append("L);\n");
            out.append(indent(indent)).append("}\n");
        }

        /**
         * The async body-task invocation's private-state save. The frame's
         * saved array is built <em>unconditionally</em> and its matching
         * restore ({@link #emitAsyncInvocationStateRestore}) copies it back
         * on every path, exactly like the synchronous arm's: the task's body
         * executes at the enclosing invocation's serial drain, and that
         * enclosing invocation may have been started by a callee this arm
         * cannot name at compile time (a dynamic dispatch body task) or by
         * an entry of another unit, so the active marker cannot gate the
         * save. The marker is still maintained, so a re-entrant invocation
         * leaves it in place and only the outermost clears it.
         */
        private StateSlot emitAsyncInvocationStateSave(FunctionId callee,
                                                       OpId invocation, int indent) {
            List<String> keys = bodyStateKeys(callee);
            if (keys.isEmpty()) {
                return null;
            }
            String previous = "__pa_" + invocation.id();
            String saved = "__sv_" + invocation.id();
            out.append(indent(indent)).append("boolean ").append(previous)
                .append(" = __bodyActive.contains(").append(callee.id())
                .append("L);\n");
            out.append(indent(indent)).append("Object[] ").append(saved)
                .append(" = new Object[]{").append(String.join(", ", keys))
                .append("};\n");
            out.append(indent(indent)).append("__bodyActive.add(")
                .append(callee.id()).append("L);\n");
            return new StateSlot(previous, saved);
        }

        /** The matching unconditional restore, executed on every path. */
        private void emitAsyncInvocationStateRestore(FunctionId callee, StateSlot slot,
                                                     int indent) {
            if (slot == null) {
                return;
            }
            List<String> keys = bodyStateKeys(callee);
            for (int i = 0; i < keys.size(); i++) {
                out.append(indent(indent)).append(keys.get(i)).append(" = ")
                    .append(slot.saved()).append("[").append(i).append("];\n");
            }
            // A re-entrant invocation leaves the enclosing invocation's
            // marker in place; only the outermost one clears it.
            out.append(indent(indent)).append("if (!").append(slot.previous())
                .append(") {\n");
            out.append(indent(indent + 1)).append("__bodyActive.remove(")
                .append(callee.id()).append("L);\n");
            out.append(indent(indent)).append("}\n");
        }

        /** One invocation's re-entrant state save: its flag and its saved array. */
        private record StateSlot(String previous, String saved) {
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
                    new deal.semantic.ir.FunctionAllocationIdentity(identity.id()));
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

        private void emitDynamicCall(SemanticOp op, KindPayload.CallPayload payload,
                                     KindPayload.CallCallee.Dynamic callee, int indent) {
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
            String id = String.valueOf(op.opId().id());
            String carrier = "__dc" + id;
            String result = "__dr" + id;
            String cls = "__dk" + id;

            SemanticOp recordedDealCell = opsById.get(cells.dealBodyBoundaryOpId());
            boolean callOwnedDealCell = recordedDealCell != null
                && callOwnedCell(recordedDealCell);
            StringBuilder args = new StringBuilder();
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                if (args.length() > 0) {
                    args.append(", ");
                }
                args.append(slot(((KindPayload.BoundaryPayload)
                    opsById.get(boundaryId).payload()).input()));
            }
            String argList = args.toString();
            out.append(indent(indent)).append("Object ").append(result)
                .append(" = null;\n");
            out.append(indent(indent)).append("Object ").append(carrier)
                .append(" = ").append(slot(callee.callee())).append(";\n");
            out.append(indent(indent)).append("String ").append(cls).append(";\n");
            out.append(indent(indent)).append("if (").append(carrier)
                .append(" instanceof JvmRuntime.AdapterValue) {\n");
            out.append(indent(indent + 1)).append(cls).append(" = \"ADAPTER\";\n");
            out.append(indent(indent)).append("} else if (").append(carrier)
                .append(" instanceof JvmRuntime.FunctionValue __fv").append(id)
                .append(" && __fv").append(id).append(".fid != null) {\n");
            out.append(indent(indent + 1)).append(cls).append(" = \"DEAL_BODY\";\n");
            out.append(indent(indent)).append("} else if (").append(carrier)
                .append(" instanceof JvmRuntime.FunctionValue) {\n");
            out.append(indent(indent + 1)).append(cls).append(" = \"HOST\";\n");
            out.append(indent(indent)).append("} else {\n");
            emitDynamicCarrierFailure(op, carrier, indent + 1);
            out.append(indent(indent)).append("}\n");
            // DEAL_BODY: the carrier's own invoker under the callee's
            // module; the frame and the module context are restored on
            // every path.
            out.append(indent(indent)).append("if (\"DEAL_BODY\".equals(")
                .append(cls).append(")) {\n");
            emitDynamicBodyInvoke(op, carrier, result,
                "new Object[]{ " + argList + " }", id, indent + 1);
            if (callOwnedDealCell) {
                emitRecordedCellRun(op, recordedDealCell, result, "__cr" + id,
                    indent + 1);
            }

            out.append(indent(indent)).append("} else if (\"ADAPTER\".equals(")
                .append(cls).append(")) {\n");
            out.append(indent(indent + 1)).append("JvmRuntime.AdapterValue __ad")
                .append(id).append(" = (JvmRuntime.AdapterValue) ").append(carrier)
                .append(";\n");
            out.append(indent(indent + 1)).append("Object __src").append(id)
                .append(" = JvmRuntime.fnCheck(JvmRuntime.adapterSource(__ad")
                .append(id).append("), __ad").append(id).append(".sourceSpec, ")
                .append(javaString(originOf(op))).append(");\n");
            out.append(indent(indent + 1)).append("if (__src").append(id)
                .append(" instanceof JvmRuntime.Intrinsic __ii").append(id)
                .append(") {\n");

            if (payload.parameterBoundaryOpIds().isEmpty()) {
                // The leading-M projection of the runtime intrinsic source has
                // no argument to convert (the intrinsic declares one
                // parameter): the carrier fails closed at the call origin.
                emitDynamicCarrierFailure(op, carrier, indent + 2);
            } else {
                String dynamicArg = slot(((KindPayload.BoundaryPayload)
                    opsById.get(payload.parameterBoundaryOpIds().get(0)).payload())
                    .input());
                String converted = "__di" + id;
                out.append(indent(indent + 2)).append("Object ").append(converted)
                    .append(";\n");
                out.append(indent(indent + 2)).append("if (\"INT_CONVERT\".equals("
                    + "__ii").append(id).append(".kind)) {\n");
                out.append(indent(indent + 3)).append(converted).append(" = ")
                    .append(intrinsicHelper(IntrinsicKind.INT_CONVERT)).append("(")
                    .append(dynamicArg).append(", ")
                    .append(intrinsicInvocationArgs(op, IntrinsicKind.INT_CONVERT))
                    .append(");\n");
                out.append(indent(indent + 2)).append("} else if (\"NUMBER_CONVERT\""
                    + ".equals(__ii").append(id).append(".kind)) {\n");
                out.append(indent(indent + 3)).append(converted).append(" = ")
                    .append(intrinsicHelper(IntrinsicKind.NUMBER_CONVERT)).append("(")
                    .append(dynamicArg).append(", ")
                    .append(intrinsicInvocationArgs(op, IntrinsicKind.NUMBER_CONVERT))
                    .append(");\n");
                out.append(indent(indent + 2)).append("} else {\n");
                out.append(indent(indent + 3)).append(converted).append(" = ")
                    .append(intrinsicHelper(IntrinsicKind.BYTES_NEW)).append("(")
                    .append(dynamicArg).append(", ")
                    .append(intrinsicInvocationArgs(op, IntrinsicKind.BYTES_NEW))
                    .append(");\n");
                out.append(indent(indent + 2)).append("}\n");
                String dynamicAdmitted = emitHostCellRun(op, hostCell, converted,
                    indent + 2);
                out.append(indent(indent + 2)).append(result).append(" = ")
                    .append(dynamicAdmitted).append(";\n");
            }
            out.append(indent(indent + 1)).append("} else if (__src").append(id)
                .append(" instanceof JvmRuntime.FunctionValue) {\n");
            emitDynamicBodyInvoke(op, "__src" + id, result,
                "java.util.Arrays.copyOfRange(new Object[]{ " + argList
                    + " }, 0, __ad" + id + ".arity)", id, indent + 2);
            if (callOwnedDealCell) {
                emitRecordedCellRun(op, recordedDealCell, result, "__cr" + id,
                    indent + 2);
            }
            out.append(indent(indent + 1)).append("} else {\n");
            emitDynamicCarrierFailure(op, "__src" + id, indent + 2);
            out.append(indent(indent + 1)).append("}\n");
            // HOST: the loaded surface entry carrier and the recorded
            // HOST_TO_DEAL + HOST_SYNC_RETURN cell.
            out.append(indent(indent)).append("} else {\n");
            emitDynamicHostRow(op, payload, hostCell, carrier, result, id, indent + 1);
            out.append(indent(indent)).append("}\n");
            out.append(indent(indent)).append(slot((ValueId) op.result())).append(" = ")
                .append(result).append(";\n");
        }

        /**
         * One dynamically dispatched DEAL-body invocation: the carrier's
         * function id resolves its owning module through the emitted
         * lookup, the frame is pushed, the module context is switched,
         * and the carrier's own invoker runs; the invocation's private
         * state is restored and the op's FAILURE terminal carries the
         * caller's module when the callee fails.
         */
        private void emitDynamicBodyInvoke(SemanticOp op, String source, String result,
                                           String invokeArgs, String id, int indent) {
            String fid = "__fy" + id;
            out.append(indent(indent)).append("JvmRuntime.FunctionValue ").append(fid)
                .append(" = (JvmRuntime.FunctionValue) ").append(source).append(";\n");
            out.append(indent(indent)).append("if (").append(fid)
                .append(".fid == null) {\n");
            emitDynamicCarrierFailure(op, source, indent + 1);
            out.append(indent(indent)).append("}\n");
            out.append(indent(indent)).append("String __fm").append(id)
                .append(" = dealModuleOfFunction(").append(fid).append(".fid);\n");
            out.append(indent(indent)).append("if (__fm").append(id)
                .append(" == null) {\n");
            emitDynamicCarrierFailure(op, source, indent + 1);
            out.append(indent(indent)).append("}\n");
            out.append(indent(indent)).append("String __pm").append(id)
                .append(" = MODULE;\n");
            out.append(indent(indent)).append("String __pr").append(id)
                .append(" = JvmRuntime.currentModule();\n");
            out.append(indent(indent)).append("MODULE = __fm").append(id).append(";\n");
            out.append(indent(indent)).append("JvmRuntime.setModule(MODULE);\n");
            out.append(indent(indent)).append("JvmRuntime.pushFrame(").append(fid)
                .append(".fid);\n");
            // The invoking dynamic call's origin: a failure inside the
            // callee body whose arm renders the call origin (the
            // @jsonable toJson walk) reads the innermost active call —
            // the f(w) invocation, never an enclosing static call. The
            // finally below pops it on every path.
            out.append(indent(indent)).append("JvmRuntime.callOrigins.push(")
                .append(javaString(originOf(op))).append(");\n");
            out.append(indent(indent)).append("JvmRuntime.DealError __fd").append(id)
                .append(" = null;\n");
            out.append(indent(indent)).append("try {\n");
            out.append(indent(indent + 1)).append(result).append(" = ")
                .append(fid).append(".fn.invoke(").append(invokeArgs)
                .append(");\n");
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __e")
                .append(id).append(") {\n");
            out.append(indent(indent + 1)).append("__fd").append(id)
                .append(" = __e").append(id).append(";\n");
            out.append(indent(indent)).append("} finally {\n");
            out.append(indent(indent + 1)).append("JvmRuntime.callOrigins.pop();\n");
            out.append(indent(indent + 1)).append("JvmRuntime.popFrame();\n");
            out.append(indent(indent + 1)).append("MODULE = __pm").append(id)
                .append(";\n");
            out.append(indent(indent + 1)).append("JvmRuntime.setModule(__pr")
                .append(id).append(");\n");
            out.append(indent(indent)).append("}\n");
            out.append(indent(indent)).append("if (__fd").append(id)
                .append(" != null) {\n");
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(__fd" + id + ")", indent + 1);
            out.append(indent(indent + 1)).append("throw __fd").append(id)
                .append(";\n");
            out.append(indent(indent)).append("}\n");
        }

        private void emitDynamicHostRow(SemanticOp op, KindPayload.CallPayload payload,
                                        SemanticOp returnBoundary, String carrier,
                                        String result, String id, int indent) {
            StringBuilder args = new StringBuilder();
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                if (args.length() > 0) {
                    args.append(", ");
                }
                args.append(slot(((KindPayload.BoundaryPayload)
                    opsById.get(boundaryId).payload()).input()));
            }
            String hostValue = "__dh" + id;
            out.append(indent(indent)).append("Object ").append(hostValue).append(";\n");

            if (payload.parameterBoundaryOpIds().size() == 1) {
                out.append(indent(indent)).append("if (").append(carrier)
                    .append(" instanceof JvmRuntime.Intrinsic __iv").append(id)
                    .append(") {\n");
                out.append(indent(indent + 1)).append("if (\"INT_CONVERT\".equals(__iv")
                    .append(id).append(".kind)) {\n");
                out.append(indent(indent + 2)).append(hostValue).append(" = ")
                    .append(intrinsicHelper(IntrinsicKind.INT_CONVERT)).append("(")
                    .append(args).append(", ")
                    .append(intrinsicInvocationArgs(op, IntrinsicKind.INT_CONVERT))
                    .append(");\n");
                out.append(indent(indent + 1)).append("} else if (\"NUMBER_CONVERT\""
                    + ".equals(__iv").append(id).append(".kind)) {\n");
                out.append(indent(indent + 2)).append(hostValue).append(" = ")
                    .append(intrinsicHelper(IntrinsicKind.NUMBER_CONVERT)).append("(")
                    .append(args).append(", ")
                    .append(intrinsicInvocationArgs(op, IntrinsicKind.NUMBER_CONVERT))
                    .append(");\n");
                out.append(indent(indent + 1)).append("} else {\n");
                out.append(indent(indent + 2)).append(hostValue).append(" = ")
                    .append(intrinsicHelper(IntrinsicKind.BYTES_NEW)).append("(")
                    .append(args).append(", ")
                    .append(intrinsicInvocationArgs(op, IntrinsicKind.BYTES_NEW))
                    .append(");\n");
                out.append(indent(indent + 1)).append("}\n");
                out.append(indent(indent)).append("} else if (").append(carrier)
                    .append(" instanceof JvmRuntime.StdlibFunctionValue) {\n");
            } else {
                out.append(indent(indent)).append("if (").append(carrier)
                    .append(" instanceof JvmRuntime.Intrinsic) {\n");
                emitDynamicCarrierFailure(op, carrier, indent + 1);
                out.append(indent(indent)).append("} else if (").append(carrier)
                    .append(" instanceof JvmRuntime.StdlibFunctionValue) {\n");
            }
            out.append(indent(indent + 1)).append(hostValue)
                .append(" = JvmRuntime.invokeStdlibCallable((JvmRuntime."
                    + "StdlibFunctionValue) ")
                .append(carrier).append(", ").append(javaString(op.kind().name()))
                .append(", ").append(javaString(opKey(op.opId()))).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                .append(javaString(originOf(op))).append(", new Object[]{ ")
                .append(args).append(" });\n");
            out.append(indent(indent)).append("} else {\n");
            out.append(indent(indent + 1)).append(hostValue)
                .append(" = ((JvmRuntime.FunctionValue) ").append(carrier)
                .append(").fn.invoke(new Object[]{ ").append(args).append(" });\n");
            out.append(indent(indent)).append("}\n");
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) returnBoundary.payload();
            String checked = "__dhc" + id;
            emitBoundaryStart(returnBoundary, hostValue, boundaryPayload.descriptor(),
                indent);
            out.append(indent(indent)).append("Object ").append(checked).append(";\n");
            out.append(indent(indent)).append("try {\n");
            out.append(indent(indent + 1)).append(checked)
                .append(" = ")
                .append(bcheckArgs(boundaryPayload.descriptor(), hostValue))
                .append(";\n");
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __be) {\n");
            // The recorded cell's own origin replaces the check engine's
            // neutral one, exactly the Lua arm's `__chkB.o = <cell origin>`:
            // the pinned failure framing of a cataloged callee names the
            // call expression (K2/K15), and the oracle's recorded-cell run
            // carries the identical origin.
            out.append(indent(indent + 1))
                .append("JvmRuntime.DealError __bre = new JvmRuntime.DealError("
                    + "__be.code, __be.msg, ")
                .append(javaString(originOf(returnBoundary)))
                .append(", __be.expected, __be.actual, __be.frames, null);\n");
            emitFailureEvent(returnBoundary.opId(), "BOUNDARY", returnBoundary,
                "JvmRuntime.errtext(__bre)", indent + 1);
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(__bre)", indent + 1);
            out.append(indent(indent + 1)).append("throw __bre;\n");
            out.append(indent(indent)).append("}\n");
            emitBoundarySuccess(returnBoundary, checked, boundaryPayload.descriptor(),
                indent);
            out.append(indent(indent)).append(result).append(" = ").append(checked)
                .append(";\n");
        }

        private void emitRecordedCellRun(SemanticOp invocation, SemanticOp cell,
                                         String valueExpr, String checkedName, int indent) {
            emitFieldBoundaryCheck(invocation, cell, valueExpr, checkedName, indent);
            out.append(indent(indent)).append(valueExpr).append(" = ")
                .append(checkedName).append(";\n");
        }

        /**
         * The dynamic dispatch's fail-closed residue: a carrier whose
         * class is not resolvable at this boundary projects the pinned
         * E8001 {@code expected function} text with the carrier's actual
         * runtime kind at the call origin, emits the op's single FAILURE
         * terminal, and throws — nothing executes silently.
         */
        private void emitDynamicCarrierFailure(SemanticOp op, String carrierExpr,
                                               int indent) {
            String error = "__dy_" + op.opId().id();
            out.append(indent(indent)).append("JvmRuntime.DealError ").append(error)
                .append(" = JvmRuntime.arm("
                    + "deal.semantic.ir.FailureArmId.TYPED_BOUNDARY_KIND,"
                    + " java.util.Map.of(\"kind\", \"function\"), ")
                .append(javaString(originOf(op)))
                .append(", \"function\", JvmRuntime.actualOf(\"function\", ")
                .append(carrierExpr).append("));\n");
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(" + error + ")", indent);
            out.append(indent(indent)).append("throw ").append(error).append(";\n");
        }

        private void emitDynamicAsyncStart(SemanticOp op,
                                           KindPayload.AsyncStartPayload payload,
                                           KindPayload.CallCallee.Dynamic callee,
                                           AsyncTokenId token, List<String> args,
                                           int indent) {
            String id = String.valueOf(op.opId().id());
            String carrier = "__dc" + id;

            SemanticOp recordedTaskCell = payload.returnBoundaryOpId() == null
                ? null : opsById.get(payload.returnBoundaryOpId());
            boolean callOwnedTaskCell = recordedTaskCell != null
                && callOwnedCell(recordedTaskCell);
            out.append(indent(indent)).append("Object ").append(carrier)
                .append(" = ").append(slot(callee.callee())).append(";\n");

            out.append(indent(indent)).append("if (").append(carrier)
                .append(" instanceof JvmRuntime.AdapterValue) {\n");
            String adapter = "__da" + id;
            String source = "__ds" + id;
            String sourceModule = "__dm" + id;
            out.append(indent(indent + 1)).append("JvmRuntime.AdapterValue ")
                .append(adapter).append(" = (JvmRuntime.AdapterValue) ")
                .append(carrier).append(";\n");
            out.append(indent(indent + 1)).append("Object ").append(source)
                .append(" = JvmRuntime.fnCheck(JvmRuntime.adapterSource(")
                .append(adapter).append("), ").append(adapter)
                .append(".sourceSpec, ").append(javaString(originOf(op)))
                .append(");\n");

            out.append(indent(indent + 1)).append("if (").append(source)
                .append(" instanceof JvmRuntime.Intrinsic __ii").append(id)
                .append(") {\n");
            if (args.isEmpty()) {
                // The leading-M projection of the runtime intrinsic source has
                // no argument to convert (the intrinsic declares one
                // parameter): the carrier fails closed at the start origin.
                emitDynamicCarrierFailure(op, source, indent + 2);
            } else {
                out.append(indent(indent + 2)).append("JvmRuntime.startBodyTask(")
                    .append(token.tokenId()).append(", \"DEAL_BODY_TASK\", () -> ")
                    .append("\"INT_CONVERT\".equals(__ii").append(id)
                    .append(".kind) ? JvmRuntime.intConv(").append(args.get(0))
                    .append(", ").append(javaString(op.kind().name()))
                    .append(", \"number\", ").append(intrinsicContextArgs(op))
                    .append(") : JvmRuntime.numConv(").append(args.get(0)).append(", ")
                    .append(javaString(op.kind().name())).append(", \"int\", ")
                    .append(intrinsicContextArgs(op)).append("));\n");
            }
            out.append(indent(indent + 1)).append("} else {\n");
            out.append(indent(indent + 1)).append("if (!(").append(source)
                .append(" instanceof JvmRuntime.FunctionValue) || "
                    + "((JvmRuntime.FunctionValue) ").append(source)
                .append(").fid == null) {\n");
            emitDynamicCarrierFailure(op, source, indent + 2);
            out.append(indent(indent + 1)).append("}\n");
            out.append(indent(indent + 1)).append("String ").append(sourceModule)
                .append(" = dealModuleOfFunction(((JvmRuntime.FunctionValue) ")
                .append(source).append(").fid);\n");
            out.append(indent(indent + 1)).append("if (").append(sourceModule)
                .append(" == null) {\n");
            emitDynamicCarrierFailure(op, source, indent + 2);
            out.append(indent(indent + 1)).append("}\n");
            out.append(indent(indent + 1)).append("JvmRuntime.startBodyTask(")
                .append(token.tokenId()).append(", \"DEAL_BODY_TASK\", () -> {\n");
            out.append(indent(indent + 2)).append("String __prevM = "
                + "JvmRuntime.currentModule();\n");
            out.append(indent(indent + 2)).append("MODULE = ").append(sourceModule)
                .append(";\n");
            out.append(indent(indent + 2)).append("JvmRuntime.setModule(MODULE);\n");
            out.append(indent(indent + 2)).append("JvmRuntime.pushFrame("
                + "((JvmRuntime.FunctionValue) ").append(source).append(").fid);\n");
            out.append(indent(indent + 2)).append("try {\n");
            if (callOwnedTaskCell) {
                out.append(indent(indent + 3)).append("Object __dv").append(id)
                    .append(" = ")
                    .append("((JvmRuntime.FunctionValue) ").append(source)
                    .append(").fn.invoke(java.util.Arrays.copyOfRange(new Object[]{ ")
                    .append(String.join(", ", args)).append(" }, 0, ")
                    .append(adapter).append(".arity));\n");
                emitRecordedCellRun(op, recordedTaskCell, "__dv" + id, "__vc" + id,
                    indent + 3);
                out.append(indent(indent + 3)).append("return __dv").append(id)
                    .append(";\n");
            } else {
                out.append(indent(indent + 3)).append("return "
                    + "((JvmRuntime.FunctionValue) ").append(source)
                    .append(").fn.invoke(java.util.Arrays.copyOfRange(new Object[]{ ")
                    .append(String.join(", ", args)).append(" }, 0, ")
                    .append(adapter).append(".arity));\n");
            }
            out.append(indent(indent + 2)).append("} finally {\n");
            out.append(indent(indent + 3)).append("JvmRuntime.popFrame();\n");
            out.append(indent(indent + 3)).append("MODULE = __prevM;\n");
            out.append(indent(indent + 3)).append("JvmRuntime.setModule(__prevM);\n");
            out.append(indent(indent + 2)).append("}\n");
            out.append(indent(indent + 1)).append("});\n");
            out.append(indent(indent + 1)).append("}\n");
            // DEAL_BODY: the carrier's own invoker under the callee's
            // module; the frame and the module context are restored on
            // every path.
            out.append(indent(indent)).append("} else if (").append(carrier)
                .append(" instanceof JvmRuntime.FunctionValue ")
                .append("__fv").append(id).append(" && __fv").append(id)
                .append(".fid != null) {\n");
            String functionValue = "__fv" + id;
            out.append(indent(indent + 1)).append("String __fm").append(id)
                .append(" = dealModuleOfFunction(").append(functionValue)
                .append(".fid);\n");
            out.append(indent(indent + 1)).append("if (__fm").append(id)
                .append(" == null) {\n");
            emitDynamicCarrierFailure(op, carrier, indent + 2);
            out.append(indent(indent + 1)).append("}\n");
            out.append(indent(indent + 1)).append("JvmRuntime.startBodyTask(")
                .append(token.tokenId()).append(", \"DEAL_BODY_TASK\", () -> {\n");
            out.append(indent(indent + 2)).append("String __prevM = "
                + "JvmRuntime.currentModule();\n");
            out.append(indent(indent + 2)).append("MODULE = __fm").append(id)
                .append(";\n");
            out.append(indent(indent + 2)).append("JvmRuntime.setModule(MODULE);\n");
            out.append(indent(indent + 2)).append("JvmRuntime.pushFrame(")
                .append(functionValue).append(".fid);\n");
            out.append(indent(indent + 2)).append("try {\n");
            if (callOwnedTaskCell) {
                out.append(indent(indent + 3)).append("Object __dv").append(id)
                    .append(" = ").append(functionValue)
                    .append(".fn.invoke(new Object[]{ ").append(String.join(", ", args))
                    .append(" });\n");
                emitRecordedCellRun(op, recordedTaskCell, "__dv" + id, "__vc" + id,
                    indent + 3);
                out.append(indent(indent + 3)).append("return __dv").append(id)
                    .append(";\n");
            } else {
                out.append(indent(indent + 3)).append("return ")
                    .append(functionValue)
                    .append(".fn.invoke(new Object[]{ ")
                    .append(String.join(", ", args)).append(" });\n");
            }
            out.append(indent(indent + 2)).append("} finally {\n");
            out.append(indent(indent + 3)).append("JvmRuntime.popFrame();\n");
            out.append(indent(indent + 3)).append("MODULE = __prevM;\n");
            out.append(indent(indent + 3)).append("JvmRuntime.setModule(__prevM);\n");
            out.append(indent(indent + 2)).append("}\n");
            out.append(indent(indent + 1)).append("});\n");
            // HOST: the carrier is a loaded host surface entry, so the declared
            // host export's async start runs through the same host calling
            // convention and the same ASYNC_OPERATION_HANDLE terminal as the
            // static arm — the loaded wrapper's declared-async shape check
            // inside the emitted wrapper, the operation handle bound to this
            // token under the declared identity's operation label (the same
            // label the oracle's dynamic HOST resolution derives from the
            // binding). The declared identity is the carrier's own
            // identity-indexed home in the program's export-surface registry; a
            // carrier with no unique home (never a loaded surface entry) fails
            // closed.
            out.append(indent(indent)).append("} else if (").append(carrier)
                .append(" instanceof JvmRuntime.FunctionValue __hv").append(id)
                .append(" && __hv").append(id).append(".fid == null")
                .append(" && !(").append(carrier)
                .append(" instanceof JvmRuntime.StdlibFunctionValue)) {\n");
            String hostName = "__hn" + id;
            String hostLabel = "__hl" + id;
            String hostHandle = "__hh" + id;
            out.append(indent(indent + 1)).append("String[] ").append(hostName)
                .append(" = JvmRuntime.surfaceNameOf(").append(carrier).append(");\n");
            out.append(indent(indent + 1)).append("if (").append(hostName)
                .append(" == null) {\n");
            emitDynamicCarrierFailure(op, carrier, indent + 2);
            out.append(indent(indent + 1)).append("}\n");
            out.append(indent(indent + 1)).append("String ").append(hostLabel)
                .append(" = ").append(hostName).append("[0] + \".\" + ")
                .append(hostName).append("[1];\n");
            out.append(indent(indent + 1))
                .append("JvmRuntime.effect(\"ASYNC_START_OP\", ").append(hostLabel)
                .append(");\n");
            out.append(indent(indent + 1)).append("Object ").append(hostHandle)
                .append(";\n");
            out.append(indent(indent + 1)).append("try {\n");
            out.append(indent(indent + 2)).append(hostHandle).append(" = ")
                .append("((JvmRuntime.FunctionValue) ").append(carrier)
                .append(").fn.invoke(new Object[]{ ").append(String.join(", ", args))
                .append(" });\n");
            out.append(indent(indent + 1)).append("} catch (JvmRuntime.DealError __e) {\n");
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(__e)", indent + 2);
            out.append(indent(indent + 2)).append("throw __e;\n");
            out.append(indent(indent + 1)).append("}\n");
            out.append(indent(indent + 1)).append("JvmRuntime.startHostTask(")
                .append(token.tokenId()).append(", ").append(hostLabel).append(", ")
                .append(hostHandle).append(");\n");
            // The fail-closed residue: no class is resolvable.
            out.append(indent(indent)).append("} else {\n");
            emitDynamicCarrierFailure(op, carrier, indent + 1);
            out.append(indent(indent)).append("}\n");
        }

        private void emitHostCall(SemanticOp op, KindPayload.CallPayload payload,
                ModuleId hostModuleId, String exportName, int indent) {

            StdlibFunctionCatalog.Entry catalogRow = StdlibFunctionCatalog
                .lookup(hostModuleId.path(), exportName).orElse(null);
            if (catalogRow != null) {
                emitStdlibCalleeCall(op, payload, catalogRow, indent);
                return;
            }
            if (hostAbi == null) {
                throw new IllegalStateException("CALL " + op.opId()
                    + " resolves the host export '" + hostModuleId.path() + "."
                    + exportName + "' but the session carries no host ABI"
                    + " emission surface (a producer defect; only the"
                    + " production project entry carries the compile's"
                    + " declaration surface)");
            }
            String wrapper = hostAbi.requireWrapper(hostModuleId.path(), exportName);
            Type.Func declared = hostAbi.declaredFunction(hostModuleId.path(),
                exportName);
            HostCallArguments arguments = emitHostArguments(op, payload, declared, indent);
            StringBuilder call = new StringBuilder(wrapper).append('(');
            for (String projected : arguments.projected()) {
                call.append(projected).append(", ");
            }
            call.append(originArgs(op)).append(')');
            finishHostCall(op, payload, declared, arguments, call.toString(), true,
                indent);
        }

        private void emitStdlibCalleeCall(SemanticOp op, KindPayload.CallPayload payload,
                StdlibFunctionCatalog.Entry row, int indent) {
            List<String> checked = new ArrayList<>();
            int index = 1;
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                SemanticOp boundary = opsById.get(boundaryId);
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                RuntimeDescriptor descriptor = boundaryPayload.descriptor();
                if (descriptor instanceof RuntimeDescriptor.Nullable
                        || descriptor instanceof RuntimeDescriptor.Class
                        || descriptor instanceof RuntimeDescriptor.Func) {
                    throw new IllegalStateException("the cataloged callable '"
                        + row.declaredDescriptor().canonicalSpecText() + "' arrives at "
                        + op.opId() + " with the parameter position "
                        + descriptor.canonicalSpecText() + ", whose host-facing "
                        + "projection is not the identity — a producer defect");
                }
                String input = slot(boundaryPayload.input());
                emitBoundaryStart(boundary, input, descriptor, indent);
                String name = "__sp_" + boundary.opId().id();
                out.append(indent(indent)).append("Object ").append(name).append(";\n");
                out.append(indent(indent)).append("try {\n");
                out.append(indent(indent + 1)).append(name)
                    .append(" = __hostParamCheck(").append(index).append(", ")
                    .append(javaString(descriptorText(descriptor))).append(", ")
                    .append(input).append(", ").append(originArgs(op)).append(");\n");
                out.append(indent(indent)).append("} catch (JvmRuntime.DealError __be) {\n");
                emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                    "JvmRuntime.errtext(__be)", indent + 1);
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "JvmRuntime.errtext(__be)", indent + 1);
                out.append(indent(indent + 1)).append("throw __be;\n");
                out.append(indent(indent)).append("}\n");
                emitBoundarySuccess(boundary, name, descriptor, indent);
                checked.add(name);
                index++;
            }
            String result = "__sc_" + op.opId().id();
            out.append(indent(indent)).append("Object ").append(result)
                .append(" = JvmRuntime.invokeStdlibCallable(JvmRuntime.stdlibCallable(")
                .append(stdlibRowArgs(row)).append("), ")
                .append(javaString(op.kind().name())).append(", ")
                .append(javaString(opKey(op.opId()))).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                .append(javaString(originOf(op))).append(", new Object[]{ ")
                .append(String.join(", ", checked)).append(" });\n");
            SemanticOp returnBoundary = payload.returnBoundaryOpId() == null ? null
                : opsById.get(payload.returnBoundaryOpId());
            if (returnBoundary != null) {
                // The cataloged result's terminal is the descriptor-kind
                // projection of the direct STDLIB_RETURN boundary (the int
                // ladder's pinned E8004 included), never the recorded host
                // cell's HOST_SYNC_RETURN row.
                String admitted = "__sr_" + op.opId().id();
                emitBoundaryAdmission(op, returnBoundary, result, admitted,
                    "__be", "__bre", indent);
                result = admitted;
            }
            out.append(indent(indent)).append(slot((ValueId) op.result())).append(" = ")
                .append(result).append(";\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitIntrinsicValueCall(SemanticOp op, KindPayload.CallPayload payload,
                FunctionExecutionBinding.IntrinsicFunction intrinsic, int indent) {
            List<String> checked = new ArrayList<>();
            int index = 1;
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                SemanticOp boundary = opsById.get(boundaryId);
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                RuntimeDescriptor descriptor = boundaryPayload.descriptor();
                if (descriptor instanceof RuntimeDescriptor.Nullable
                        || descriptor instanceof RuntimeDescriptor.Class
                        || descriptor instanceof RuntimeDescriptor.Func) {
                    throw new IllegalStateException("the conversion intrinsic '"
                        + intrinsic.kind() + "' arrives at " + op.opId()
                        + " with the parameter position "
                        + descriptor.canonicalSpecText() + ", whose host-facing "
                        + "projection is not the identity — a producer defect");
                }
                String input = slot(boundaryPayload.input());
                emitBoundaryStart(boundary, input, descriptor, indent);
                String name = "__ip_" + boundary.opId().id();
                out.append(indent(indent)).append("Object ").append(name).append(";\n");
                out.append(indent(indent)).append("try {\n");
                out.append(indent(indent + 1)).append(name)
                    .append(" = __hostParamCheck(").append(index).append(", ")
                    .append(javaString(descriptorText(descriptor))).append(", ")
                    .append(input).append(", ").append(originArgs(op)).append(");\n");
                out.append(indent(indent)).append("} catch (JvmRuntime.DealError __be) {\n");
                emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                    "JvmRuntime.errtext(__be)", indent + 1);
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "JvmRuntime.errtext(__be)", indent + 1);
                out.append(indent(indent + 1)).append("throw __be;\n");
                out.append(indent(indent)).append("}\n");
                emitBoundarySuccess(boundary, name, descriptor, indent);
                checked.add(name);
                index++;
            }
            if (checked.size() != intrinsic.descriptor().paramTypes().size()) {
                throw new IllegalStateException("the conversion intrinsic call "
                    + op.opId() + " records " + checked.size() + " parameter cell(s) for "
                    + "the '" + intrinsic.kind() + "' intrinsic's "
                    + intrinsic.descriptor().paramTypes().size() + " declared "
                    + "parameter(s) (a producer defect)");
            }
            // The one conversion ladder at the call site: the invoking CALL op's
            // context, origin, and kind label (the pinned conversion texts and
            // the FAILURE event carry the invoking op).
            String result = "__ic_" + op.opId().id();
            String helper = intrinsicHelper(intrinsic.kind());
            out.append(indent(indent)).append("Object ").append(result).append(" = ")
                .append(helper).append("(")
                .append(String.join(", ", checked)).append(", ")
                .append(intrinsicInvocationArgs(op, intrinsic.kind())).append(");\n");
            // The recorded HOST_TO_DEAL + HOST_SYNC_RETURN cell, run by the call
            // op exactly once per invocation.
            SemanticOp returnBoundary = payload.returnBoundaryOpId() == null ? null
                : opsById.get(payload.returnBoundaryOpId());
            if (returnBoundary != null) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) returnBoundary.payload();
                emitBoundaryStart(returnBoundary, result, boundaryPayload.descriptor(),
                    indent);
                String admitted = "__iac_" + op.opId().id();
                out.append(indent(indent)).append("Object ").append(admitted)
                    .append(";\n");
                out.append(indent(indent)).append("try {\n");
                out.append(indent(indent + 1)).append(admitted).append(" = ")
                    .append(hostCellCheck(boundaryPayload.descriptor(), result, op))
                    .append(";\n");
                out.append(indent(indent)).append("} catch (JvmRuntime.DealError __be) {\n");
                emitFailureEvent(returnBoundary.opId(), "BOUNDARY", returnBoundary,
                    "JvmRuntime.errtext(__be)", indent + 1);
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "JvmRuntime.errtext(__be)", indent + 1);
                out.append(indent(indent + 1)).append("throw __be;\n");
                out.append(indent(indent)).append("}\n");
                emitBoundarySuccess(returnBoundary, admitted,
                    boundaryPayload.descriptor(), indent);
                result = admitted;
            }
            out.append(indent(indent)).append(slot((ValueId) op.result())).append(" = ")
                .append(result).append(";\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
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
                case INT_CONVERT -> "JvmRuntime.intConv";
                case NUMBER_CONVERT -> "JvmRuntime.numConv";
                case BYTES_NEW -> "JvmRuntime.bytesNew";
            };
        }

        /**
         * The trailing argument list of one intrinsic invocation: the
         * invoking op's kind label, the declared parameter's static kind
         * for a conversion, and the op key, the contract digest, the
         * parent key, and the origin. The invocation's declared signature
         * is the only descriptor source (never a call site's or an adapter
         * target's).
         */
        private String intrinsicInvocationArgs(SemanticOp op, IntrinsicKind kind) {
            StringBuilder args = new StringBuilder();
            args.append(javaString(op.kind().name())).append(", ");
            if (kind != IntrinsicKind.BYTES_NEW) {
                args.append(javaString(staticKind(
                    kind.declaredSignature().paramTypes().get(0)))).append(", ");
            }
            args.append(javaString(opKey(op.opId()))).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                .append(javaString(originOf(op)));
            return args.toString();
        }

        private void emitAdapterOverIntrinsicRun(SemanticOp op,
                KindPayload.CallPayload payload,
                FunctionExecutionBinding.AdapterBinding adapter, int indent) {
            IntrinsicKind kind = adapterIntrinsicKind(adapter);
            if (kind == null) {
                throw new IllegalStateException("CALL " + op.opId()
                    + " records an adapter whose source is not the seeded intrinsic "
                    + "identity (producer defect)");
            }
            RuntimeDescriptor.Func declared = kind.declaredSignature();
            String adapterSlot = slot((ValueId) opsById.get(adapter.adaptOpId()).result());

            String source = "__ais" + op.opId().id();
            out.append(indent(indent)).append("try {\n");
            out.append(indent(indent + 1)).append("Object ").append(source)
                .append(" = JvmRuntime.fnCheck(JvmRuntime.adapterSource("
                    + "(JvmRuntime.AdapterValue) ")
                .append(adapterSlot).append("), ")
                .append(javaString(adapter.sourceSignature().canonicalSpecText()))
                .append(", ").append(javaString(originOf(op))).append(");\n");
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __e) {\n");
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(__e)", indent + 1);
            out.append(indent(indent + 1)).append("throw __e;\n");
            out.append(indent(indent)).append("}\n");
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
            // The one conversion/allocation ladder at the adapter call site:
            // the invoking CALL op's context, origin, and kind label.
            String helper = intrinsicHelper(kind);
            String converted = "__aiv" + op.opId().id();
            out.append(indent(indent)).append("Object ").append(converted)
                .append(" = ").append(helper).append("(").append(input).append(", ")
                .append(intrinsicInvocationArgs(op, kind)).append(");\n");
            // The recorded HOST_TO_DEAL + HOST_SYNC_RETURN cell, run by the
            // call op exactly once per invocation (the source class HOST).
            SemanticOp returnBoundary = payload.returnBoundaryOpId() == null ? null
                : opsById.get(payload.returnBoundaryOpId());
            String admitted = emitHostCellRun(op, returnBoundary, converted, indent);
            out.append(indent(indent)).append(slot((ValueId) op.result())).append(" = ")
                .append(admitted).append(";\n");
        }

        /**
         * The recorded host return cell's check expression: the production
         * typed-boundary check ({@code JvmRuntime.bcheck}) for the bytes
         * carrier — the bytes value the allocation ladder publishes is the
         * runtime's own carrier, never the emitted host ABI's
         * {@code $DealRt.Bytes} — and the emitted host check for every
         * other descriptor (whose declared texts and policy stay the
         * recorded cell's).
         */
        private String hostCellCheck(RuntimeDescriptor descriptor, String valueExpr,
                                     SemanticOp op) {
            if (descriptor instanceof RuntimeDescriptor.Bytes) {
                return bcheckArgs(descriptor, valueExpr);
            }
            return "__hostCheck(" + javaString(descriptorText(descriptor)) + ", "
                + valueExpr + ", false, " + originArgs(op) + ")";
        }

        private String emitHostCellRun(SemanticOp op, SemanticOp returnBoundary,
                                       String valueExpr, int indent) {
            if (returnBoundary == null) {
                return valueExpr;
            }
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) returnBoundary.payload();
            emitBoundaryStart(returnBoundary, valueExpr, boundaryPayload.descriptor(),
                indent);
            String admitted = "__iacc_" + returnBoundary.opId().id();
            out.append(indent(indent)).append("Object ").append(admitted)
                .append(";\n");
            out.append(indent(indent)).append("try {\n");
            out.append(indent(indent + 1)).append(admitted).append(" = ")
                .append(hostCellCheck(boundaryPayload.descriptor(), valueExpr, op))
                .append(";\n");
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __be) {\n");
            emitFailureEvent(returnBoundary.opId(), "BOUNDARY", returnBoundary,
                "JvmRuntime.errtext(__be)", indent + 1);
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(__be)", indent + 1);
            out.append(indent(indent + 1)).append("throw __be;\n");
            out.append(indent(indent)).append("}\n");
            emitBoundarySuccess(returnBoundary, admitted,
                boundaryPayload.descriptor(), indent);
            return admitted;
        }

        /**
         * The {@code HostFunctionValue} indirect call arm: the value
         * materialized at its producing host crossing ({@code
         * host-module-load-and-host-call-realization} H3/H5/H7) is
         * invoked through the production function carrier the crossing's
         * declared-return projection published, with the same declared
         * parameter cells and the pinned E8010 texts at the call origin.
         */
        private void emitHostValueCall(SemanticOp op, KindPayload.CallPayload payload,
                FunctionExecutionBinding.HostFunctionValue hostValue, int indent) {
            if (hostAbi == null) {
                throw new IllegalStateException("CALL " + op.opId()
                    + " resolves the host-materialized function value of module '"
                    + hostValue.hostModuleId().path() + "' but the session carries"
                    + " no host ABI emission surface (a producer defect)");
            }
            Type.Func declared = hostAbi.declaredFunctionValue(
                hostValue.descriptor().canonicalSpecText());
            SemanticOp crossing = opsById.get(hostValue.materializingBoundaryOpId());
            if (crossing == null
                    || !(crossing.payload() instanceof KindPayload.BoundaryPayload boundary)) {
                throw new IllegalStateException("CALL " + op.opId()
                    + " names the materializing host crossing "
                    + hostValue.materializingBoundaryOpId()
                    + ", which is not a boundary op of the emitted closure"
                    + " (a producer defect)");
            }
            String value = slot(boundary.input());
            HostCallArguments arguments = emitHostArguments(op, payload, declared, indent);
            StringBuilder call = new StringBuilder("((JvmRuntime.FunctionValue) ")
                .append(value).append(").fn.invoke(new java.lang.Object[]{ ");
            for (int i = 0; i < arguments.projected().size(); i++) {
                if (i > 0) {
                    call.append(", ");
                }
                call.append(arguments.projected().get(i));
            }
            call.append(" })");
            finishHostCall(op, payload, declared, arguments, call.toString(), false,
                indent);
        }

        /** The projected argument names, array carriers, and array parameters of one host call. */
        private record HostCallArguments(List<String> projected, List<String> carriers,
            List<String> arrays, List<String> arrayCarriers) {
        }

        /**
         * The declared parameter cells of one host call: one-based order,
         * each cell through the emitted host-check seam with the pinned
         * E8010 projection at the call origin, then the host-facing
         * crossing projection (H7).
         */
        private HostCallArguments emitHostArguments(SemanticOp op,
                KindPayload.CallPayload payload, Type.Func declared, int indent) {
            List<String> projected = new ArrayList<>();
            List<String> carriers = new ArrayList<>();
            List<String> arrays = new ArrayList<>();
            List<String> arrayCarriers = new ArrayList<>();
            int index = 1;
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                SemanticOp boundary = opsById.get(boundaryId);
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                String input = slot(boundaryPayload.input());
                Type declaredParameter = index - 1 < declared.paramTypes().size()
                    ? declared.paramTypes().get(index - 1) : null;
                String desc = declaredParameter == null
                    ? boundaryPayload.descriptor().canonicalSpecText()
                    : JvmHostAbiEmission.descriptorText(declaredParameter);
                emitBoundaryStart(boundary, input, boundaryPayload.descriptor(), indent);
                String checked = "__hp_" + boundary.opId().id();
                out.append(indent(indent)).append("Object ").append(checked)
                    .append(";\n");
                out.append(indent(indent)).append("try {\n");
                out.append(indent(indent)).append("  ").append(checked)
                    .append(" = __hostParamCheck(").append(index).append(", ")
                    .append(javaString(desc)).append(", ").append(input)
                    .append(", ").append(originArgs(op)).append(");\n");
                out.append(indent(indent))
                    .append("} catch (JvmRuntime.DealError __be) {\n");
                emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                    "JvmRuntime.errtext(__be)", indent + 1);
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "JvmRuntime.errtext(__be)", indent + 1);
                out.append(indent(indent)).append("  throw __be;\n");
                out.append(indent(indent)).append("}\n");
                emitBoundarySuccess(boundary, checked, boundaryPayload.descriptor(),
                    indent);
                String value = "__hq_" + boundary.opId().id();
                out.append(indent(indent)).append("Object ").append(value)
                    .append(" = __hostProjectArg(").append(javaString(desc))
                    .append(", ").append(checked).append(");\n");
                projected.add(value);
                if (declaredParameter != null
                        && innerOf(declaredParameter) instanceof Type.Array) {
                    // The copy-back target is the production array the
                    // crossing materialized the carrier from, and the source
                    // is the projected carrier the host method received.
                    arrays.add(checked);
                    carriers.add(desc);
                    arrayCarriers.add(value);
                }
                index++;
            }
            return new HostCallArguments(List.copyOf(projected), List.copyOf(carriers),
                List.copyOf(arrays), List.copyOf(arrayCarriers));
        }

        /**
         * The call half of one host arm: the invocation under the op's
         * FAILURE events, the declared return value's production
         * projection, the normal-return copy-back of the declared array
         * parameters, and the return boundary's events.
         */
        private void finishHostCall(SemanticOp op, KindPayload.CallPayload payload,
                Type.Func declared, HostCallArguments arguments, String invocation,
                boolean targetChecksReturn, int indent) {
            Type declaredReturn = declared.returnType();
            String result = "__hr_" + op.opId().id();
            String call = targetChecksReturn ? invocation
                : "__hostCheck("
                    + javaString(JvmHostAbiEmission.descriptorText(declaredReturn))
                    + ", " + invocation + ", false, " + originArgs(op) + ")";
            if (declaredReturn instanceof Type.Null) {
                out.append(indent(indent)).append("try {\n");
                out.append(indent(indent)).append("  ").append(call).append(";\n");
                emitHostCallCatch(op, payload, indent);
                out.append(indent(indent)).append("Object ").append(result)
                    .append(" = null;\n");
            } else {
                // The direct host arm assigns the emitted wrapper's
                // declared return carrier; the HostFunctionValue arm
                // assigns the emitted host-check seam's java.lang.Object
                // result (the wrapper is not involved), so the held slot
                // is Object there and the checked value is reconciled
                // with the declared production carrier after the seam.
                String carrier = targetChecksReturn
                        && !hostAbi.needsReturnProjection(declaredReturn)
                    ? hostAbi.carrierType(declaredReturn,
                        declaredReturn instanceof Type.Nullable)
                    : "java.lang.Object";
                String held = "__hw_" + op.opId().id();
                out.append(indent(indent)).append(carrier).append(" ").append(held)
                    .append(";\n");
                out.append(indent(indent)).append("try {\n");
                out.append(indent(indent)).append("  ").append(held).append(" = ")
                    .append(call).append(";\n");
                emitHostCallCatch(op, payload, indent);
                out.append(indent(indent)).append("Object ").append(result)
                    .append(" = ").append(targetChecksReturn
                        ? productionValueOf(declaredReturn, held)
                        : checkedResultOf(declaredReturn, held))
                    .append(";\n");
            }
            for (int i = 0; i < arguments.arrays().size(); i++) {
                out.append(indent(indent)).append("__hostArrayCopyBack(")
                    .append(javaString(arguments.carriers().get(i))).append(", ")
                    .append(arguments.arrayCarriers().get(i))
                    .append(", (JvmRuntime.Array) ")
                    .append(arguments.arrays().get(i)).append(");\n");
            }
            SemanticOp returnBoundary = payload.returnBoundaryOpId() == null ? null
                : opsById.get(payload.returnBoundaryOpId());
            if (returnBoundary != null) {
                // The return boundary's event pair runs for every declared
                // return position, a declared null included (the oracle's
                // return cell emits the same pair over the language null),
                // so the differential comparison stays event-for-event.
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) returnBoundary.payload();
                emitBoundaryStart(returnBoundary, result,
                    boundaryPayload.descriptor(), indent);
                emitBoundarySuccess(returnBoundary, result,
                    boundaryPayload.descriptor(), indent);
            }
            out.append(indent(indent)).append(slot((ValueId) op.result())).append(" = ")
                .append(result).append(";\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        /**
         * The catch half of one host call: the op FAILURE (and, for the
         * wrapper's declared-return cell, the return boundary's FAILURE)
         * with the wrapper's own error, then the identical rethrow — a
         * DEAL error the host raises propagates unchanged.
         */
        private void emitHostCallCatch(SemanticOp op, KindPayload.CallPayload payload,
                int indent) {
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __e) {\n");
            SemanticOp returnBoundary = payload.returnBoundaryOpId() == null ? null
                : opsById.get(payload.returnBoundaryOpId());
            if (returnBoundary != null) {
                out.append(indent(indent)).append("  if (\"E8010\".equals(__e.code)"
                    + " && __e.msg != null && __e.msg.startsWith(\"return value 1"
                    + " type mismatch\")) {\n");
                emitFailureEvent(returnBoundary.opId(), "BOUNDARY", returnBoundary,
                    "JvmRuntime.errtext(__e)", indent + 2);
                out.append(indent(indent)).append("  }\n");
            }
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(__e)", indent + 1);
            out.append(indent(indent)).append("  throw __e;\n");
            out.append(indent(indent)).append("}\n");
        }

        /** The declared inner type of one position (nullable unwrapped). */
        private static Type innerOf(Type type) {
            return type instanceof Type.Nullable nullable ? nullable.inner() : type;
        }

        /**
         * The production projection of one checked host return held as
         * the host-check seam's {@code java.lang.Object} result (the
         * {@code HostFunctionValue} indirect and callback arms; H3/H7):
         * the declared carrier is reconciled with the production value
         * carrier — a numeric or boolean position unboxes through its
         * boxed form (a {@code null}-safe form for a {@code ?} position),
         * a declared array or function return crosses back into the
         * production carriers through the host-to-DEAL projection, and
         * every other reference carrier is the production value itself.
         */

        /** The origin-argument triplet of a wrapper invocation. */
        private String originArgs(SemanticOp op) {
            SourceSpan span = op.origin().span();
            if (span == null) {
                return "\"-\", 0, 0";
            }
            return javaString(op.origin().sourceId()) + ", " + span.startLine()
                + ", " + span.startColumn();
        }

        private String intrinsicContextArgs(SemanticOp op) {
            return javaString(opKey(op.opId())) + ", "
                + javaString(op.contract().canonicalDigest()) + ", "
                + javaString(parentKey(op.origin().parentOpId())) + ", "
                + javaString(originOf(op));
        }

        private void emitClassNew(SemanticOp op, int indent) {
            KindPayload.ClassNewPayload payload =
                (KindPayload.ClassNewPayload) op.payload();
            emitStart(op, indent);
            ClassLayout layout = classLayouts.get(payload.classId());
            if (layout == null && payload.defaultOwner() == DefaultOwner.BUILTIN_DEFAULTS) {

                layout = ClassLayout.BUILTIN_ERROR;
            }
            if (layout == null && payload.defaultOwner() == DefaultOwner.HOST_DEFAULTS) {

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
                case LOCAL -> emitClassNewLocalDefaults(op, payload, provided, indent);
                case SHARED_FACTORY -> emitClassNewFactoryTransfer(op, payload, provided,
                    indent);
                case HOST_DEFAULTS -> {

                }
                case FFI_PLAN ->
                    throw new IllegalStateException("CLASS_NEW " + op.opId()
                        + " carries defaultOwner " + payload.defaultOwner()
                        + ": the extern-C C-struct construction is the FFI"
                        + " child's and is not emitted here — a fail-closed"
                        + " producer defect, never emitted");
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

            for (KindPayload.ProvidedField field : payload.providedFields()) {
                if (fieldOf(layout, field.name()) == null) {
                    String errName = "__ee_" + op.opId().id() + "_"
                        + Integer.toHexString(field.name().hashCode() & 0x7fffffff);
                    // The static extra-key check always fires at this
                    // site (the emitter proves the name is outside the
                    // layout); the constant guard keeps the trailing
                    // instance build reachable for javac.
                    out.append(indent(indent)).append("if (true) {\n");
                    out.append(indent(indent)).append("  JvmRuntime.DealError ")
                        .append(errName)
                        .append(" = JvmRuntime.arm(deal.semantic.ir."
                            + "FailureArmId.CLASS_EXTRA_FIELD, java.util.Map.of(\"field\", ")
                        .append(javaString(field.name()))
                        .append(", \"classId\", ")
                        .append(javaString(payload.classId().text()))
                        .append("), ").append(javaString(originOf(op)))
                        .append(", null, null);\n");
                    if (trace) {
                        emitFailureEvent(op.opId(), op.kind().name(), op,
                            "JvmRuntime.errtext(" + errName + ")", indent + 1);
                    }
                    out.append(indent(indent)).append("  throw ").append(errName)
                        .append(";\n");
                    out.append(indent(indent)).append("}\n");
                }
            }
            if (payload.defaultOwner() == DefaultOwner.BUILTIN_DEFAULTS) {
                emitClassNewBuiltinDefaults(op, payload, layout, indent);
                return;
            }
            if (payload.defaultOwner() == DefaultOwner.HOST_DEFAULTS) {
                emitClassNewHostDefaults(op, payload, indent);
                return;
            }

            String carrier = carrierName(payload.classId());
            String instName = "__inst_" + op.opId().id();
            out.append(indent(indent)).append(carrier).append(' ').append(instName)
                .append(" = new ").append(carrier).append("();\n");
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
                    String extracted = "__ft_" + boundary.opId().id();
                    out.append(indent(indent)).append("Object ").append(extracted)
                        .append(" = ((").append("JvmRuntime.ClassInstance) ")
                        .append(slot((ValueId) factoryOp.result())).append(").read(")
                        .append(javaString(entry.field())).append(");\n");
                    inputExpr = extracted;
                } else {
                    inputExpr = slot(boundaryPayload.input());
                }
                emitBoundaryStart(boundary, inputExpr, boundaryPayload.descriptor(), indent);
                String checked = "__c_" + boundary.opId().id();
                out.append(indent(indent)).append("Object ").append(checked)
                    .append(";\n");
                out.append(indent(indent)).append("try {\n");
                out.append(indent(indent)).append("  ").append(checked)
                    .append(" = ")
                    .append(bcheckArgs(boundaryPayload.descriptor(), inputExpr))
                    .append(";\n");
                out.append(indent(indent)).append("} catch (JvmRuntime.DealError __be) {\n");
                out.append(indent(indent))
                    .append("  JvmRuntime.DealError __bre = new JvmRuntime.DealError("
                        + "__be.code, __be.msg, ")
                    .append(javaString(originOf(boundary)))
                    .append(", __be.expected, __be.actual, __be.frames, null);\n");
                if (trace) {
                    emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                        "JvmRuntime.errtext(__bre)", indent + 1);
                    emitFailureEvent(op.opId(), op.kind().name(), op,
                        "JvmRuntime.errtext(__bre)", indent + 1);
                }
                out.append(indent(indent)).append("  throw __bre;\n");
                out.append(indent(indent)).append("}\n");
                emitBoundarySuccess(boundary, checked, boundaryPayload.descriptor(), indent);
                out.append(indent(indent)).append(instName).append('.')
                    .append(fieldSlot(entry.field())).append(" = ").append(checked)
                    .append(";\n");
                out.append(indent(indent)).append(instName).append('.')
                    .append(fieldFlag(entry.field())).append(" = true;\n");
            }

            out.append(indent(indent)).append(slot((ValueId) op.result()))
                .append(" = ").append(instName).append(";\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
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
         * the boundary START, the checked admission into the named local,
         * the boundary and owner FAILURE events, and the boundary SUCCESS.
         */
        private void emitClassNewFieldAdmission(SemanticOp op, SemanticOp boundary,
                                                String checkedName, int indent) {
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) boundary.payload();
            String inputExpr = slot(boundaryPayload.input());
            emitBoundaryStart(boundary, inputExpr, boundaryPayload.descriptor(), indent);
            out.append(indent(indent)).append("Object ").append(checkedName)
                .append(";\n");
            out.append(indent(indent)).append("try {\n");
            out.append(indent(indent)).append("  ").append(checkedName)
                .append(" = ")
                .append(bcheckArgs(boundaryPayload.descriptor(), inputExpr))
                .append(";\n");
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __be) {\n");
            out.append(indent(indent))
                .append("  JvmRuntime.DealError __bre = new JvmRuntime.DealError("
                    + "__be.code, __be.msg, ")
                .append(javaString(originOf(boundary)))
                .append(", __be.expected, __be.actual, __be.frames, null);\n");
            emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                "JvmRuntime.errtext(__bre)", indent + 1);
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(__bre)", indent + 1);
            out.append(indent(indent)).append("  throw __bre;\n");
            out.append(indent(indent)).append("}\n");
            emitBoundarySuccess(boundary, checkedName, boundaryPayload.descriptor(),
                indent);
        }

        private void emitClassNewBuiltinDefaults(SemanticOp op,
                KindPayload.ClassNewPayload payload, ClassLayout layout, int indent) {
            if (!ClassId.ERROR.equals(payload.classId())
                    || !layout.equals(ClassLayout.BUILTIN_ERROR)) {
                throw new IllegalStateException("CLASS_NEW " + op.opId()
                    + " carries defaultOwner BUILTIN_DEFAULTS for " + payload.classId()
                    + " over " + layout.classId() + ": the builtin-defaults owner is"
                    + " admissible only for the compiler-owned builtin Error class"
                    + " (producer defect)");
            }
            String code = null;
            String message = null;
            for (KindPayload.FieldBoundary entry : payload.fieldBoundaries()) {
                SemanticOp boundary = requireClassNewFieldBoundary(op, entry);
                String checked = "__ec_" + boundary.opId().id();
                emitClassNewFieldAdmission(op, boundary, checked, indent);
                if ("code".equals(entry.field())) {
                    code = checked;
                } else if ("message".equals(entry.field())) {
                    message = checked;
                } else {
                    throw new IllegalStateException("CLASS_NEW " + op.opId()
                        + " names the builtin Error field '" + entry.field() + "':"
                        + " the builtin class declares exactly code and message —"
                        + " a fail-closed producer defect, never emitted");
                }
            }
            String instName = "__inst_" + op.opId().id();
            out.append(indent(indent)).append("JvmRuntime.ErrorValue ")
                .append(instName).append(" = new JvmRuntime.ErrorValue(")
                .append(code == null ? javaString("") : "(String) " + code)
                .append(", ")
                .append(message == null ? javaString("") : "(String) " + message)
                .append(");\n");
            out.append(indent(indent)).append(slot((ValueId) op.result()))
                .append(" = ").append(instName).append(";\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitClassNewHostDefaults(SemanticOp op,
                KindPayload.ClassNewPayload payload, int indent) {
            JvmHostAbiEmission.HostClassFacts facts = hostAbi == null ? null
                : hostAbi.hostClassFacts(payload.classId());
            if (facts == null) {
                throw new IllegalStateException("CLASS_NEW " + op.opId()
                    + " carries defaultOwner HOST_DEFAULTS for " + payload.classId()
                    + ", which is not a declared host class of the compile's"
                    + " declaration surface (the class defaults capture has exactly"
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

            for (KindPayload.ProvidedField field : payload.providedFields()) {
                out.append(indent(indent)).append("if (!").append(facts.defaultsField())
                    .append(".containsKey(").append(javaString(field.name()))
                    .append(")) {\n");
                String errName = "__eh_" + op.opId().id() + "_"
                    + Integer.toHexString(field.name().hashCode() & 0x7fffffff);
                out.append(indent(indent)).append("  JvmRuntime.DealError ")
                    .append(errName).append(" = JvmRuntime.arm(deal.semantic.ir."
                        + "FailureArmId.CLASS_EXTRA_FIELD, java.util.Map.of(\"field\", ")
                    .append(javaString(field.name()))
                    .append(", \"classId\", ")
                    .append(javaString(payload.classId().text()))
                    .append("), ").append(javaString(originOf(op)))
                    .append(", null, null);\n");
                if (trace) {
                    emitFailureEvent(op.opId(), op.kind().name(), op,
                        "JvmRuntime.errtext(" + errName + ")", indent + 1);
                }
                out.append(indent(indent)).append("  throw ").append(errName)
                    .append(";\n");
                out.append(indent(indent)).append("}\n");
            }

            java.util.Map<String, String> checked = new java.util.LinkedHashMap<>();
            for (KindPayload.FieldBoundary entry : payload.fieldBoundaries()) {
                SemanticOp boundary = requireClassNewFieldBoundary(op, entry);
                String checkedName = "__hc_" + boundary.opId().id();
                emitClassNewFieldAdmission(op, boundary, checkedName, indent);
                checked.put(entry.field(), checkedName);
            }
            // Phases 2-5: the record in field declaration order — provided
            // fields with their boundary-published values projected onto the
            // declared host carriers, omitted required-present fields from
            // the loaded defaults capture (the retained missing-default
            // guard when the map carries none), omitted optional fields
            // absent; the constructor tags the instance with its canonical
            // class identity.
            java.util.Map<String, ValueId> providedSlots = new java.util.LinkedHashMap<>();
            for (KindPayload.ProvidedField field : payload.providedFields()) {
                providedSlots.put(field.name(), field.valueOpId());
            }
            java.util.List<String> args = new java.util.ArrayList<>();
            for (JvmHostAbiEmission.HostFieldFacts field : facts.fields()) {
                String providedChecked = checked.get(field.name());
                if (providedSlots.containsKey(field.name())) {
                    if (providedChecked == null) {
                        throw new IllegalStateException("CLASS_NEW " + op.opId()
                            + " provides field '" + field.name() + "' without its"
                            + " pinned CLASS_LITERAL_FIELD boundary child (producer"
                            + " defect)");
                    }
                    args.add(JvmHostAbiEmission.hostFieldWriteProjection(
                        field.descriptorText(), field.storageType(), providedChecked,
                        "__hostProjectArg"));
                    if (field.optional()) {
                        args.add("true");
                    }
                    continue;
                }
                if (field.optional()) {
                    args.add("null");
                    args.add("false");
                    continue;
                }
                String loaded = "__hostDefault(" + facts.defaultsField() + ", "
                    + javaString(field.name()) + ", "
                    + javaString(payload.classId().text()) + ", "
                    + originArgs(op) + ")";
                args.add(JvmHostAbiEmission.hostFieldWriteProjection(
                    field.descriptorText(), field.storageType(), loaded,
                    "__hostProjectArg"));
            }
            String instName = "__hi_" + op.opId().id();
            out.append(indent(indent)).append("$DealRt.")
                .append(facts.recordSimpleName()).append(' ').append(instName)
                .append(" = new $DealRt.").append(facts.recordSimpleName())
                .append('(').append(String.join(", ", args)).append(");\n");
            out.append(indent(indent)).append(slot((ValueId) op.result()))
                .append(" = ").append(instName).append(";\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitClassNewLocalDefaults(SemanticOp op,
                KindPayload.ClassNewPayload payload, java.util.Set<String> provided,
                int indent) {
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
                emitClassDefaultCall(op, defaultOp, indent);
            }
        }

        /** One CLASS_DEFAULT child execution (START, method, terminal). */
        private void emitClassDefaultCall(SemanticOp op, SemanticOp defaultOp, int indent) {
            if (trace) {
                out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                    .append(javaString(opKey(defaultOp.opId())))
                    .append(", \"START\", \"CLASS_DEFAULT\", ")
                    .append(javaString(defaultOp.contract().canonicalDigest()))
                    .append(", \"-\", List.of(), null, null);\n");
            }
            out.append(indent(indent)).append("try {\n");
            if (defaultOp.result() instanceof ValueId resultId) {
                out.append(indent(indent)).append("  ").append(slot(resultId))
                    .append(" = ").append(defaultFn(defaultOp.opId())).append("();\n");
            } else {
                out.append(indent(indent)).append("  ")
                    .append(defaultFn(defaultOp.opId())).append("();\n");
            }
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __e) {\n");
            if (trace) {
                emitFailureEvent(defaultOp.opId(), "CLASS_DEFAULT", defaultOp,
                    "JvmRuntime.errtext(__e)", indent + 1);
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "JvmRuntime.errtext(__e)", indent + 1);
            }
            out.append(indent(indent)).append("  throw __e;\n");
            out.append(indent(indent)).append("}\n");
            emitClassDefaultSuccess(defaultOp, indent);
        }

        /** The CLASS_DEFAULT SUCCESS terminal of one default child. */
        private void emitClassDefaultSuccess(SemanticOp defaultOp, int indent) {
            if (!trace) {
                return;
            }
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(defaultOp.opId())))
                .append(", \"SUCCESS\", \"CLASS_DEFAULT\", ")
                .append(javaString(defaultOp.contract().canonicalDigest()))
                .append(", \"-\", List.of(), JvmRuntime.atom(")
                .append(defaultOp.result() instanceof ValueId resultId
                    ? slot(resultId) : "null")
                .append(", ")
                .append(javaString(staticKind(
                    (RuntimeDescriptor) defaultOp.resultType())))
                .append("), null);\n");
        }

        private void emitClassNewFactoryTransfer(SemanticOp op,
                KindPayload.ClassNewPayload payload, java.util.Set<String> provided,
                int indent) {
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
            out.append(indent(indent)).append("String __prevMod_")
                .append(op.opId().id()).append(" = MODULE;\n");
            out.append(indent(indent)).append("MODULE = ")
                .append(javaString(ownerPath)).append(";\n");
            out.append(indent(indent)).append("JvmRuntime.setModule(MODULE);\n");
            if (trace) {
                out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                    .append(javaString(opKey(factoryOp.opId())))
                    .append(", \"START\", \"CLASS_FACTORY\", ")
                    .append(javaString(factoryOp.contract().canonicalDigest()))
                    .append(", ").append(javaString(opKey(op.opId())))
                    .append(", List.of(), null, null);\n");
            }
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
                if (trace) {
                    out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                        .append(javaString(opKey(defaultOp.opId())))
                        .append(", \"START\", \"CLASS_DEFAULT\", ")
                        .append(javaString(defaultOp.contract().canonicalDigest()))
                        .append(", \"-\", List.of(), null, null);\n");
                }
                out.append(indent(indent)).append("try {\n");
                if (defaultOp.result() instanceof ValueId resultId) {
                    out.append(indent(indent)).append("  ").append(slot(resultId))
                        .append(" = ").append(defaultFn(defaultOp.opId())).append("();\n");
                } else {
                    out.append(indent(indent)).append("  ")
                        .append(defaultFn(defaultOp.opId())).append("();\n");
                }
                out.append(indent(indent)).append("} catch (JvmRuntime.DealError __e) {\n");
                if (trace) {
                    emitFailureEvent(defaultOp.opId(), "CLASS_DEFAULT", defaultOp,
                        "JvmRuntime.errtext(__e)", indent + 1);
                    out.append(indent(indent)).append("  JvmRuntime.ev(MODULE, ")
                        .append(javaString(opKey(factoryOp.opId())))
                        .append(", \"FAILURE\", \"CLASS_FACTORY\", ")
                        .append(javaString(factoryOp.contract().canonicalDigest()))
                        .append(", ").append(javaString(opKey(op.opId())))
                        .append(", List.of(), null, JvmRuntime.errtext(__e));\n");
                }
                // Restore the caller's module context before the caller's
                // own terminal: the owner-side terminals above carry the
                // owner's module, the caller's CLASS_NEW FAILURE carries
                // the caller's (the oracle's own tagging).
                out.append(indent(indent)).append("  MODULE = __prevMod_")
                    .append(op.opId().id()).append(";\n");
                out.append(indent(indent)).append("  JvmRuntime.setModule(MODULE);\n");
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "JvmRuntime.errtext(__e)", indent + 1);
                out.append(indent(indent)).append("  throw __e;\n");
                out.append(indent(indent)).append("}\n");
                emitClassDefaultSuccess(defaultOp, indent);
                filled.add(defaultOp);
            }
            // The untagged internal transfer instance: the defaulted
            // fields present (present null is f == null && p == true),
            // every other field missing.
            String carrier = carrierName(payload.classId());
            String instName = "__tf_" + factoryOp.opId().id() + "_" + op.opId().id();
            out.append(indent(indent)).append(carrier).append(' ').append(instName)
                .append(" = new ").append(carrier).append("();\n");
            for (SemanticOp defaultOp : filled) {
                KindPayload.ClassDefaultPayload defaultPayload =
                    (KindPayload.ClassDefaultPayload) defaultOp.payload();
                out.append(indent(indent)).append(instName).append('.')
                    .append(fieldSlot(defaultPayload.field())).append(" = ")
                    .append(slot((ValueId) defaultOp.result())).append(";\n");
                out.append(indent(indent)).append(instName).append('.')
                    .append(fieldFlag(defaultPayload.field())).append(" = true;\n");
            }
            out.append(indent(indent)).append(slot(factoryResult)).append(" = ")
                .append(instName).append(";\n");
            if (trace) {
                out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                    .append(javaString(opKey(factoryOp.opId())))
                    .append(", \"SUCCESS\", \"CLASS_FACTORY\", ")
                    .append(javaString(factoryOp.contract().canonicalDigest()))
                    .append(", ").append(javaString(opKey(op.opId())))
                    .append(", List.of(), JvmRuntime.atom(").append(instName)
                    .append(", \"class\"), null);\n");
            }
            out.append(indent(indent)).append("MODULE = __prevMod_")
                .append(op.opId().id()).append(";\n");
            out.append(indent(indent)).append("JvmRuntime.setModule(MODULE);\n");
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

        private void emitIntrinsic(SemanticOp op, int indent) {
            KindPayload.IntrinsicCallPayload payload =
                (KindPayload.IntrinsicCallPayload) op.payload();
            emitStart(op, indent);
            String target = slot((ValueId) op.result());
            String input = slot(payload.input());
            String kind = staticKind(op.operandTypes().get(0));
            String origin = originOf(op);
            switch (payload.kind()) {

                case INT_CONVERT -> out.append(indent(indent)).append(target)
                    .append(" = JvmRuntime.intConv(").append(input).append(", ")
                    .append(javaString(op.kind().name())).append(", ")
                    .append(javaString(kind)).append(", ")
                    .append(javaString(opKey(op.opId()))).append(", ")
                    .append(javaString(op.contract().canonicalDigest())).append(", ")
                    .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(javaString(origin)).append(");\n");
                case NUMBER_CONVERT -> out.append(indent(indent)).append(target)
                    .append(" = JvmRuntime.numConv(").append(input).append(", ")
                    .append(javaString(op.kind().name())).append(", ")
                    .append(javaString(kind)).append(", ")
                    .append(javaString(opKey(op.opId()))).append(", ")
                    .append(javaString(op.contract().canonicalDigest())).append(", ")
                    .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(javaString(origin)).append(");\n");
                case BYTES_NEW -> out.append(indent(indent)).append(target)
                    .append(" = JvmRuntime.bytesNew(").append(input).append(", ")
                    .append(javaString(op.kind().name())).append(", ")
                    .append(javaString(opKey(op.opId()))).append(", ")
                    .append(javaString(op.contract().canonicalDigest())).append(", ")
                    .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                    .append(javaString(origin)).append(");\n");
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
        }

        /**
         * One checked boundary admission assigning into an existing
         * expression under the caller's enclosing block (the stdlib
         * parameter and return shapes): the value is checked into
         * {@code targetExpr} and the boundary/owner FAILURE events and
         * the boundary SUCCESS carry the boundary's own origin.
         */
        private void emitCheckedBoundaryAdmission(SemanticOp op, SemanticOp boundary,
                                                  String targetExpr, String valueExpr,
                                                  int indent) {
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) boundary.payload();
            out.append(indent(indent)).append("  try {\n");
            out.append(indent(indent)).append("    ").append(targetExpr)
                .append(" = ")
                .append(bcheckArgs(boundaryPayload.descriptor(), valueExpr))
                .append(";\n");
            out.append(indent(indent)).append("  } catch (JvmRuntime.DealError __be) {\n");
            out.append(indent(indent))
                .append("    JvmRuntime.DealError __bre = new JvmRuntime.DealError("
                    + "__be.code, __be.msg, ")
                .append(javaString(originOf(boundary)))
                .append(", __be.expected, __be.actual, __be.frames, null);\n");
            emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                "JvmRuntime.errtext(__bre)", indent + 1);
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(__bre)", indent + 1);
            out.append(indent(indent)).append("    throw __bre;\n");
            out.append(indent(indent)).append("  }\n");
            emitBoundarySuccess(boundary, targetExpr, boundaryPayload.descriptor(),
                indent);
        }

        private void emitStdlib(SemanticOp op, int indent) {
            KindPayload.StdlibCallPayload payload =
                (KindPayload.StdlibCallPayload) op.payload();
            if (trace) {
                // The custom START: the operand atoms render the actual
                // value kind (the oracle's atomOf — a dynamic argument at
                // a declared boundary atomizes as its own kind), never the
                // declared kind.
                StringBuilder inputs = new StringBuilder();
                for (int i = 0; i < payload.args().size(); i++) {
                    if (i > 0) {
                        inputs.append(", ");
                    }
                    inputs.append("JvmRuntime.rawAtom(")
                        .append(slot(payload.args().get(i))).append(", ")
                        .append(javaString(staticKind(op.operandTypes().get(i))))
                        .append(")");
                }
                out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                    .append(javaString(opKey(op.opId()))).append(", \"START\", ")
                    .append("\"STDLIB_CALL\", ")
                    .append(javaString(op.contract().canonicalDigest())).append(", ")
                    .append(javaString(parentKey(op.origin().parentOpId())))
                    .append(", List.of(").append(inputs).append("), null, null);\n");
            }
            // The closed STDLIB_PARAMETER boundaries in one-based declared
            // order: a failing boundary publishes the boundary FAILURE and
            // the op FAILURE events with the boundary origin, then rethrows
            // (the exact oracle event sequence — never a silent terminal).
            List<SemanticOp> paramBoundaries = stdlibParamBoundaries(op);
            for (SemanticOp boundary : paramBoundaries) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                if (trace) {
                    // Custom boundary START: the input atom renders the
                    // actual value kind (the oracle's atomOf).
                    out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                        .append(javaString(opKey(boundary.opId())))
                        .append(", \"START\", \"BOUNDARY\", ")
                        .append(javaString(boundary.contract().canonicalDigest()))
                        .append(", ")
                        .append(javaString(parentKey(boundary.origin().parentOpId())))
                        .append(", List.of(JvmRuntime.rawAtom(")
                        .append(slot(boundaryPayload.input())).append(", ")
                        .append(javaString(staticKind(boundaryPayload.descriptor())))
                        .append(")), null, null);\n");
                }
                String admitted = "__sb_" + boundary.opId().id();
                out.append(indent(indent)).append("  Object ").append(admitted)
                    .append(";\n");
                emitCheckedBoundaryAdmission(op, boundary, admitted,
                    slot(boundaryPayload.input()), indent);
            }
            SemanticOp returnBoundary = stdlibReturnBoundary(op);
            String target = slot((ValueId) op.result());
            // Every row runs the one row invoker the cataloged callable also
            // runs (M4: the two console rows' single-effect write included),
            // so the direct arm and the callable share one realization and
            // the direct arm's observable is unchanged.
            out.append(indent(indent)).append(target)
                .append(" = JvmRuntime.stdlibInvoke(")
                .append(javaString(op.kind().name())).append(", ")
                .append(javaString(payload.function().name())).append(", ")
                .append(javaString(opKey(op.opId()))).append(", ")
                .append(javaString(op.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                .append(javaString(originOf(op))).append(", new Object[]{");
            for (int i = 0; i < payload.args().size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(slot(payload.args().get(i)));
            }
            out.append("});\n");
            if (returnBoundary != null) {
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) returnBoundary.payload();
                if (trace) {
                    // Custom boundary START: the result atom renders the
                    // actual value kind (the oracle's atomOf).
                    out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                        .append(javaString(opKey(returnBoundary.opId())))
                        .append(", \"START\", \"BOUNDARY\", ")
                        .append(javaString(returnBoundary.contract().canonicalDigest()))
                        .append(", ")
                        .append(javaString(
                            parentKey(returnBoundary.origin().parentOpId())))
                        .append(", List.of(JvmRuntime.rawAtom(")
                        .append(target).append(", ")
                        .append(javaString(staticKind(boundaryPayload.descriptor())))
                        .append(")), null, null);\n");
                }
                emitCheckedBoundaryAdmission(op, returnBoundary, target, target,
                    indent);
            }
            emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitBranch(SemanticOp op, int indent) {
            KindPayload.BranchPayload payload = (KindPayload.BranchPayload) op.payload();
            emitStart(op, indent);
            String condition = slot(payload.condition());
            switch (payload.selector()) {
                case IF -> {
                    out.append(indent(indent)).append("if (((Boolean) ").append(condition)
                        .append(").booleanValue()) {\n");
                    emitBlockOps(payload.selectedBlock(), indent + 1);
                    out.append(indent(indent)).append("}");
                    if (payload.alternateBlock() != null) {
                        out.append(" else {\n");
                        emitBlockOps(payload.alternateBlock(), indent + 1);
                        out.append(indent(indent)).append("}");
                    }
                    out.append("\n");
                    if (payload.alternateBlock() == null
                            || blockCompletesNormally(payload.selectedBlock())
                            || blockCompletesNormally(payload.alternateBlock())) {
                        emitPlainSuccess(op, indent);
                    } else {
                        // JLS §14.21: the emitted if/else cannot complete
                        // normally (both branches end in a transfer), so its
                        // normal-completion SUCCESS event is unreachable —
                        // javac rejects a plain statement here, and the
                        // oracle's normal-completion path is unreachable on
                        // the same traces. The transfer closures emit the
                        // closed-success line on every transfer path.
                        out.append(indent(indent)).append("// unreachable: the preceding"
                            + " statement cannot complete normally\n");
                    }
                }
                case LOGICAL_AND, LOGICAL_OR -> {
                    String target = slot((ValueId) op.result());
                    boolean isAnd = payload.selector()
                        == deal.semantic.ir.ControlSelector.LOGICAL_AND;
                    if (isAnd) {
                        // AND: the block runs when the left value is true.
                        out.append(indent(indent)).append("if (((Boolean) ")
                            .append(condition).append(").booleanValue()) {\n");
                    } else {
                        // OR: the block runs when the left value is false.
                        out.append(indent(indent)).append("if (!((Boolean) ")
                            .append(condition).append(").booleanValue()) {\n");
                    }
                    emitBlockOps(payload.selectedBlock(), indent + 1);
                    out.append(indent(indent)).append("} else {\n");
                    out.append(indent(indent)).append("  ").append(target).append(" = ")
                        .append(condition).append(";\n");
                    out.append(indent(indent)).append("}\n");
                    emitResultSuccess(op, target, (RuntimeDescriptor) op.resultType(),
                        indent);
                }
                default -> throw new IllegalStateException("BRANCH selector "
                    + payload.selector());
            }
        }

        private void emitLoop(SemanticOp op, int indent) {
            KindPayload.LoopPayload payload = (KindPayload.LoopPayload) op.payload();
            emitStart(op, indent);
            String condition = slot(payload.condition());
            switch (payload.selector()) {
                case WHILE -> {
                    out.append(indent(indent)).append(loopLabel(op.opId()))
                        .append(": while (true) {\n");
                    emitBlockOps(payload.initBlock(), indent + 1);
                    out.append(indent(indent)).append("  if (!((Boolean) ").append(condition)
                        .append(").booleanValue()) break ").append(loopLabel(op.opId()))
                        .append(";\n");
                    emitBlockOps(payload.bodyBlock(), indent + 1);
                    out.append(indent(indent)).append("}\n");
                }
                case FOR -> {
                    emitBlockOps(payload.initBlock(), indent);
                    out.append(indent(indent)).append(loopLabel(op.opId()))
                        .append(": while (true) {\n");
                    out.append(indent(indent)).append("  if (!((Boolean) ").append(condition)
                        .append(").booleanValue()) break ").append(loopLabel(op.opId()))
                        .append(";\n");
                    out.append(indent(indent)).append("  CONT").append(op.opId().id())
                        .append(": do {\n");
                    emitBlockOps(payload.bodyBlock(), indent + 2);
                    out.append(indent(indent)).append("  } while (false);\n");
                    if (payload.updateBlock() != null) {
                        if (forUpdateReachable(payload.bodyBlock(), op.opId())) {
                            emitBlockOps(payload.updateBlock(), indent + 1);
                        } else if (blockHasEmittedOps(payload.updateBlock())) {
                            // JLS §14.21: the emitted CONT do statement
                            // cannot complete normally (every path of the
                            // emitted body leaves by break/return/throw and
                            // no reachable continue targets this loop), so
                            // the update statements would be unreachable
                            // Java — javac rejects them, and the oracle's
                            // loop walk stops on the same non-completing
                            // path. Emit the skip marker instead; the update
                            // ops stay in the lowered unit.
                            out.append(indent(indent)).append("  // unreachable: the"
                                + " preceding statement cannot complete normally\n");
                        }
                    }
                    out.append(indent(indent)).append("}\n");
                }
                default -> throw new IllegalStateException("LOOP selector "
                    + payload.selector());
            }
            emitPlainSuccess(op, indent);
        }

        private void emitForEach(SemanticOp op, int indent) {
            KindPayload.ForEachPayload payload = (KindPayload.ForEachPayload) op.payload();
            emitStart(op, indent);
            switch (payload.mode()) {
                case ARRAY_VALUES -> {
                    String iterable = slot(payload.iterable());
                    String cellName = cell(payload.binding(), payload.generation());
                    out.append(indent(indent)).append("{\n");
                    out.append(indent(indent)).append("  JvmRuntime.Array __it = (JvmRuntime.Array) ")
                        .append(iterable).append(";\n");
                    out.append(indent(indent)).append("  int __itn = __it.length;\n");
                    out.append(indent(indent)).append("  FE").append(op.opId().id())
                        .append(": for (int __i = 0; __i < __itn; __i++) {\n");
                    out.append(indent(indent)).append("    Object __elem = JvmRuntime.MISSING;\n");
                    out.append(indent(indent)).append("    if (__i < __it.length && __i < __it.elements.size()) {\n");
                    out.append(indent(indent)).append("      __elem = __it.elements.get(__i);\n");
                    out.append(indent(indent)).append("    }\n");
                    out.append(indent(indent)).append("    JvmRuntime.foreachCheck(")
                        .append(javaString(opKey(op.opId()))).append(", ")
                        .append(javaString(op.contract().canonicalDigest())).append(", ")
                        .append(javaString(parentKey(op.origin().parentOpId()))).append(", ")
                        .append(javaString(descriptorText(elementDescriptorOf(op))))
                        .append(", __elem, ").append(javaString(originOf(op))).append(");\n");
                    out.append(indent(indent)).append("    ").append(cellName)
                        .append(" = new Object[]{__elem};\n");
                    emitBlockOps(payload.body(), indent + 2);
                    out.append(indent(indent)).append("  }\n");
                    out.append(indent(indent)).append("}\n");
                }
                case STRING_SCALARS -> {
                    String iterable = slot(payload.iterable());
                    String cellName = cell(payload.binding(), payload.generation());
                    out.append(indent(indent)).append("{\n");
                    out.append(indent(indent)).append("  String __it = (String) ")
                        .append(iterable).append(";\n");
                    out.append(indent(indent)).append("  int[] __cps = __it.codePoints().toArray();\n");
                    out.append(indent(indent)).append("  FE").append(op.opId().id())
                        .append(": for (int __cp : __cps) {\n");
                    out.append(indent(indent)).append("    ").append(cellName)
                        .append(" = new Object[]{new String(Character.toChars(__cp))};\n");
                    emitBlockOps(payload.body(), indent + 2);
                    out.append(indent(indent)).append("  }\n");
                    out.append(indent(indent)).append("}\n");
                }
            }
            emitPlainSuccess(op, indent);
        }

        private void emitTryCatch(SemanticOp op, int indent) {
            KindPayload.TryCatchPayload payload =
                (KindPayload.TryCatchPayload) op.payload();
            emitStart(op, indent);
            String cellName = cell(payload.catchBinding(), 0);
            out.append(indent(indent)).append("try {\n");
            tryDepth++;
            try {
                out.append(indent(indent)).append("  try {\n");
                emitBlockOps(payload.tryBlock(), indent + 2);
                out.append(indent(indent))
                    .append("  } catch (JvmRuntime.DealError __terr) {\n");
                out.append(indent(indent)).append("    ").append(cellName)
                    .append(" = new JvmRuntime.ErrorValue(__terr.code, __terr.msg);\n");
                out.append(indent(indent)).append("    try {\n");
                emitBlockOps(payload.catchBlock(), indent + 3);
                out.append(indent(indent))
                    .append("    } catch (JvmRuntime.DealError __cerr) {\n");
                out.append(indent(indent))
                    .append("      JvmRuntime.DealError __wrapped = new "
                        + "JvmRuntime.DealError(__cerr.code, __cerr.msg, "
                        + "__cerr.origin, __cerr.expected, __cerr.actual, "
                        + "__cerr.frames, __terr);\n");
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "JvmRuntime.errtext(__wrapped)", indent + 3);
                out.append(indent(indent)).append("      throw __wrapped;\n");
                out.append(indent(indent)).append("    }\n");
                out.append(indent(indent)).append("  }\n");
            } finally {
                tryDepth--;
            }
            out.append(indent(indent))
                .append("} catch (JvmRuntime.Transfer __tr) {\n");
            emitJvmTransferDispatch(op, payload.tryBlock(), indent + 1);
            emitJvmTransferDispatch(op, payload.catchBlock(), indent + 1);
            out.append(indent(indent)).append("  throw __tr;\n");
            out.append(indent(indent)).append("}\n");
            if (blockCompletesNormally(payload.tryBlock())
                    || blockCompletesNormally(payload.catchBlock())) {
                emitPlainSuccess(op, indent);
            } else {
                // JLS §14.21: the emitted try/catch cannot complete normally
                // (both clauses end in a transfer), so its
                // normal-completion SUCCESS event is unreachable — javac
                // rejects a plain statement here, and the oracle's
                // normal-completion path is unreachable on the same traces.
                out.append(indent(indent)).append("// unreachable: the preceding"
                    + " statement cannot complete normally\n");
            }
        }

        /** The transfer dispatch: each distinct BREAK/CONTINUE/RETURN of the
         *  block's emitted reachable prefix re-applies after emitting the
         *  TRY_CATCH SUCCESS. A transfer whose target label is defined at
         *  this level takes the emitted jump; a farther target leaves the
         *  protected region as the re-raised marker (the next enclosing
         *  dispatch owns the jump) — no emitted jump references a label
         *  outside its scope. */
        private void emitJvmTransferDispatch(SemanticOp tryOp, BlockId block, int indent) {
            List<SemanticOp> transfers = new ArrayList<>();
            collectReachableTransfers(block, transfers);
            for (SemanticOp transfer : transfers) {
                switch (transfer.kind()) {
                    case BREAK -> {
                        KindPayload.BreakPayload breakPayload =
                            (KindPayload.BreakPayload) transfer.payload();
                        SemanticOp target = opsById.get(breakPayload.loopId());
                        boolean visible = transferTargetVisible(tryOp, target);
                        out.append(indent(indent)).append("if (\"break\".equals(__tr.kind) ")
                            .append("&& __tr.id == ").append(breakPayload.loopId().id())
                            .append("L) {\n");
                        emitPlainSuccess(tryOp, indent + 1);
                        emitTransferClosures(tryOp, target, !visible, indent + 1);
                        if (visible) {
                            out.append(indent(indent)).append("  ")
                                .append(breakJump(breakPayload.loopId(), target))
                                .append("\n");
                        } else {
                            out.append(indent(indent)).append("  throw __tr;\n");
                        }
                        out.append(indent(indent)).append("}\n");
                    }
                    case CONTINUE -> {
                        KindPayload.ContinuePayload continuePayload =
                            (KindPayload.ContinuePayload) transfer.payload();
                        SemanticOp target = opsById.get(continuePayload.loopId());
                        boolean visible = transferTargetVisible(tryOp, target);
                        out.append(indent(indent)).append("if (\"continue\".equals(__tr.kind) ")
                            .append("&& __tr.id == ").append(continuePayload.loopId().id())
                            .append("L) {\n");
                        emitPlainSuccess(tryOp, indent + 1);
                        emitTransferClosures(tryOp, target, !visible, indent + 1);
                        if (visible) {
                            out.append(indent(indent)).append("  ")
                                .append(continueJump(continuePayload.loopId(), target))
                                .append("\n");
                        } else {
                            out.append(indent(indent)).append("  throw __tr;\n");
                        }
                        out.append(indent(indent)).append("}\n");
                    }
                    case RETURN -> {
                        boolean visible = transferTargetVisible(tryOp, null);
                        out.append(indent(indent)).append("if (\"return\".equals(__tr.kind)) {\n");
                        emitPlainSuccess(tryOp, indent + 1);
                        emitTransferClosures(tryOp, null, !visible, indent + 1);
                        if (visible) {
                            out.append(indent(indent)).append("  return __tr.value;\n");
                        } else {
                            out.append(indent(indent)).append("  throw __tr;\n");
                        }
                        out.append(indent(indent)).append("}\n");
                    }
                    default -> {
                    }
                }
            }
        }


        /**
         * The reachable transfer ops of one block: the same walk the
         * emission applies (the non-owned, non-skipped members in order,
         * entering each op's structure payloads), stopping at the first op
         * whose emitted statement cannot complete normally. Every op behind
         * that one is unreachable Java (JLS &sect;14.21) and is skipped by
         * the block walk, so its transfers are never signalled and their
         * target labels are not in scope at the dispatch site — collecting
         * them would emit a jump to a label the skipped subtree never
         * defines. The raw structural walk
         * ({@link EmitterSessionBase#collectTransfers}) is used by the Lua
         * target, which emits every tail op; this reachability-aware form is
         * the JVM target's dispatch collection.
         */
        private void collectReachableTransfers(BlockId block, List<SemanticOp> transfers) {
            StructuredBodyTable ownerTable = blockTableOf.get(block);
            if (ownerTable == null) {
                ownerTable = table;
            }
            List<OpId> memberOps = ownerTable.blockOps().get(block);
            if (memberOps == null) {
                throw new IllegalStateException("block " + block
                    + " has no membership row in its owning unit's table (a malformed"
                    + " table — the production validator rejects this)");
            }
            for (OpId opId : memberOps) {
                if (ownedChildren.contains(opId) || skippedOps.contains(opId)) {
                    continue;
                }
                SemanticOp op = opsById.get(opId);
                if (op == null) {
                    continue;
                }
                switch (op.kind()) {
                    case BREAK, CONTINUE, RETURN -> transfers.add(op);
                    case BRANCH -> {
                        KindPayload.BranchPayload payload =
                            (KindPayload.BranchPayload) op.payload();
                        collectReachableTransfers(payload.selectedBlock(), transfers);
                        if (payload.alternateBlock() != null) {
                            collectReachableTransfers(payload.alternateBlock(), transfers);
                        }
                    }
                    case LOOP -> {
                        KindPayload.LoopPayload payload =
                            (KindPayload.LoopPayload) op.payload();
                        if (payload.initBlock() != null) {
                            collectReachableTransfers(payload.initBlock(), transfers);
                        }
                        collectReachableTransfers(payload.bodyBlock(), transfers);
                        if (payload.updateBlock() != null) {
                            collectReachableTransfers(payload.updateBlock(), transfers);
                        }
                    }
                    case FOR_EACH -> {
                        KindPayload.ForEachPayload payload =
                            (KindPayload.ForEachPayload) op.payload();
                        collectReachableTransfers(payload.body(), transfers);
                    }
                    case TRY_CATCH -> {
                        KindPayload.TryCatchPayload payload =
                            (KindPayload.TryCatchPayload) op.payload();
                        collectReachableTransfers(payload.tryBlock(), transfers);
                        collectReachableTransfers(payload.catchBlock(), transfers);
                    }
                    default -> {
                    }
                }
                if (!completesNormally(op)) {
                    return;
                }
            }
        }

        private void emitThrow(SemanticOp op, int indent) {
            KindPayload.ThrowPayload payload = (KindPayload.ThrowPayload) op.payload();
            emitStart(op, indent);
            out.append(indent(indent)).append("JvmRuntime.DealError __thrown = new "
                + "JvmRuntime.DealError(((").append("JvmRuntime.ErrorValue) ")
                .append(slot(payload.errorValue())).append(").code, ((")
                .append("JvmRuntime.ErrorValue) ").append(slot(payload.errorValue()))
                .append(").message, ").append(javaString(originOf(op)))
                .append(", null, null, JvmRuntime.framesText(), null);\n");
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(__thrown)", indent);
            out.append(indent(indent)).append("throw __thrown;\n");
        }

        /**
         * One boundary admission checking {@code inputExpr} into a fresh
         * local and re-projecting a failure at the boundary's own origin
         * (the stdlib-callee result and body-local RETURN shapes).
         */
        private void emitBoundaryAdmission(SemanticOp op, SemanticOp boundary,
                                           String inputExpr, String checkedName,
                                           String caughtName, String rejectionName,
                                           int indent) {
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) boundary.payload();
            emitBoundaryStart(boundary, inputExpr, boundaryPayload.descriptor(), indent);
            out.append(indent(indent)).append("Object ").append(checkedName).append(";\n");
            out.append(indent(indent)).append("try {\n");
            out.append(indent(indent + 1)).append(checkedName).append(" = ")
                .append(bcheckArgs(boundaryPayload.descriptor(), inputExpr))
                .append(";\n");
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError ")
                .append(caughtName).append(") {\n");
            out.append(indent(indent + 1)).append("JvmRuntime.DealError ")
                .append(rejectionName).append(" = new JvmRuntime.DealError(")
                .append(caughtName).append(".code, ").append(caughtName)
                .append(".msg, ")
                .append(javaString(originOf(boundary)))
                .append(", ").append(caughtName).append(".expected, ").append(caughtName)
                .append(".actual, ").append(caughtName).append(".frames, null);\n");
            emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                "JvmRuntime.errtext(" + rejectionName + ")", indent + 1);
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(" + rejectionName + ")", indent + 1);
            out.append(indent(indent + 1)).append("throw ").append(rejectionName)
                .append(";\n");
            out.append(indent(indent)).append("}\n");
            emitBoundarySuccess(boundary, checkedName, boundaryPayload.descriptor(),
                indent);
        }

        private void emitReturn(SemanticOp op, int indent) {
            KindPayload.ReturnPayload payload = (KindPayload.ReturnPayload) op.payload();
            SemanticOp boundary = opsById.get(payload.returnBoundaryOpId());
            emitStart(op, indent);
            String value = payload.value() == null ? "null" : slot(payload.value());
            out.append(indent(indent)).append("Object __rv_").append(op.opId().id())
                .append(" = ").append(value).append(";\n");
            // The body-local return cell's own failure projection: the cell
            // projection carries the cell's origin (the callee's RETURN), the
            // boundary and the RETURN op emit their FAILURE terminals, and the
            // identical error propagates (the semantic oracle's
            // runBoundaryChild + the owning-op FAILURE).
            String rvc = "__rvc_" + op.opId().id();
            String rejection = "__bre_" + op.opId().id();
            String caught = "__be_" + op.opId().id();
            emitBoundaryAdmission(op, boundary, "__rv_" + op.opId().id(), rvc,
                caught, rejection, indent);
            emitPlainSuccess(op, indent);
            if (!transferTargetVisible(op, null)) {
                emitTransferClosures(op, null, true, indent);
                out.append(indent(indent))
                    .append("throw new JvmRuntime.Transfer(\"return\", 0L, __rvc_")
                    .append(op.opId().id()).append(");\n");
            } else {
                emitTransferClosures(op, null, false, indent);
                out.append(indent(indent)).append("return __rvc_").append(op.opId().id())
                    .append(";\n");
            }
        }

        private void emitBreak(SemanticOp op, int indent) {
            KindPayload.BreakPayload payload = (KindPayload.BreakPayload) op.payload();
            emitStart(op, indent);
            emitPlainSuccess(op, indent);
            SemanticOp target = opsById.get(payload.loopId());
            if (transferTargetVisible(op, target)) {
                emitTransferClosures(op, target, false, indent);
                out.append(indent(indent)).append(breakJump(payload.loopId(), target))
                    .append("\n");
            } else {
                emitTransferClosures(op, target, true, indent);
                out.append(indent(indent)).append("throw new JvmRuntime.Transfer(\"break\", ")
                    .append(payload.loopId().id()).append("L, null);\n");
            }
        }

        private void emitContinue(SemanticOp op, int indent) {
            KindPayload.ContinuePayload payload = (KindPayload.ContinuePayload) op.payload();
            emitStart(op, indent);
            emitPlainSuccess(op, indent);
            SemanticOp target = opsById.get(payload.loopId());
            if (transferTargetVisible(op, target)) {
                emitTransferClosures(op, target, false, indent);
                out.append(indent(indent))
                    .append(continueJump(payload.loopId(), target)).append("\n");
            } else {
                emitTransferClosures(op, target, true, indent);
                out.append(indent(indent))
                    .append("throw new JvmRuntime.Transfer(\"continue\", ")
                    .append(payload.loopId().id()).append("L, null);\n");
            }
        }

        private void emitDiscard(SemanticOp op, int indent) {
            emitStart(op, indent);
            emitPlainSuccess(op, indent);
        }

        /**
         * {@code EXPORT_PUBLISH} — the checked {@code MODULE_EXPORT}
         * boundary (its owned child) then the publication of the callable
         * function value ({@code JvmRuntime.FunctionValue}) into the
         * emitting module's export surface.
         */
        private void emitExportPublish(SemanticOp op, int indent) {
            KindPayload.ExportPublishPayload payload =
                (KindPayload.ExportPublishPayload) op.payload();
            emitStart(op, indent);
            for (SemanticOp candidate : opsById.values()) {
                if (candidate.kind() == SemanticOpKind.BOUNDARY
                        && op.opId().equals(candidate.origin().parentOpId())) {
                    KindPayload.BoundaryPayload boundaryPayload =
                        (KindPayload.BoundaryPayload) candidate.payload();
                    emitBoundaryStart(candidate, slot(boundaryPayload.input()),
                        boundaryPayload.descriptor(), indent + 1);
                    out.append(indent(indent + 1)).append("Object __ep_")
                        .append(candidate.opId().id())
                        .append(" = ")
                        .append(bcheckArgs(boundaryPayload.descriptor(),
                            slot(boundaryPayload.input()))).append(";\n");
                    emitBoundarySuccess(candidate, "__ep_" + candidate.opId().id(),
                        boundaryPayload.descriptor(), indent + 1);
                }
            }
            out.append(indent(indent)).append("exportSurface(")
                .append(javaString(emittingModulePath(op))).append(").write(")
                .append(javaString(payload.name())).append(", ")
                .append(slot(payload.value())).append(");\n");
            emitPlainSuccess(op, indent);
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
         * function callable across a shared/shadow edge: production export
         * transport is the retained layout's artifact ABI, so the record
         * needs no runtime action.
         */
        private void emitExternalEntryRecord(SemanticOp op, int indent) {
            emitStart(op, indent);
            emitPlainSuccess(op, indent);
        }

        /**
         * CALLBACK_INVOKE (E6, D13 host-driven dispatch): one per-unit
         * static entry method the scenario host invokes top-level with
         * scripted arguments. The entry emits its own START (the scripted
         * argument inputs, no parentOpId — the scenario step triggers
         * it), runs the {@code HOST_TO_DEAL} parameter boundaries in
         * one-based order, executes the bound
         * {@link FunctionExecutionBinding} (a DEAL body, or the D15
         * adapter protocol with the leading-M projection), and closes
         * with the op's terminal (SUCCESS with the checked value, or
         * FAILURE with the propagated error). The single
         * {@code DEAL_TO_HOST} return boundary runs by the executed
         * body's {@code RETURN} (its payload names this op as the
         * enclosing invocation). The block walk never runs the entry.
         */
        private void emitCallbackInvoke(SemanticOp op, int indent) {
            KindPayload.CallbackInvokePayload payload =
                (KindPayload.CallbackInvokePayload) op.payload();
            FunctionExecutionBinding binding = unit.functionBindings().get(
                new deal.semantic.ir.FunctionAllocationIdentity(
                    payload.function().id()));
            if (binding == null) {
                throw new IllegalStateException("CALLBACK_INVOKE " + op.opId()
                    + " resolves no FunctionExecutionBinding (producer defect)");
            }
            String entry = "cb" + op.opId().id();
            out.append(indent(indent)).append("public static Object ").append(entry)
                .append("(Object[] __args) {\n");
            if (trace) {
                StringBuilder inputs = new StringBuilder();
                for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                    if (i > 0) {
                        inputs.append(", ");
                    }
                    inputs.append("JvmRuntime.hostAtom(__args[").append(i).append("])");
                }
                out.append(indent(indent)).append("  JvmRuntime.ev(MODULE, ")
                    .append(javaString(opKey(op.opId()))).append(", \"START\", ")
                    .append(javaString(op.kind().name())).append(", ")
                    .append(javaString(op.contract().canonicalDigest()))
                    .append(", \"-\", List.of(").append(inputs)
                    .append("), null, null);\n");
            }
            for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                SemanticOp boundary = opsById.get(payload.parameterBoundaryOpIds().get(i));
                KindPayload.BoundaryPayload boundaryPayload =
                    (KindPayload.BoundaryPayload) boundary.payload();
                String checked = "__cb_" + boundary.opId().id();
                if (trace) {
                    out.append(indent(indent)).append("  JvmRuntime.ev(MODULE, ")
                        .append(javaString(opKey(boundary.opId())))
                        .append(", \"START\", \"BOUNDARY\", ")
                        .append(javaString(boundary.contract().canonicalDigest()))
                        .append(", ")
                        .append(javaString(parentKey(boundary.origin().parentOpId())))
                        .append(", List.of(JvmRuntime.hostAtom(__args[")
                        .append(i).append("])), null, null);\n");
                }
                out.append(indent(indent)).append("  Object ").append(checked)
                    .append(";\n");
                out.append(indent(indent)).append("  try {\n");
                out.append(indent(indent)).append("    ").append(checked)
                    .append(" = ")
                    .append(bcheckArgs(boundaryPayload.descriptor(), "__args[" + i + "]"))
                    .append(";\n");
                out.append(indent(indent)).append("  } catch (JvmRuntime.DealError __be) {\n");
                out.append(indent(indent))
                    .append("    JvmRuntime.DealError __bre = new JvmRuntime.DealError("
                        + "__be.code, __be.msg, ")
                    .append(javaString(originOf(boundary)))
                    .append(", __be.expected, __be.actual, __be.frames, null);\n");
                if (trace) {
                    emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                        "JvmRuntime.errtext(__bre)", indent + 1);
                    emitFailureEvent(op.opId(), op.kind().name(), op,
                        "JvmRuntime.errtext(__bre)", indent + 1);
                }
                out.append(indent(indent)).append("    throw __bre;\n");
                out.append(indent(indent)).append("  }\n");
                if (trace) {
                    out.append(indent(indent)).append("  JvmRuntime.ev(MODULE, ")
                        .append(javaString(opKey(boundary.opId())))
                        .append(", \"SUCCESS\", \"BOUNDARY\", ")
                        .append(javaString(boundary.contract().canonicalDigest()))
                        .append(", ")
                        .append(javaString(parentKey(boundary.origin().parentOpId())))
                        .append(", List.of(), JvmRuntime.atom(")
                        .append(checked).append(", ")
                        .append(javaString(staticKind(boundaryPayload.descriptor())))
                        .append("), null);\n");
                }
            }
            out.append(indent(indent)).append("  Object __res;\n");
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
                    out.append(indent(indent)).append("  JvmRuntime.pushFrame(")
                        .append(javaString(String.valueOf(body.functionId().id())))
                        .append(");\n");
                    out.append(indent(indent)).append("  try {\n");
                    out.append(indent(indent)).append("    __res = ")
                        .append(fnFactory(body.functionId())).append("(")
                        .append(caps).append(").fn.invoke(new Object[]{");
                    for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                        if (i > 0) {
                            out.append(", ");
                        }
                        SemanticOp boundary =
                            opsById.get(payload.parameterBoundaryOpIds().get(i));
                        out.append("__cb_").append(boundary.opId().id());
                    }
                    out.append("});\n");
                    out.append(indent(indent)).append("  } catch (JvmRuntime.DealError __e) {\n");
                    if (trace) {
                        emitFailureEvent(op.opId(), op.kind().name(), op,
                            "JvmRuntime.errtext(__e)", indent + 1);
                    }
                    out.append(indent(indent)).append("    throw __e;\n");
                    out.append(indent(indent)).append("  } finally {\n");
                    out.append(indent(indent)).append("    JvmRuntime.popFrame();\n");
                    out.append(indent(indent)).append("  }\n");
                }
                case FunctionExecutionBinding.AdapterBinding adapter -> {
                    String adapterSlot =
                        slot((ValueId) opsById.get(adapter.adaptOpId()).result());
                    out.append(indent(indent)).append("  try {\n");
                    out.append(indent(indent))
                        .append("    __res = JvmRuntime.invokeAdapter((JvmRuntime.AdapterValue) ")
                        .append(adapterSlot).append(", ")
                        .append(javaString(originOf(op))).append(", new Object[]{");
                    for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                        if (i > 0) {
                            out.append(", ");
                        }
                        SemanticOp boundary =
                            opsById.get(payload.parameterBoundaryOpIds().get(i));
                        out.append("__cb_").append(boundary.opId().id());
                    }
                    out.append("});\n");
                    out.append(indent(indent)).append("  } catch (JvmRuntime.DealError __e) {\n");
                    if (trace) {
                        emitFailureEvent(op.opId(), op.kind().name(), op,
                            "JvmRuntime.errtext(__e)", indent + 1);
                    }
                    out.append(indent(indent)).append("    throw __e;\n");
                    out.append(indent(indent)).append("  }\n");
                }
                case FunctionExecutionBinding.HostFunction host ->
                    emitHostCallbackInvoke(op, payload,
                        hostAbi.requireWrapper(host.hostModuleId().path(),
                            host.exportName()),
                        hostAbi.declaredFunction(host.hostModuleId().path(),
                            host.exportName()), indent);
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
                    emitHostCallbackValue(op, payload, slot(boundary.input()), indent);
                }
                case FunctionExecutionBinding.IntrinsicFunction intrinsic ->
                    throw new IllegalStateException("CALLBACK_INVOKE " + op.opId()
                        + " resolves the " + intrinsic.kind() + " intrinsic carrier"
                        + " (the intrinsic carrier is the function-typed-value"
                        + " child's — a fail-closed producer defect)");
                case FunctionExecutionBinding.ExternalFunction external ->
                    throw new IllegalStateException("CALLBACK_INVOKE " + op.opId()
                        + " resolves an external binding outside the statically-"
                        + "resolved slice (a fail-closed producer defect)");
                default -> throw new IllegalStateException("CALLBACK_INVOKE "
                    + op.opId() + " resolves a binding outside the statically-resolved "
                    + "slice: " + binding);
            }
            if (trace) {
                out.append(indent(indent)).append("  JvmRuntime.ev(MODULE, ")
                    .append(javaString(opKey(op.opId()))).append(", \"SUCCESS\", ")
                    .append(javaString(op.kind().name())).append(", ")
                    .append(javaString(op.contract().canonicalDigest()))
                    .append(", \"-\", List.of(), JvmRuntime.atom(__res, ")
                    .append(javaString(staticKind(payload.descriptor().returnType())))
                    .append("), null);\n");
            }
            out.append(indent(indent)).append("  return __res;\n");
            out.append(indent(indent)).append("}\n");
        }

        private void emitHostCallbackInvoke(SemanticOp op,
                KindPayload.CallbackInvokePayload payload, String wrapper,
                Type.Func declared, int indent) {
            StringBuilder call = new StringBuilder(wrapper).append('(');
            for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                SemanticOp boundary = opsById.get(payload.parameterBoundaryOpIds().get(i));
                call.append("__cb_").append(boundary.opId().id()).append(", ");
            }
            call.append(originArgs(op)).append(')');
            out.append(indent(indent)).append("  try {\n");
            if (declared.returnType() instanceof Type.Null) {
                // The emitted wrapper of a declared null return is void:
                // the invocation is a statement and the callback value is
                // the language null (the op's own return cell below checks
                // it).
                out.append(indent(indent)).append("    ").append(call).append(";\n");
                out.append(indent(indent)).append("    __res = null;\n");
            } else {
                out.append(indent(indent)).append("    __res = ").append(call)
                    .append(";\n");
            }
            out.append(indent(indent))
                .append("  } catch (JvmRuntime.DealError __e) {\n");
            if (trace) {
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "JvmRuntime.errtext(__e)", indent + 2);
            }
            out.append(indent(indent)).append("    throw __e;\n");
            out.append(indent(indent)).append("  }\n");
            emitCallbackReturnCell(op, payload, indent);
        }

        /**
         * The host-materialized {@code CALLBACK_INVOKE} arm: the value the
         * callback record's producing host crossing published is invoked
         * through its production function carrier with the same declared
         * cells and the callback op's own return projection.
         */
        private void emitHostCallbackValue(SemanticOp op,
                KindPayload.CallbackInvokePayload payload, String value, int indent) {
            out.append(indent(indent)).append("  try {\n");
            out.append(indent(indent)).append("    __res = ((JvmRuntime.FunctionValue) ")
                .append(value).append(").fn.invoke(new Object[]{");
            for (int i = 0; i < payload.parameterBoundaryOpIds().size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                SemanticOp boundary = opsById.get(payload.parameterBoundaryOpIds().get(i));
                out.append("__cb_").append(boundary.opId().id());
            }
            out.append("});\n");
            out.append(indent(indent))
                .append("  } catch (JvmRuntime.DealError __e) {\n");
            if (trace) {
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "JvmRuntime.errtext(__e)", indent + 2);
            }
            out.append(indent(indent)).append("    throw __e;\n");
            out.append(indent(indent)).append("  }\n");
            emitCallbackReturnCell(op, payload, indent);
        }

        /** The callback op's own single return boundary cell, when recorded. */
        private void emitCallbackReturnCell(SemanticOp op,
                KindPayload.CallbackInvokePayload payload, int indent) {
            if (payload.returnBoundaryOpId() == null) {
                return;
            }
            SemanticOp boundary = opsById.get(payload.returnBoundaryOpId());
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) boundary.payload();
            emitBoundaryStartAtom(boundary, "JvmRuntime.atom(__res, "
                + javaString(staticKind(boundaryPayload.descriptor())) + ")", indent);
            String checked = "__cbr_" + boundary.opId().id();
            out.append(indent(indent)).append("  Object ").append(checked)
                .append(";\n");
            out.append(indent(indent)).append("  try {\n");
            out.append(indent(indent)).append("    ").append(checked)
                .append(" = ")
                .append(bcheckArgs(boundaryPayload.descriptor(), "__res"))
                .append(";\n");
            out.append(indent(indent))
                .append("  } catch (JvmRuntime.DealError __be) {\n");
            out.append(indent(indent))
                .append("    JvmRuntime.DealError __bre = new JvmRuntime.DealError("
                    + "__be.code, __be.msg, ")
                .append(javaString(originOf(boundary)))
                .append(", __be.expected, __be.actual, __be.frames, null);\n");
            if (trace) {
                emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                    "JvmRuntime.errtext(__bre)", indent + 2);
                emitFailureEvent(op.opId(), op.kind().name(), op,
                    "JvmRuntime.errtext(__bre)", indent + 2);
            }
            out.append(indent(indent)).append("    throw __bre;\n");
            out.append(indent(indent)).append("  }\n");
            emitBoundarySuccess(boundary, checked, boundaryPayload.descriptor(),
                indent + 1);
            out.append(indent(indent)).append("  __res = ").append(checked)
                .append(";\n");
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
        private void emitTokenSuccess(SemanticOp op, String atomExpr, int indent) {
            emitSuccessEvent(op, op.kind().name(), atomExpr + ", null", indent);
        }

        /** A boundary START event with a raw input atom expression. */
        private void emitBoundaryStartAtom(SemanticOp boundary, String atomExpr,
                                           int indent) {
            if (!trace) {
                return;
            }
            out.append(indent(indent)).append("JvmRuntime.ev(MODULE, ")
                .append(javaString(opKey(boundary.opId()))).append(", \"START\", ")
                .append("\"BOUNDARY\", ")
                .append(javaString(boundary.contract().canonicalDigest())).append(", ")
                .append(javaString(parentKey(boundary.origin().parentOpId())))
                .append(", List.of(").append(atomExpr).append("), null, null);\n");
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
         * DEAL body task is a {@code CompletableFuture} completed by the
         * module's run-local single-thread serial executor (never the JDK
         * common pool); an adapter-over-async task resolves the D15
         * source, checks the source signature, and executes the nested
         * source op; a host operation starts through the artifact's
         * host-seam entry (bad handle → the op's own E8010
         * {@code ASYNC_OPERATION_HANDLE}); an external operation starts
         * through the callee artifact's async-entry dispatch entry. The
         * op's terminal publishes the canonical/alias token atom.
         */
        private void emitAsyncStart(SemanticOp op, int indent) {
            KindPayload.AsyncStartPayload payload =
                (KindPayload.AsyncStartPayload) op.payload();
            AsyncTokenId token = (AsyncTokenId) op.result();
            emitStart(op, indent);
            // The argument expressions per position: the boundary-checked
            // values (RUN) or the raw operand slots (ELIDED_BY_ADAPTER).
            List<String> args = new ArrayList<>();
            if (payload.parameterBoundaryMode() == ParameterBoundaryMode.RUN) {
                for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                    SemanticOp boundary = opsById.get(boundaryId);
                    KindPayload.BoundaryPayload boundaryPayload =
                        (KindPayload.BoundaryPayload) boundary.payload();
                    emitBoundaryStart(boundary, slot(boundaryPayload.input()),
                        boundaryPayload.descriptor(), indent);
                    // The declared parameter cell's own failure: the
                    // boundary FAILURE (and the owning ASYNC_START's)
                    // publishes at the boundary's origin and the error
                    // re-originates there — identical to the oracle's
                    // boundary-child pair and the Lua prelude's arm.
                    String checked = "__sa_" + boundary.opId().id();
                    out.append(indent(indent)).append("Object ").append(checked)
                        .append(";\n");
                    out.append(indent(indent)).append("try {\n");
                    out.append(indent(indent + 1)).append(checked).append(" = ")
                        .append(bcheckArgs(boundaryPayload.descriptor(),
                            slot(boundaryPayload.input()))).append(";\n");
                    out.append(indent(indent))
                        .append("} catch (JvmRuntime.DealError __be) {\n");
                    out.append(indent(indent + 1))
                        .append("JvmRuntime.DealError __bre = new "
                            + "JvmRuntime.DealError(__be.code, __be.msg, ")
                        .append(javaString(originOf(boundary)))
                        .append(", __be.expected, __be.actual, __be.frames, null);\n");
                    emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                        "JvmRuntime.errtext(__bre)", indent + 1);
                    emitFailureEvent(op.opId(), op.kind().name(), op,
                        "JvmRuntime.errtext(__bre)", indent + 1);
                    out.append(indent(indent + 1)).append("throw __bre;\n");
                    out.append(indent(indent)).append("}\n");
                    emitBoundarySuccess(boundary, checked,
                        boundaryPayload.descriptor(), indent);
                    args.add(checked);
                }
            } else {
                for (ValueId operand : op.operands()) {
                    args.add(slot(operand));
                }
            }
            if (payload.callee() instanceof KindPayload.CallCallee.Dynamic dynamic) {

                emitDynamicAsyncStart(op, payload, dynamic, token, args, indent);
                emitTokenSuccess(op, javaString(tokenAtom(token)), indent);
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
                    out.append(indent(indent)).append("JvmRuntime.startBodyTask(")
                        .append(token.tokenId()).append(", \"DEAL_BODY_TASK\", () -> {\n");
                    out.append(indent(indent + 1)).append("String __prevM = "
                        + "JvmRuntime.currentModule();\n");
                    out.append(indent(indent + 1)).append("JvmRuntime.setModule(MODULE);\n");
                    out.append(indent(indent + 1)).append("JvmRuntime.pushFrame(")
                        .append(javaString(String.valueOf(body.functionId().id())))
                        .append(");\n");
                    // The task's body execution is the async counterpart of
                    // the synchronous invocation: a recursive await of the
                    // same body runs the nested task inline (the serial
                    // drain) while the enclosing invocation is still live,
                    // so the callee body's private slots are saved before the
                    // body runs and restored on the success and failure
                    // paths. The enclosing invocation may itself have been
                    // started by a dynamic-dispatch task or an entry, so the
                    // save/restore is unconditional.
                    StateSlot savedState = emitAsyncInvocationStateSave(
                        body.functionId(), op.opId(), indent + 1);
                    out.append(indent(indent + 1)).append("try {\n");
                    out.append(indent(indent + 2)).append("return ");
                    if (payload.callee()
                            instanceof KindPayload.CallCallee.Indirect indirect) {
                        // The value-carried async invocation (a nested group
                        // member with creation-site captures): the closure
                        // value the binding holds runs its own invoker, whose
                        // captured cells are the ones its creation published —
                        // never a call-site re-resolution of a per-creation
                        // incarnation.
                        out.append("((JvmRuntime.FunctionValue) ")
                            .append(slot(indirect.callee()))
                            .append(").fn.invoke(new Object[]{");
                    } else {
                        out.append(fnFactory(body.functionId())).append("(").append(caps)
                            .append(").fn.invoke(new Object[]{");
                    }
                    out.append(String.join(", ", args)).append("});\n");
                    out.append(indent(indent + 1)).append("} finally {\n");
                    out.append(indent(indent + 2)).append("JvmRuntime.popFrame();\n");
                    out.append(indent(indent + 2)).append("JvmRuntime.setModule(__prevM);\n");
                    emitAsyncInvocationStateRestore(body.functionId(), savedState,
                        indent + 2);
                    out.append(indent(indent + 1)).append("}\n");
                    out.append(indent(indent)).append("});\n");
                }
                case FunctionExecutionBinding.AdapterBinding adapter -> {
                    // The outer adapter-over-async task (zero return
                    // boundaries of its own): the D15 source resolution
                    // and source-signature check, then the nested source
                    // op's full emission (its task queues under the FIFO
                    // drain — the outer task completes after it).
                    SemanticOp nested = nestedAsyncStartOf(op);
                    out.append(indent(indent)).append("JvmRuntime.startBodyTask(")
                        .append(token.tokenId()).append(", \"DEAL_BODY_TASK\", () -> {\n");
                    out.append(indent(indent + 1)).append("Object __src = "
                        + "JvmRuntime.adapterSource((JvmRuntime.AdapterValue) ")
                        .append(slot((ValueId) opsById.get(adapter.adaptOpId()).result()))
                        .append(");\n");
                    out.append(indent(indent + 1)).append("JvmRuntime.fnCheck(__src, ")
                        .append(javaString(adapter.sourceSignature().canonicalSpecText()))
                        .append(", ").append(javaString(originOf(op))).append(");\n");
                    emitAsyncStart(nested, indent + 1);
                    out.append(indent(indent + 1)).append("return null;\n");
                    out.append(indent(indent)).append("});\n");
                }
                case FunctionExecutionBinding.HostFunction host -> {
                    if (hostAbi == null) {

                        emitAsyncHostSeamStart(op, indent, token,
                            host.hostModuleId().path(), host.exportName(), args);
                    } else {
                        emitAsyncHostStart(op, indent, token, host.hostModuleId(),
                            host.exportName(), args);
                    }
                }
                case FunctionExecutionBinding.HostFunctionValue hostValue -> {
                    if (hostAbi == null) {
                        emitAsyncHostSeamStart(op, indent, token,
                            hostValue.hostModuleId().path(),
                            "@value#" + hostValue.materializingBoundaryOpId().id(), args);
                    } else {
                        emitAsyncHostValueStart(op, indent, token, hostValue, args);
                    }
                }
                case FunctionExecutionBinding.ExternalFunction external ->
                    emitAsyncExternalStart(op, indent, token, payload.externalAsyncLink(),
                        args);
                case FunctionExecutionBinding.IntrinsicFunction intrinsic -> {

                    if (args.size() != intrinsic.descriptor().paramTypes().size()) {
                        throw new IllegalStateException("ASYNC_START " + op.opId()
                            + " carries " + args.size() + " argument(s) for the '"
                            + intrinsic.kind() + "' intrinsic's "
                            + intrinsic.descriptor().paramTypes().size()
                            + " declared parameter(s) (producer defect)");
                    }
                    out.append(indent(indent)).append("JvmRuntime.startBodyTask(")
                        .append(token.tokenId())
                        .append(", \"DEAL_BODY_TASK\", () -> ")
                        .append(intrinsic.kind() == IntrinsicKind.INT_CONVERT
                            ? "JvmRuntime.intConv(" : "JvmRuntime.numConv(")
                        .append(args.get(0)).append(", ")
                        .append(javaString(op.kind().name())).append(", ")
                        .append(javaString(staticKind(
                            intrinsic.descriptor().paramTypes().get(0))))
                        .append(", ").append(intrinsicContextArgs(op))
                        .append("));\n");
                }
                case FunctionExecutionBinding.DynamicFunctionValue dynamic ->
                    throw new IllegalStateException("ASYNC_START " + op.opId()
                        + " resolves the dynamic function value produced by "
                        + dynamic.materializingOpId() + " outside the dynamic arm (a "
                        + "DynamicFunctionValue names no execution class and is never a "
                        + "statically resolved callee — producer defect)");
            }
            emitTokenSuccess(op, javaString(tokenAtom(token)), indent);
        }

        private void emitAsyncHostSeamStart(SemanticOp op, int indent,
                                            AsyncTokenId token, String module,
                                            String export, List<String> args) {
            KindPayload.AsyncStartPayload payload =
                (KindPayload.AsyncStartPayload) op.payload();
            String label = payload.hostOperationLabel();
            out.append(indent(indent)).append("JvmRuntime.effect(\"ASYNC_START_OP\", ")
                .append(javaString(label)).append(");\n");
            out.append(indent(indent)).append("if (JvmRuntime.HOST_ASYNC == null) {\n");
            out.append(indent(indent + 1))
                .append("throw new IllegalStateException(\"an async host start has no "
                    + "deterministic host seam (the scenario host drives it)\");\n");
            out.append(indent(indent)).append("}\n");
            out.append(indent(indent)).append("String __bound = "
                + "JvmRuntime.HOST_ASYNC.startAsync(")
                .append(javaString(module)).append(", ").append(javaString(export))
                .append(", ").append(javaString(label)).append(", new Object[]{")
                .append(String.join(", ", args)).append("});\n");
            out.append(indent(indent)).append("if (__bound == null) {\n");
            out.append(indent(indent + 1)).append("JvmRuntime.DealError __e = "
                + "JvmRuntime.arm(deal.semantic.ir.FailureArmId.ASYNC_SHAPE, "
                + "java.util.Map.of(\"actual\", \"nothing\"), ")
                .append(javaString(originOf(op)))
                .append(", \"async operation\", \"nothing\");\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "JvmRuntime.errtext(__e)",
                indent + 1);
            out.append(indent(indent + 1)).append("throw __e;\n");
            out.append(indent(indent)).append("}\n");
            out.append(indent(indent)).append("JvmRuntime.startHostTask(")
                .append(token.tokenId()).append(", ").append(javaString(label))
                .append(");\n");
        }

        private void emitAsyncHostStart(SemanticOp op, int indent, AsyncTokenId token,
                                        ModuleId moduleId, String exportName,
                                        List<String> args) {
            KindPayload.AsyncStartPayload payload =
                (KindPayload.AsyncStartPayload) op.payload();
            String label = payload.hostOperationLabel();
            if (hostAbi == null) {
                throw new IllegalStateException("ASYNC_START " + op.opId()
                    + " resolves the host export '" + moduleId.path() + "."
                    + exportName + "' but the session carries no host ABI emission"
                    + " surface (a producer defect; only the production project"
                    + " entry carries the compile's declaration surface)");
            }
            String wrapper = hostAbi.requireWrapper(moduleId.path(), exportName);
            StringBuilder call = new StringBuilder(wrapper).append('(');
            for (String arg : args) {
                call.append(arg).append(", ");
            }
            call.append(originArgs(op)).append(')');
            emitAsyncHostOperation(op, indent, token, label, call.toString());
        }

        /**
         * The {@code HostFunctionValue} async arm: the value materialized
         * at its producing host crossing is invoked through the production
         * function carrier the crossing's declared-return projection
         * published (the surface entry's own bridging wrapper), whose
         * declared-async shape check carries the pinned projection — the
         * same route as the sync{\@code HostFunctionValue} arm.
         */
        private void emitAsyncHostValueStart(SemanticOp op, int indent,
                                             AsyncTokenId token,
                                             FunctionExecutionBinding.HostFunctionValue hostValue,
                                             List<String> args) {
            KindPayload.AsyncStartPayload payload =
                (KindPayload.AsyncStartPayload) op.payload();
            String label = payload.hostOperationLabel();
            if (hostAbi == null) {
                throw new IllegalStateException("ASYNC_START " + op.opId()
                    + " resolves the host-materialized function value of module '"
                    + hostValue.hostModuleId().path() + "' but the session carries"
                    + " no host ABI emission surface (a producer defect)");
            }
            SemanticOp crossing = opsById.get(hostValue.materializingBoundaryOpId());
            if (crossing == null
                    || !(crossing.payload() instanceof KindPayload.BoundaryPayload boundary)) {
                throw new IllegalStateException("ASYNC_START " + op.opId()
                    + " names the materializing host crossing "
                    + hostValue.materializingBoundaryOpId()
                    + ", which is not a boundary op of the emitted closure"
                    + " (a producer defect)");
            }
            StringBuilder call = new StringBuilder("((JvmRuntime.FunctionValue) ")
                .append(slot(boundary.input()))
                .append(").fn.invoke(new java.lang.Object[]{ ");
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) {
                    call.append(", ");
                }
                call.append(args.get(i));
            }
            call.append(" })");
            emitAsyncHostOperation(op, indent, token, label, call.toString());
        }

        /**
         * The shared invocation half of one async host start: the
         * declared-async shape check (inside the emitted wrapper) under the
         * op's own failure path, then the operation handle bound to the
         * canonical token through the production host-task registration.
         */
        private void emitAsyncHostOperation(SemanticOp op, int indent,
                                            AsyncTokenId token, String label,
                                            String invocation) {
            out.append(indent(indent)).append("JvmRuntime.effect(\"ASYNC_START_OP\", ")
                .append(javaString(label)).append(");\n");
            String handle = "__ha_" + op.opId().id();
            out.append(indent(indent)).append("Object ").append(handle)
                .append(";\n");
            out.append(indent(indent)).append("try {\n");
            out.append(indent(indent + 1)).append(handle).append(" = ")
                .append(invocation).append(";\n");
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __e) {\n");
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(__e)", indent + 1);
            out.append(indent(indent + 1)).append("throw __e;\n");
            out.append(indent(indent)).append("}\n");
            out.append(indent(indent)).append("JvmRuntime.startHostTask(")
                .append(token.tokenId()).append(", ").append(javaString(label))
                .append(", ").append(handle).append(");\n");
        }

        private void emitAsyncExternalStart(SemanticOp op, int indent, AsyncTokenId token,
                                            ExternalAsyncLink link, List<String> args) {
            if (link == null) {
                throw new IllegalStateException("ASYNC_START(EXTERNAL) without "
                    + "its ExternalAsyncLink (producer defect)");
            }
            if (projectSession) {
                // The one production class carries the entry method: the
                // reference resolves locally (and fails closed when the
                // link names no emitted entry).
                resolveExternalAsyncEntry(op, link);
                out.append(indent(indent)).append("ae")
                    .append(link.calleeTokenId().tokenId()).append("(")
                    .append(javaString(opKey(op.opId())))
                    .append(", false, new Object[]{")
                    .append(String.join(", ", args)).append("});\n");
            } else {
                out.append(indent(indent))
                    .append(sharedClassName(link.calleeModuleId().path()))
                    .append(".ae").append(link.calleeTokenId().tokenId()).append("(")
                    .append(javaString(opKey(op.opId())))
                    .append(", false, new Object[]{")
                    .append(String.join(", ", args)).append("});\n");
            }
        }

        /**
         * AWAIT — the completion position (D13 step 6): the deterministic
         * FIFO drain first (the serial executor's join), then the
         * canonical referent's completion. A production host operation
         * joins its registered operation and never reads the seam; a
         * seam-registered host operation completes through the host seam
         * (the ordered ASYNC_COMPLETE_* effects); a failed operation
         * publishes the identical error — never a re-check or a
         * synthesized copy — and a completed value crosses the single
         * {@code ASYNC_COMPLETION} boundary at the await site (a boundary
         * failure is re-originated at that site and publishes the
         * boundary's FAILURE beside the await op's, exactly the oracle's
         * boundary-child pair).
         */
        private void emitAwait(SemanticOp op, int indent) {
            KindPayload.AwaitPayload payload = (KindPayload.AwaitPayload) op.payload();
            long canonicalId = canonicalReferent(payload.token());
            SemanticOp boundary = opsById.get(payload.completionBoundaryOpId());
            KindPayload.BoundaryPayload boundaryPayload =
                (KindPayload.BoundaryPayload) boundary.payload();
            emitStart(op, indent);
            String awaited = "__av_" + op.opId().id();
            out.append(indent(indent)).append("Object ").append(awaited)
                .append(";\n");
            out.append(indent(indent)).append("try {\n");
            out.append(indent(indent + 1)).append(awaited)
                .append(" = JvmRuntime.awaitTask(")
                .append(canonicalId).append(", ").append(javaString(originOf(op)))
                .append(");\n");
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __e) {\n");
            emitFailureEvent(op.opId(), op.kind().name(), op, "JvmRuntime.errtext(__e)",
                indent + 1);
            out.append(indent(indent + 1)).append("throw __e;\n");
            out.append(indent(indent)).append("}\n");
            // The single ASYNC_COMPLETION boundary at the await site: a
            // host completion atomizes by its runtime carrier; a DEAL
            // body value atomizes by the declared descriptor.
            if (canonicalOwnerOf(payload.token()) == AsyncTokenOwner.HOST_OPERATION) {
                emitBoundaryStartAtom(boundary, "JvmRuntime.hostAtom(" + awaited + ")",
                    indent);
            } else {
                emitBoundaryStart(boundary, awaited, boundaryPayload.descriptor(),
                    indent);
            }
            String checked = "__avc_" + op.opId().id();
            out.append(indent(indent)).append("Object ").append(checked).append(";\n");
            out.append(indent(indent)).append("try {\n");
            out.append(indent(indent + 1)).append(checked).append(" = ")
                .append(bcheckCompletionArgs(boundaryPayload.descriptor(), awaited))
                .append(";\n");
            out.append(indent(indent)).append("} catch (JvmRuntime.DealError __be) {\n");
            out.append(indent(indent + 1)).append("JvmRuntime.DealError __bre = new "
                + "JvmRuntime.DealError(__be.code, __be.msg, ")
                .append(javaString(originOf(boundary)))
                .append(", __be.expected, __be.actual, __be.frames, null);\n");
            emitFailureEvent(boundary.opId(), "BOUNDARY", boundary,
                "JvmRuntime.errtext(__bre)", indent + 1);
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(__bre)", indent + 1);
            out.append(indent(indent + 1)).append("throw __bre;\n");
            out.append(indent(indent)).append("}\n");
            emitBoundarySuccess(boundary, checked, boundaryPayload.descriptor(),
                indent);
            out.append(indent(indent)).append(slot((ValueId) op.result()))
                .append(" = ").append(checked).append(";\n");
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }

        private void emitAsyncEntry(SemanticOp op, int indent,
                                    LoweredModuleUnit owner) {
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
            out.append(indent(indent)).append("public static Object ae")
                .append(op.opId().id())
                .append("(String __parent, boolean __drive, Object[] __args) {\n");
            if (trace) {
                // The entry's own events pass the owning module literal:
                // a cross-module caller invokes the entry from the
                // caller's context and the shared mutable MODULE would
                // otherwise tag the entry's events with the caller's
                // module.
                out.append(indent(indent + 1)).append("JvmRuntime.ev(")
                    .append(javaString(ownerPath)).append(", ")
                    .append(javaString(opKey(op.opId()))).append(", \"START\", ")
                    .append(javaString(op.kind().name())).append(", ")
                    .append(javaString(op.contract().canonicalDigest()))
                    .append(", __parent, List.of(), null, null);\n");
            }
            out.append(indent(indent + 1)).append("JvmRuntime.startBodyTask(")
                .append(op.opId().id()).append(", \"DEAL_BODY_TASK\", () -> {\n");
            out.append(indent(indent + 2)).append("String __prevM = "
                + "JvmRuntime.currentModule();\n");
            out.append(indent(indent + 2)).append("String __prevModule = MODULE;\n");
            out.append(indent(indent + 2)).append("MODULE = ")
                .append(javaString(ownerPath)).append(";\n");
            out.append(indent(indent + 2)).append("JvmRuntime.setModule(MODULE);\n");
            out.append(indent(indent + 2)).append("JvmRuntime.pushFrame(")
                .append(javaString(String.valueOf(payload.function().id())))
                .append(");\n");
            out.append(indent(indent + 2)).append("try {\n");
            out.append(indent(indent + 3)).append("return ")
                .append(fnFactory(payload.function())).append("(").append(caps)
                .append(").fn.invoke(__args);\n");
            out.append(indent(indent + 2)).append("} finally {\n");
            out.append(indent(indent + 3)).append("JvmRuntime.popFrame();\n");
            out.append(indent(indent + 3)).append("MODULE = __prevModule;\n");
            out.append(indent(indent + 3)).append("JvmRuntime.setModule(__prevM);\n");
            out.append(indent(indent + 2)).append("}\n");
            out.append(indent(indent + 1)).append("});\n");
            if (trace) {
                out.append(indent(indent + 1)).append("JvmRuntime.ev(")
                    .append(javaString(ownerPath)).append(", ")
                    .append(javaString(opKey(op.opId()))).append(", \"SUCCESS\", ")
                    .append(javaString(op.kind().name())).append(", ")
                    .append(javaString(op.contract().canonicalDigest()))
                    .append(", __parent, List.of(), \"tok:").append(op.opId().id())
                    .append(":DEAL_BODY_TASK\", null);\n");
            }
            out.append(indent(indent + 1)).append("if (__drive) {\n");
            out.append(indent(indent + 2)).append("return JvmRuntime.awaitTask(")
                .append(op.opId().id()).append(", ")
                .append(javaString(originOf(op))).append(");\n");
            out.append(indent(indent + 1)).append("}\n");
            out.append(indent(indent + 1)).append("return null;\n");
            out.append(indent(indent)).append("}\n");
        }

        /**
         * ENTRY_INVOKE — delegates exactly one CALL(DIRECT) to main
         * (its owned child) and exits after the terminal. A delegated
         * failure publishes the ENTRY_INVOKE FAILURE terminal (the oracle's
         * own projection) before the error propagates to the module-init
         * wrapper's terminal.
         */
        private void emitEntryInvoke(SemanticOp op, int indent) {
            emitStart(op, indent);
            out.append(indent(indent)).append("try {\n");
            for (SemanticOp candidate : opsById.values()) {
                if (candidate.kind() == SemanticOpKind.CALL
                        && op.opId().equals(candidate.origin().parentOpId())) {
                    emitCall(candidate, indent + 1);
                }
            }
            out.append(indent(indent))
                .append("} catch (JvmRuntime.DealError __entryErr) {\n");
            emitFailureEvent(op.opId(), op.kind().name(), op,
                "JvmRuntime.errtext(__entryErr)", indent + 1);
            out.append(indent(indent)).append("  throw __entryErr;\n");
            out.append(indent(indent)).append("}\n");
            emitPlainSuccess(op, indent);
        }

        private void emitModuleImport(SemanticOp op, int indent) {
            KindPayload.ModuleImportPayload payload =
                (KindPayload.ModuleImportPayload) op.payload();
            emitStart(op, indent);
            if (payload.kind() == deal.semantic.ir.ModuleImportKind.HOST
                    && hostAbi != null) {

                SourceSpan span = op.origin().span();
                if (span == null) {
                    throw new IllegalStateException("the host import " + op.opId()
                        + " carries no source span: the pinned E8011 origin"
                        + " needs the import statement's own origin (a producer"
                        + " defect)");
                }
                out.append(indent(indent))
                    .append(JvmHostAbiEmission.loadEntry(JvmHostAbiEmission.key(
                        payload.resolvedModule().path())))
                    .append('(').append(javaString(op.origin().sourceId()))
                    .append(", ").append(span.startLine()).append(", ")
                    .append(span.startColumn()).append(");\n");
            }
            emitAliasCellCompletion(payload, indent);
            emitPlainSuccess(op, indent);
        }

        /**
         * The {@code MODULE_IMPORT} completion write (K15 item 2): every
         * alias cell named by the payload receives the module's namespace
         * value — the module-identity-keyed surface through the one
         * {@code exportSurface} accessor, the one surface object the
         * module's publication or its single load created — in the cell's
         * own publication discipline. The write runs after the
         * kind-specific load, so a HOST/FFI alias holds the loaded table.
         */
        private void emitAliasCellCompletion(KindPayload.ModuleImportPayload payload,
                                             int indent) {
            if (payload.aliasCells().isEmpty()) {
                return;
            }
            String surface = "exportSurface("
                + javaString(payload.resolvedModule().path()) + ")";
            for (BindingId aliasCell : payload.aliasCells()) {
                BindingCellKind kind = cellKindOf(aliasCell, 0);
                if (kind == BindingCellKind.SHARED_CELL) {
                    out.append(indent(indent)).append("((Object[]) ")
                        .append(cell(aliasCell, 0)).append(")[0] = ")
                        .append(surface).append(";\n");
                } else {
                    out.append(indent(indent)).append(cell(aliasCell, 0))
                        .append(" = ").append(surface).append(";\n");
                }
            }
        }

        /**
         * The cataloged callable surfaces of the session's STDLIB imports
         * (M4/K15 item 1): one surface entry per catalog row of every
         * imported STDLIB module, in catalog order, created through the one
         * memoized accessor ({@code JvmRuntime.stdlibCallable}) — the
         * whole-surface population and the read resolve the identical
         * carrier per row, and the surface is written only by this
         * session-scoped catalog memoization (never re-derived per read).
         */
        private void emitStdlibSurfacePopulation(int indent) {
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
                for (StdlibFunctionCatalog.Entry row : rows) {
                    out.append(indent(indent)).append("exportSurface(")
                        .append(javaString(modulePath)).append(").write(")
                        .append(javaString(row.exportName()))
                        .append(", JvmRuntime.stdlibCallable(")
                        .append(stdlibRowArgs(row)).append("));\n");
                }
            }
        }

        /**
         * The closed catalog rows of one imported stdlib module, in catalog
         * order (the pinned declaration order of the one catalog authority);
         * an unimported or unknown module has none.
         */
        private static List<StdlibFunctionCatalog.Entry> stdlibRowsOf(String modulePath) {
            List<StdlibFunctionCatalog.Entry> rows = new java.util.ArrayList<>();
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
         * ({@code JvmRuntime.stdlibCallable(module, name, rowTag, signature,
         * spec)}): the row's declared descriptor text and its canonical spec
         * text — the row's declared signature, never the reading site's.
         */
        private static String stdlibRowArgs(StdlibFunctionCatalog.Entry row) {
            return javaString(row.modulePath()) + ", " + javaString(row.exportName())
                + ", " + javaString(row.function().name()) + ", "
                + javaString(descriptorText(row.declaredDescriptor())) + ", "
                + javaString(row.declaredDescriptor().canonicalSpecText());
        }

        private void emitExportRead(SemanticOp op, int indent) {
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
            if (kind == ModuleImportKind.COMPILED
                    && units.containsKey(payload.module())) {
                requirePublishedExport(op.opId(), payload.module(), payload.name());
            }
            emitStart(op, indent);
            out.append(indent(indent)).append(slot((ValueId) op.result()));
            if (kind == ModuleImportKind.COMPILED || kind == ModuleImportKind.HOST) {
                out.append(" = exportSurface(")
                    .append(javaString(payload.module().path())).append(").read(")
                    .append(javaString(payload.name())).append(");\n");
            } else if (kind == ModuleImportKind.STDLIB) {
                // The read publishes the catalog row's callable carrier
                // itself (M4): the memoized accessor the whole-surface
                // population uses, so the read and the surface hold the
                // identical object and the read never builds a per-read
                // value.
                out.append(" = JvmRuntime.stdlibCallable(")
                    .append(stdlibRowArgs(stdlibRowOf(payload.module().path(),
                        payload.name()))).append(");\n");
            } else {

                String carrier = registeredIntrinsicCarrierExpr((ValueId) op.result());
                out.append(" = ")
                    .append(carrier == null ? exportPlaceholderCarrier() : carrier)
                    .append(";\n");
            }
            emitResultSuccess(op, slot((ValueId) op.result()),
                (RuntimeDescriptor) op.resultType(), indent);
        }
    }
}
