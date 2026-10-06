package deal.codegen.jvm;

import deal.ast.*;
import deal.ast.ArrayType;
import deal.ast.FunctionType;
import deal.ast.NullableType;
import deal.checker.CheckResult;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.codegen.HostModuleDeclarations;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalClassIdentityIndex;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.module.CompilerClassDefaultEntry;
import deal.module.CompilerClassDefaultPlan;
import deal.module.DefaultRuntimeAbiVersion;
import deal.module.ModuleIdentityResolver;
import deal.module.PlannedDefaultClass;
import deal.module.ResolvedDefaultExpression;
import deal.module.RuntimeClassDefaultPlan;
import deal.module.RuntimeDefaultEvaluator;
import deal.module.RuntimeDefaultPlanLowering;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.CompilerDiagnostic;
import deal.semantic.ir.SemanticProfile;
import deal.types.Type;
import deal.types.Types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public final class JvmBackend {

    public record JvmCodegenResult(String className, String source,
                                   List<CompilerDiagnostic> diagnostics,
                                   boolean int32Mode,
                                   List<RuntimeClassDefaultPlan> runtimePlans) {

        /** Backward-compatible four-component constructor: the runtime
         * plan realization list is empty (no published plan consumed —
         * the standalone entry points). */
        public JvmCodegenResult(String className, String source,
                                List<CompilerDiagnostic> diagnostics,
                                boolean int32Mode) {
            this(className, source, diagnostics, int32Mode, List.of());
        }

        public JvmCodegenResult {
            Objects.requireNonNull(className, "className must not be null");
            Objects.requireNonNull(source, "source must not be null");
            diagnostics = List.copyOf(diagnostics);
            runtimePlans = List.copyOf(runtimePlans);
        }

        /** True when at least one error-level diagnostic was recorded. */
        public boolean hasErrors() {
            return diagnostics.stream()
                .anyMatch(d -> "error".equals(d.severity()));
        }
    }

    public static final String ASYNC_EXPORT_HOST_ARG = "$asyncExportHost";

    public static final String ASYNC_EXPORT_HOST_CLASS_SUFFIX =
        "$AsyncExportHost";

    public static final String ASYNC_EXPORT_RESULT_PREFIX =
        "DEAL_ASYNC_EXPORT_RESULT:";

    // =========================================================================
    // State
    // =========================================================================

    private final Map<ExpressionNode, Type> typeMap;
    private final SymbolTable symbols;
    private final String sourcePath;
    private final String modulePath;

    // v1.2 identity carriage (descriptor-identity-propagation D1): class
    // descriptor text comes only from
    // index.descriptorTextFor(identity); the module-path classification
    // (the module-identity layer's own surface) builds the local
    // classes' identities and routes imported identities back to their
    // private wiring paths.  Descriptor text is never computed from the
    // dotted module path.
    private final CanonicalClassIdentityIndex identityIndex;
    private final Function<String, CanonicalModuleIdentity> moduleIdentities;

    /** The static identity index of the public static
     * {@link #typeDescriptor(Type)} surface: descriptorTextFor projects
     * from the identity carriers, so one empty-classification index
     * serves every identity. */
    private static final CanonicalClassIdentityIndex STATIC_DESCRIPTOR_INDEX =
        ModuleIdentityResolver.buildIndex(Map.of(
            "", CanonicalModuleIdentity.BuiltinModule.INSTANCE));

    /**
     * The one canonical descriptor service behind the public static
     * {@link #typeDescriptor(Type)} surface
     * (descriptor-identity-propagation D2): the static emitter
     * delegates to {@link CanonicalRuntimeTypeDescriptor#encode(Type)} —
     * the compilation's one Type→text producer — over the
     * identity-carrier projection index above.  The per-producer
     * switch (including its {@code Type.Error} arm) is retired with
     * it: the sentinel has no descriptor and {@code encode} fails
     * closed for it (the pinned internal invariant violation — never
     * emitted, never an artifact).
     */
    private static final CanonicalRuntimeTypeDescriptor STATIC_DESCRIPTORS =
        new CanonicalRuntimeTypeDescriptor(STATIC_DESCRIPTOR_INDEX);

    /** The standalone single-module identity surface of the legacy
     * generate overloads: both the module path and the source path
     * classify as project modules whose root text is the path itself
     * (the checker's standalone default classifies its path the same
     * way, so local-class identity comparisons align in the
     * single-module harnesses). */
    private static Function<String, CanonicalModuleIdentity> standaloneClassification(
            String modulePath, String sourcePath) {
        Map<String, CanonicalModuleIdentity> map = new LinkedHashMap<>();
        map.put("", CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        // List.of (not Set.of): modulePath and sourcePath may be
        // equal or null; iterate once per distinct non-empty path.
        for (String path : List.of(modulePath, sourcePath)) {
            if (path != null && !path.isEmpty()
                    && !map.containsKey(path)) {
                map.put(path,
                    new CanonicalModuleIdentity.ProjectModule(
                        new ProjectModuleIdentity(path, path, List.of())));
            }
        }
        return map::get;
    }
    private final boolean isEntry;

    private final boolean emitSharedTable;

    private final List<Type> collectedShapes;

    private final Map<CanonicalClassIdentity, String> sharedClassDeclarations;

    private final boolean int32Mode;
    private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();

    private StringBuilder out = new StringBuilder();
    private int indent = 0;

    private final Map<String, String> importAliases = new LinkedHashMap<>();

    private Map<String, List<PlannedDefaultClass>> plansByModulePath =
        Map.of();

    private final List<RuntimeClassDefaultPlan> runtimePlans =
        new ArrayList<>();

    private String generatedClassName = null;

    private static final Set<String> SUPPORTED_STDLIB_MODULES = Set.of(
        "std/console", "std/string", "std/math", "std/time", "std/table",
        "std/json");

    /**
     * Raw import path → module path of the imported compiled module
     * ({@code "./lib" → lib}), supplied by {@code CompilationOrchestrator}
     * phase 4 — the same mapping the LuaJIT use site builds. Only imports
     * present here are accepted as project-module imports; declaration
     * files and host modules are never entries and stay E6000 at the
     * import statement.
     */
    private final Map<String, String> importResolutions;

    private final Map<String, Map<String, ClassDeclaration>> importedClasses;

    private final Map<String, ImportedModuleSurface> importedSurfaces;

    /**
     * One imported compiled module's declared-boundary surface (D6): the
     * module's exported function declarations by name and the module's
     * source path. The annotation spans ride the AST nodes
     * ({@link Parameter#type()} and
     * {@link FunctionDeclaration#returnType()}); the source path is the
     * file literal the emitted declared-boundary origin carries.
     */
    public record ImportedModuleSurface(
            Map<String, FunctionDeclaration> functions,
            String sourcePath) {

        public ImportedModuleSurface {
            functions = functions == null ? Map.of() : Map.copyOf(functions);
        }
    }

    private final Map<String, Map<String, Type>> hostModules;

    /**
     * Import alias → raw import path of a host-module import
     * ({@code http → host/http}), recorded by the pre-scan for every
     * import present in {@link #hostModules} whose declared exports the
     * slice supports. Member calls through the alias emit calls to the
     * per-alias wrapper methods ({@code __host$<alias>$<fn>}); the import
     * statement emits a {@code static { __hostLoad$<alias>(); }} block
     * whose load-time reflection presence check raises E8011 for a missing
     * host module class or a missing declared export.
     */
    private final Map<String, String> hostAliases = new LinkedHashMap<>();

    private final Deque<Map<String, String>> localScopes = new ArrayDeque<>();

    private final Deque<Map<String, Type>> localTypeScopes = new ArrayDeque<>();

    private Type currentReturnType = null;

    private final Deque<Set<String>> functionBindingNames = new ArrayDeque<>();

    /** True while emitting direct module-body statements (class members). */
    private boolean moduleLevel = false;

    /**
     * Statements hoisted out of value positions (null-typed void calls that
     * must run for their observable side effects). They are emitted in
     * evaluation order right before the containing statement, preserving
     * DEAL's left-to-right evaluation order without ever emitting a lambda
     * (a lambda capturing a later-reassigned local would make javac reject
     * the artifact).
     */
    private final List<PreLine> preStatements = new ArrayList<>();

    /**
     * One hoisted pre-statement line. {@code extraIndent} is the relative
     * indentation beyond the flush site's indent (0 for a plain statement;
     * 1 for a line inside a guarded {@code if} block).
     */
    private record PreLine(String text, int extraIndent) {}

    /**
     * True while {@link #preStatements} contains a temporary declaration
     * that the containing expression references (a guarded
     * {@code &&}/{@code ||} lowering). A module-level field initializer
     * cannot reference a local of a separate static block, so
     * {@link #emitVariable} routes such initializers through a
     * static-block assignment instead. Reset by
     * {@link #flushPreStatements()}.
     */
    private boolean preStatementsDeclareTemps = false;

    /** Counter for dummy-locals that force evaluation of standalone
     * expression statements ({@code __ignored}, {@code __ignored1}, …).
     * The {@code __} prefix can never collide with a translation:
     * {@link #javaName} maps every leading underscore to {@code $u}. */
    private int ignoredCounter = 0;

    /** Counter for short-circuit temporaries ({@code __sc0}, {@code __sc1},
     * …). Unreachable from {@link #javaName} for the same reason. */
    private int shortCircuitCounter = 0;

    /** Counter for evaluation-order temporaries ({@code __t0}, {@code __t1},
     * …): inline operands materialized before a later hoisted
     * pre-statement so DEAL's left-to-right evaluation order holds.
     * Unreachable from {@link #javaName} for the same reason. */
    private int evalTempCounter = 0;

    /** Counter for string for-of loop temporaries ({@code __iter0},
     * {@code __i0}, {@code __cp0}, …). Sequential sibling loops share the
     * enclosing Java block scope, so every loop's temporaries need a
     * unique name; the {@code __} prefix is unreachable from
     * {@link #javaName}. */
    private int forOfCounter = 0;

    /** Counter for transformed-loop continue labels ({@code cont$0}, …).
     * Unreachable from {@link #javaName} for the same reason as the other
     * generated names. */
    private int loopLabelCounter = 0;

    private int jsonLocalCounter = 0;

    private final Deque<Set<String>> capturedNamesStack = new ArrayDeque<>();

    /**
     * Emitted Java names currently cell-ified for closure capture
     * ({@code x$c} for the mapped binding {@code x}). Kept in sync with
     * {@link #capturedNamesStack}: {@link #declareLocal} registers the
     * mapped name when the declared DEAL name is captured by a nested
     * function, and {@link #localJavaName} routes reads/writes through
     * the cell.
     */
    private final Deque<Set<String>> capturedMappedStack = new ArrayDeque<>();

    /**
     * Per-loop continue label ({@code null} when a plain Java
     * {@code continue} targets the nearest loop correctly). The
     * transformed for/while forms re-place the loop update inside the
     * body, so a DEAL {@code continue} must jump past the body to the
     * update — emitted as {@code break <label>;} on the labeled body
     * block. The checker rejects break/continue outside a loop (E2000),
     * so the stack is never empty at a break/continue emission.
     */
    private final Deque<String> loopContinueLabels = new java.util.LinkedList<>();

    /**
     * Emitted Java names whose reads temporarily bypass closure-cell
     * routing (the transformed/captured for-loop HEADER reads the plain
     * loop variable — its per-iteration cell is declared inside the
     * body).
     */
    private final Set<String> plainReadNames = new HashSet<>();

    /** Module-level function declarations by name (exports included), in
     * declaration order. */
    private final Map<String, FunctionDeclaration> moduleFunctions =
        new LinkedHashMap<>();

    /** Module-level function declaration indices by name (exports
     * included) → statement index in the module body. */
    private final Map<String, Integer> moduleFunctionIndices =
        new LinkedHashMap<>();

    /** Module-level variable declarations by name → statement index in the
     * module body. */
    private final Map<String, Integer> moduleFieldIndices =
        new LinkedHashMap<>();

    /** Module-level (non-exported) class declarations by name, in
     * declaration order ({@code export class} stays E6000 — the module ABI
     * surface is a later slice). Registered in the pre-scan, before any
     * emission, so construction sites before the declaration resolve. */
    private final Map<String, ClassDeclaration> moduleClasses =
        new LinkedHashMap<>();

    private final List<String> classCheckBranches = new ArrayList<>();

    private final List<String> refArrayCheckBranches = new ArrayList<>();

    private final List<String> hostRefArrayCheckBranches =
        new ArrayList<>();

    /** Element shapes whose {@code [D]} branch is registered (one branch
     * per shape). */
    private final Set<Type> refArrayCheckElements = new LinkedHashSet<>();

    // ---- Declared-boundary origin threading (D1/D3) --------------------

    /**
     * One declared-boundary origin (D1/D3,
     * jvm-canonical-error-snapshot-convergence): the authoritative
     * DEAL-source location of the check a currently emitted expression
     * crosses — the callee's declared parameter-type annotation for a
     * call argument, the caller's return expression for a return
     * boundary, the binding's declared contextual annotation for a
     * checked binding, or the call expression for a stdlib
     * declared-parameter boundary. The values are compile-time literals
     * in the generated artifact; the file component names the
     * DECLARING module's emitted artifact class file, which the lane's
     * deployment map normalizes to the canonical corpus-relative source
     * path (a cross-module origin therefore names the companion's
     * corpus file, never the caller's).
     */
    private record BoundaryOrigin(String file, int line, int column) { }

    /** The active declared-boundary origins (innermost first). */
    private final java.util.ArrayDeque<BoundaryOrigin> boundaryOrigins =
        new java.util.ArrayDeque<>();

    /** The current declared-boundary origin, or {@code null} when the
     * emitted expression crosses no origin-carrying boundary. */
    private BoundaryOrigin currentBoundaryOrigin() {
        return boundaryOrigins.peek();
    }

    /** Pushes one boundary origin for the duration of {@code action}. */
    private <T> T withBoundaryOrigin(BoundaryOrigin origin,
            java.util.function.Supplier<T> action) {
        if (origin == null) {
            return action.get();
        }
        boundaryOrigins.push(origin);
        try {
            return action.get();
        } finally {
            boundaryOrigins.pop();
        }
    }

    /** The emitted artifact class file name of one module path (the
     * deployment-map key the lane normalizes): {@code <ClassName>.java}
     * exactly as {@link #classNameFor} derives it. */
    private static String artifactFileOf(String modulePath) {
        return classNameFor(modulePath) + ".java";
    }

    /**
     * The origin of one declared parameter-type annotation (the
     * parameter-boundary / E8010 call-argument / imported-companion
     * classes): the annotation's own span in the DECLARING module's
     * artifact. {@code null} when the declaration or annotation is not
     * available (the raise then carries no origin, never a fabricated
     * one).
     */
    private static BoundaryOrigin paramOrigin(FunctionDeclaration fd,
            int index, String artifactFile) {
        if (fd == null || index < 0 || index >= fd.params().size()) {
            return null;
        }
        return originOf(fd.params().get(index).type(), artifactFile);
    }

    /** The origin of one declared type annotation node. */
    private static BoundaryOrigin originOf(TypeNode annotation,
            String artifactFile) {
        if (annotation == null || annotation.span() == null) {
            return null;
        }
        return new BoundaryOrigin(artifactFile,
            annotation.span().startLine(), annotation.span().startColumn());
    }

    /** The origin of one expression's start (return values, stdlib/site
     * boundaries). */
    private BoundaryOrigin originOfExpression(ExpressionNode e,
            String artifactFile) {
        if (e == null || e.span() == null) {
            return null;
        }
        return new BoundaryOrigin(artifactFile, e.span().startLine(),
            e.span().startColumn());
    }

    /**
     * The emitted {@code $check(...)} call for a checked value crossing
     * a declared boundary: without an active origin the unchanged
     * two-argument form, with one the origin-carrying form whose
     * literals reach every raise of the shared seam.
     */
    private String checkCall(String descriptorLiteral, String valueCode,
            String seamPrefix) {
        BoundaryOrigin origin = currentBoundaryOrigin();
        if (origin == null) {
            return seamPrefix + "$check(" + descriptorLiteral + ", "
                + valueCode + ")";
        }
        return seamPrefix + "$check(" + descriptorLiteral + ", "
            + valueCode + ", " + quoteJavaString(origin.file()) + ", "
            + origin.line() + ", " + origin.column() + ")";
    }

    /** {@link #checkCall} over this module's own seam. */
    private String checkCall(String descriptorLiteral, String valueCode) {
        return checkCall(descriptorLiteral, valueCode, "");
    }

    /**
     * The origin arguments of the E8010 signature-check wrapper's raise
     * (D1/D3): the active declared-boundary origin as compile-time
     * literals, or the explicit absent sentinel when no origin is
     * active.
     */
    private String signatureCheckOriginArgs() {
        BoundaryOrigin origin = currentBoundaryOrigin();
        if (origin == null) {
            return "null, -1, -1";
        }
        return quoteJavaString(origin.file()) + ", " + origin.line() + ", "
            + origin.column();
    }

    /** Module-level @jsonable class declarations, in declaration order
     * (the JSON serialization slice). Registered in the pre-scan; drives
     * the conditional emission of the JSON runtime support and the
     * per-class generated {@code C$fromJson}/{@code C$toJson} helpers. */
    private final List<ClassDeclaration> jsonableClasses = new ArrayList<>();

    /** Module-level variable declarations by name (the AST nodes), for
     * the load-time indirect-call value analysis
     * ({@link #moduleIndirectCallRisk}). */
    private final Map<String, VariableDeclaration> moduleFieldDecls =
        new LinkedHashMap<>();

    /** The module body in declaration order, for the load-time
     * indirect-call guards. */
    private List<StatementNode> moduleStatements = List.of();

    private final StringBuilder sharedWrapperClasses = new StringBuilder();

    /** Wrapper shapes already registered (one class per shape id). */
    private final Set<String> emittedWrapperShapes = new LinkedHashSet<>();

    /** The statements of the function body currently being emitted, or
     * {@code null} at module level — the scan scope for the
     * adapter-capture reassignment check. */
    private List<StatementNode> currentFunctionBody = null;

    /** DEAL parameter names of the function body currently being emitted
     * (empty at module level) — the base bindings of the adapter-capture
     * reassignment scan. */
    private List<String> currentFunctionParams = List.of();

    /** Counter for function-value snapshot temporaries ({@code __fn0},
     * {@code __fn1}, …) that keep an arity adapter's captured value
     * effectively final without a lambda. Unreachable from
     * {@link #javaName} (the {@code __} prefix escapes to {@code $u}). */
    private int functionValueTempCounter = 0;

    /** Function name → module fields read by its body, transitively through
     * calls to other module functions (use-before-declaration detection for
     * module-level calls). */
    private final Map<String, Set<String>> transitiveFieldReads =
        new LinkedHashMap<>();

    /** Function name → all module functions reachable from its body
     * through calls, including the function itself (use-before-declaration
     * detection for module-level calls). */
    private final Map<String, Set<String>> transitiveFunctionCalls =
        new LinkedHashMap<>();

    /** Function name → a module field declared after the function that its
     * body READS without a dominating write inside the function
     * (write-dominance analysis; see
     * {@link #computeForwardFieldViolations}). Emitting such a function is
     * an E6000: LuaJIT fails at call time reading the global nil (E8001)
     * while Java would silently read the initialized static field. */
    private final Map<String, String> forwardReadViolations =
        new LinkedHashMap<>();

    private final Map<String, Set<String>> transitiveImportReads =
        new LinkedHashMap<>();

    private final Map<String, Set<String>> transitiveFunctionValueReads =
        new LinkedHashMap<>();

    /** Function name → a module field declared after the function that its
     * body WRITES (write-dominance analysis; see
     * {@link #computeForwardFieldViolations}). Emitting such a function is
     * an E6000: LuaJIT binds the pre-declaration write to the GLOBAL of
     * the same name — the module-local does not exist when the function
     * value is created — leaving the module-local untouched, so later
     * readers (functions declared after the field, exported functions)
     * observe the initializer value; Java would write the static field
     * and pollute every later reader. The write-then-read shape
     * ({@code x = 5; return x;}) is included: the function's own read
     * observes the global write under LuaJIT, but the polluted Java field
     * remains observable by later readers. */
    private final Map<String, String> forwardWriteViolations =
        new LinkedHashMap<>();

    /**
     * Function name → the module fields its body ASSIGNS, transitively
     * through direct calls to other module functions (any assignment
     * position — statements, value positions, hidden operands). Used by
     * the load-time indirect-call value-set walk: a module-level call of
     * such a function executed before the guarded field read is a
     * potential assignment source, so the field's value set is not
     * statically known and the call is rejected (LuaJIT executes the
     * assignment at load; the value-set walk must observe it).
     */
    private final Map<String, Set<String>> transitiveAssignedFields =
        new LinkedHashMap<>();

    private final Map<String, Set<String>> transitiveIndirectCalls =
        new LinkedHashMap<>();

    /**
     * Function-typed module field name → the static superset of module
     * functions the field may hold at any load-time read: its
     * initializer (followed transitively through other function-valued
     * fields — an adapter over a field delegates to that field's value,
     * so the inner field's superset is included) plus EVERY assignment
     * to the field anywhere in the module (module-level statements and
     * every function body, hidden value positions included). A
     * non-identifier initializer/assignment value contributes
     * {@link #UNKNOWN_HELD_VALUE}. The superset over-approximates
     * (assignments in bodies that never run at load count too) and is
     * used only by the value-set walk's indirect-callee check to add a
     * conservative rejection, never to admit a shape.
     */
    private final Map<String, Set<String>> fieldValueSupersets =
        new LinkedHashMap<>();

    /** Sentinel member of the value-set analyses: the field's value (or
     * an indirect callee) is an expression that is not a bare
     * module-function/intrinsic identifier and cannot be analyzed. */
    private static final String UNKNOWN_HELD_VALUE = "<expression>";

    /** Maximum nesting depth the emitted {@code __jsonTableValue}
     * conversion recurses into (spec §JSON serialization fromJson):
     * past this bound the conversion throws, and the public
     * {@code C$fromJson} wrapper converts the throw to the DEAL null —
     * a deterministic guard against hostile deep-nesting stack
     * exhaustion instead of relying on a StackOverflowError at the
     * recursion limit. 512 levels of emitted conversion frames stay far
     * below the default JVM thread stack even with per-level iterator
     * frames. */
    private static final int JSON_TABLE_DEPTH_LIMIT = 512;

    /** Statement index of the module-level statement currently being
     * emitted ({@code -1} inside function bodies). Used to detect
     * module-level calls that transitively read later-declared fields. */
    private int currentModuleStatementIndex = -1;

    private Set<ExpressionNode> deferredHostArgReads = null;

    /**
     * D5 phase-order deferral for class-construction provided values
     * (identity-keyed, set only while one construction's provided
     * values are being emitted): a table read provided to a plain
     * {@code bytes} field yields the raw value at phase 1 and
     * validates against the field's canonical descriptor at phase 3 —
     * after every omitted required default of the attempt ran once —
     * exactly like the LuaJIT reference's {@code class_plan_} order
     * (provided slots → defaults → per-field canonical validation →
     * publish). The read-site E8001 would otherwise fire during phase
     * 1 and suppress the defaults the reference still runs (a
     * side-effect-count divergence pinned by the
     * bytes-class-default-integration fixture). Only the direct
     * provided-value read defers; a read nested inside the provided
     * expression is a different node and keeps its pinned read-site
     * check.
     */
    private Set<ExpressionNode> deferredConstructionReads = null;

    private final Set<String> emittedHostClassBranches =
        new LinkedHashSet<>();

    private final Map<String, Map<String,
        List<HostModuleDeclarations.HostField>>> hostClassDeclarations;

    /**
     * The project-wide host-class declaration union (raw import
     * specifier → class export name → declared field records). The
     * entry module's shared {@code $DealRt} scope emits every declared
     * host class record from this union (the single emission point).
     */
    private final Map<String, Map<String,
        List<HostModuleDeclarations.HostField>>> sharedHostClasses;

    private final Map<String, Integer> importAliasStatementIndices =
        new LinkedHashMap<>();

    private JvmBackend(Map<ExpressionNode, Type> typeMap, SymbolTable symbols,
                       String sourcePath, String modulePath,
                       Map<String, String> importResolutions,
                       Map<String, Map<String, ClassDeclaration>> importedClasses,
                       Map<String, ImportedModuleSurface> importedSurfaces,
                       Map<String, Map<String, Type>> hostModules,
                       Map<String, Map<String,
                           List<HostModuleDeclarations.HostField>>>
                           hostClassDeclarations,
                       boolean isEntry, boolean emitSharedTable,
                       SemanticProfile semanticProfile,
                       List<Type> sharedShapes,
                       CanonicalClassIdentityIndex identityIndex,
                       Function<String, CanonicalModuleIdentity> moduleIdentities,
                       Map<CanonicalClassIdentity, String> sharedClassDeclarations,
                       Map<String, Map<String,
                           List<HostModuleDeclarations.HostField>>>
                           sharedHostClasses) {
        this.typeMap = typeMap;
        this.symbols = symbols;
        this.sourcePath = sourcePath;
        this.modulePath = modulePath;
        this.isEntry = isEntry;
        this.emitSharedTable = emitSharedTable;
        this.identityIndex = Objects.requireNonNull(identityIndex,
            "identityIndex must not be null");
        this.moduleIdentities = Objects.requireNonNull(moduleIdentities,
            "moduleIdentities must not be null");
        this.int32Mode = semanticProfile == SemanticProfile.DEAL_V1_2_INT32;
        this.importResolutions = importResolutions == null
            ? Map.of() : Map.copyOf(importResolutions);
        this.importedClasses = importedClasses == null
            ? Map.of() : Map.copyOf(importedClasses);
        this.importedSurfaces = importedSurfaces == null
            ? Map.of() : Map.copyOf(importedSurfaces);
        // The inner export maps keep the orchestrator's declaration
        // order (info.exports is a statement-ordered LinkedHashMap):
        // collectModuleShapes walks them in source order (deterministic
        // diagnostics D4 — hash-bucket iteration must never drive
        // output). The outer map is keyed-access-only.
        Map<String, Map<String, Type>> hostCopy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Type>> entry
                : (hostModules == null ? Map.<String, Map<String, Type>>of()
                    : hostModules).entrySet()) {
            hostCopy.put(entry.getKey(), entry.getValue() == null
                ? Map.of() : new LinkedHashMap<>(entry.getValue()));
        }
        this.hostModules = hostCopy;
        Map<String, Map<String, List<HostModuleDeclarations.HostField>>>
            hostClassCopy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String,
                List<HostModuleDeclarations.HostField>>> entry
                : (hostClassDeclarations == null
                    ? Map.<String, Map<String,
                        List<HostModuleDeclarations.HostField>>>of()
                    : hostClassDeclarations).entrySet()) {
            hostClassCopy.put(entry.getKey(), entry.getValue() == null
                ? Map.of()
                : new LinkedHashMap<>(entry.getValue()));
        }
        this.hostClassDeclarations = hostClassCopy;
        Map<String, Map<String, List<HostModuleDeclarations.HostField>>>
            sharedHostCopy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String,
                List<HostModuleDeclarations.HostField>>> entry
                : (sharedHostClasses == null
                    ? Map.<String, Map<String,
                        List<HostModuleDeclarations.HostField>>>of()
                    : sharedHostClasses).entrySet()) {
            sharedHostCopy.put(entry.getKey(), entry.getValue() == null
                ? Map.of()
                : new LinkedHashMap<>(entry.getValue()));
        }
        this.sharedHostClasses = sharedHostCopy;
        localScopes.push(new LinkedHashMap<>());
        localTypeScopes.push(new LinkedHashMap<>());

        this.collectedShapes = sharedShapes == null
            ? List.of() : List.copyOf(sharedShapes);
        this.sharedClassDeclarations = sharedClassDeclarations == null
            ? Map.of() : Map.copyOf(sharedClassDeclarations);
    }

    private void registerCollectedShape(Type t) {
        if (t instanceof Type.Func f) {
            registerWrapperShape(f);
            return;
        }
        if (t instanceof Type.Array a) {
            Type element = a.element();

            if (element instanceof Type.Array
                    || element instanceof Type.Func
                    || (element instanceof Type.Nullable ne
                        && ne.inner() instanceof Type.Func)) {
                registerRefArrayShape(element);
            }
        }
    }

    /** The closed shape set produced by a collection pass; {@code null}
     * during emission. Populated only by {@link #collectModuleShapes}. */
    private LinkedHashSet<Type> collectedShapesOut = null;

    public static List<Type> collectShapes(ProgramNode program,
            CheckResult result, String sourcePath, String modulePath,
            Map<String, String> importResolutions,
            Map<String, Map<String, ClassDeclaration>> importedClasses,
            Map<String, Map<String, Type>> hostModules,
            Map<String, Map<String,
                List<HostModuleDeclarations.HostField>>>
                hostClassDeclarations,
            SemanticProfile semanticProfile,
            CanonicalClassIdentityIndex identityIndex,
            Function<String, CanonicalModuleIdentity> moduleIdentities) {
        JvmBackend collector = new JvmBackend(result.typeMap(),
            result.symbolTable(), sourcePath, modulePath,
            importResolutions, importedClasses, Map.of(), hostModules,
            hostClassDeclarations, false, false,
            semanticProfile, null, identityIndex, moduleIdentities,
            Map.of(), Map.of());
        return collector.collectModuleShapes(program);
    }

    private List<Type> collectModuleShapes(ProgramNode program) {
        collectedShapesOut = new LinkedHashSet<>();
        // Silent context mirror of generateProgram's pre-scan: module
        // classes and import aliases, so the silent class-type mapper
        // resolves exactly the classes the real emission would.
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ImportDeclaration imp) {
                if (SUPPORTED_STDLIB_MODULES.contains(imp.modulePath())) {
                    importAliases.put(imp.alias(), imp.modulePath());
                } else {
                    Map<String, Type> hostExports =
                        hostModules.get(imp.modulePath());
                    if (hostExports != null) {
                        importAliases.put(imp.alias(),
                            imp.modulePath().replace('/', '.'));

                        hostAliases.put(imp.alias(), imp.modulePath());
                    } else {
                        String resolved = importResolutions.get(
                            imp.modulePath());
                        if (resolved != null) {
                            importAliases.put(imp.alias(), resolved);
                        }
                    }
                }
            } else if (stmt instanceof ClassDeclaration cd) {
                moduleClasses.putIfAbsent(cd.name(), cd);
            } else if (stmt instanceof ExportDeclaration ed
                    && ed.declaration() instanceof ClassDeclaration cd) {
                moduleClasses.putIfAbsent(cd.name(), cd);
            }
        }
        // The two conversion-intrinsic wrapper shapes every module emits.
        closeShape(new Type.Func(List.of(Type.Number.INSTANCE),
            Type.Int.INSTANCE));
        closeShape(new Type.Func(List.of(Type.Int.INSTANCE),
            Type.Number.INSTANCE));
        // Host declaration shapes, in import-statement (source) order:
        // the export maps are the orchestrator's declaration-ordered
        // correctedExports LinkedHashMaps (preserved by the constructor
        // copy), so this walk is deterministic — source order, never
        // hash-bucket order (deterministic-diagnostics D4). The
        // checker-inferred expression types need no separate map
        // iteration: collectTypeNodes walks every expression node in
        // source order and closes its typeMap entry there.
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ImportDeclaration imp) {
                Map<String, Type> hostExports =
                    hostModules.get(imp.modulePath());
                if (hostExports != null) {
                    for (Type t : hostExports.values()) {
                        closeShape(t);
                    }
                }
            }
        }
        // Every type annotation and nested expression in the AST.
        collectTypeNodes(program.statements());
        List<Type> result = List.copyOf(collectedShapesOut);
        collectedShapesOut = null;
        return result;
    }

    /** Adds {@code t} to the collection and closes over its structure:
     * function params/returns, array elements, nullable inners. */
    private void closeShape(Type t) {
        if (t instanceof Type.Func f) {
            if (collectedShapesOut.add(f)) {
                for (Type p : f.paramTypes()) {
                    closeShape(p);
                }
                closeShape(f.returnType());
            }
            return;
        }
        if (t instanceof Type.Array a) {
            if (collectedShapesOut.add(a)) {
                closeShape(a.element());
            }
            return;
        }
        if (t instanceof Type.Nullable n) {
            closeShape(n.inner());
        }
    }

    /** Silent resolution of a type annotation for the collection pass:
     * mirrors {@link #resolveTypeNode} shape-for-shape but never
     * records a diagnostic ({@code Type.Error} for unsupported shapes,
     * which the real emission rejects with its own E6000). */
    /** Identity-carrying {@code Type.Class} for a class declared in
     * the given wiring path, or {@code Type.Error} when the path has
     * no public module identity — the silent collection pass never
     * throws and never records a diagnostic. */
    private Type silentClassType(String name, String wiringPath) {
        CanonicalModuleIdentity moduleIdentity = moduleIdentities.apply(
            wiringPath == null ? "" : wiringPath);
        if (moduleIdentity == null) {
            return Type.Error.INSTANCE;
        }
        return Types.classType(name,
            new CanonicalClassIdentity(moduleIdentity, name));
    }

    private Type silentResolveTypeNode(TypeNode tn) {
        return switch (tn) {
            case NamedType nt -> switch (nt.name()) {
                case "null" -> Type.Null.INSTANCE;
                case "boolean" -> Type.Boolean.INSTANCE;
                case "int" -> Type.Int.INSTANCE;
                case "number" -> Type.Number.INSTANCE;
                case "string" -> Type.String.INSTANCE;
                case "bytes" -> {
                    Symbol sym = symbols.resolve(nt.name());
                    if (sym instanceof Symbol.ClassSymbol
                            && moduleClasses.containsKey(nt.name())) {
                        yield silentClassType(nt.name(), modulePath);
                    }
                    yield Type.Bytes.INSTANCE;
                }
                case "table" -> Type.Table.INSTANCE;
                default -> {
                    Symbol sym = symbols.resolve(nt.name());
                    if (sym instanceof Symbol.ClassSymbol
                            && moduleClasses.containsKey(nt.name())) {
                        yield silentClassType(nt.name(), modulePath);
                    }
                    if ("Error".equals(nt.name())
                            && sym instanceof Symbol.ClassSymbol) {
                        yield errorClassType();
                    }
                    yield Type.Error.INSTANCE;
                }
            };
            case QualifiedType qt -> {

                String hostRaw = hostAliases.get(qt.moduleName());
                if (hostRaw != null) {
                    Map<String, Type> exports = hostModules.get(hostRaw);
                    Type exportType = exports == null ? null
                        : exports.get(qt.typeName());
                    if (exportType instanceof Type.Class c) {
                        yield c;
                    }
                    yield Type.Error.INSTANCE;
                }
                // The alias-keyed importAliases table — the same table
                // the real resolver and the collection pre-scan populate
                // — names the declaring module. importResolutions is
                // keyed by the RAW import path ("./util"), so an
                // alias-keyed lookup there silently dropped every
                // qualified-class shape from the project-wide set.
                String module = importAliases.get(qt.moduleName());
                Map<String, ClassDeclaration> decls =
                    module == null ? null : importedClasses.get(module);
                if (decls == null || !decls.containsKey(qt.typeName())) {
                    yield Type.Error.INSTANCE;
                }
                yield silentClassType(qt.typeName(), module);
            }
            case ArrayType at -> {
                Type elem = silentResolveTypeNode(at.elementType());
                yield elem == Type.Error.INSTANCE
                    ? Type.Error.INSTANCE : new Type.Array(elem);
            }
            case NullableType nnt -> {
                Type inner = silentResolveTypeNode(nnt.innerType());
                if (inner == Type.Error.INSTANCE
                        || inner instanceof Type.Null
                        || inner instanceof Type.Nullable) {
                    yield Type.Error.INSTANCE;
                }
                yield new Type.Nullable(inner);
            }
            case FunctionType ft -> {
                List<Type> params = new ArrayList<>();
                boolean ok = true;
                for (FunctionTypeParam p : ft.params()) {
                    Type pt = silentResolveTypeNode(p.type());
                    if (pt == Type.Error.INSTANCE) {
                        ok = false;
                        break;
                    }
                    params.add(pt);
                }
                Type rt = silentResolveTypeNode(ft.returnType());
                if (rt == Type.Error.INSTANCE) {
                    ok = false;
                }
                yield ok
                    ? new Type.Func(params, rt, ft.isAsync())
                    : Type.Error.INSTANCE;
            }
        };
    }

    /** The function shape of a declaration (the signature the wrapper
     * field will carry), assembled exactly like the checker's function
     * symbol type. */
    private void collectDeclarationShape(FunctionDeclaration fd) {
        List<Type> params = new ArrayList<>();
        boolean ok = true;
        for (Parameter p : fd.params()) {
            Type pt = silentResolveTypeNode(p.type());
            if (pt == Type.Error.INSTANCE) {
                ok = false;
                break;
            }
            params.add(pt);
        }
        Type rt = silentResolveTypeNode(fd.returnType());
        if (rt == Type.Error.INSTANCE) {
            ok = false;
        }
        if (ok) {
            closeShape(new Type.Func(params, rt, fd.isAsync()));
        }
    }

    /** Walks every statement collecting type annotations and expression
     * types (with nested function-expression bodies). */
    private void collectTypeNodes(List<StatementNode> stmts) {
        for (StatementNode stmt : stmts) {
            switch (stmt) {
                case VariableDeclaration vd -> {
                    vd.typeAnnotation().ifPresent(this::collectTypeNode);
                    collectExprShapes(vd.initializer());
                }
                case FunctionDeclaration fd -> {
                    collectTypeNode(fd.returnType());
                    for (Parameter p : fd.params()) {
                        collectTypeNode(p.type());
                    }
                    collectDeclarationShape(fd);
                    collectTypeNodes(fd.body().statements());
                }
                case ExportDeclaration ed -> collectTypeNodes(
                    List.of(ed.declaration()));
                case ClassDeclaration cd -> {
                    for (ClassField cf : cd.fields()) {
                        collectTypeNode(cf.type());
                        cf.defaultExpr().ifPresent(this::collectExprShapes);
                    }
                }
                case ReturnStatement rs -> rs.expr().ifPresent(
                    this::collectExprShapes);
                case IfStatement is -> {
                    collectExprShapes(is.condition());
                    collectTypeNodes(is.thenBlock().statements());
                    if (is.elseBranch().isPresent()) {
                        switch (is.elseBranch().get()) {
                            case Either.Left<IfStatement, Block> left -> {
                                collectExprShapes(left.value().condition());
                                collectTypeNodes(
                                    left.value().thenBlock().statements());
                                if (left.value().elseBranch().isPresent()) {
                                    collectTypeNodes(
                                        List.of(new IfStatement(
                                            left.value().span(),
                                            left.value().condition(),
                                            left.value().thenBlock(),
                                            left.value().elseBranch())));
                                }
                            }
                            case Either.Right<IfStatement, Block> right ->
                                collectTypeNodes(right.value().statements());
                        }
                    }
                }
                case Block b -> collectTypeNodes(b.statements());
                case WhileStatement ws -> {
                    collectExprShapes(ws.condition());
                    collectTypeNodes(ws.body().statements());
                }
                case ForStatement fs -> {
                    if (fs.init().isPresent()) {
                        switch (fs.init().get()) {
                            case ForInit.VarDecl vd -> collectTypeNodes(
                                List.of(vd.decl()));
                            case ForInit.AssignExpr ae -> collectExprShapes(
                                ae.expr());
                        }
                    }
                    fs.condition().ifPresent(this::collectExprShapes);
                    fs.update().ifPresent(this::collectExprShapes);
                    collectTypeNodes(fs.body().statements());
                }
                case ForOfStatement fos -> {
                    collectTypeNode(fos.varType());
                    collectExprShapes(fos.iterable());
                    collectTypeNodes(fos.body().statements());
                }
                case ExpressionStatement es -> collectExprShapes(es.expr());
                case ThrowStatement ts -> collectExprShapes(ts.expr());
                case DeleteStatement ds -> collectExprShapes(ds.target());
                case TryStatement ts -> {
                    collectTypeNodes(ts.tryBlock().statements());
                    collectTypeNodes(ts.catchBlock().statements());
                }
                case ImportDeclaration ignored -> { }
                case BreakStatement ignored -> { }
                case ContinueStatement ignored -> { }
            }
        }
    }

    /** Collects the checker-inferred type of an expression and walks its
     * children (function-expression bodies recurse into statements). */
    private void collectExprShapes(ExpressionNode e) {
        Type t = typeMap.get(e);
        if (t != null) {
            closeShape(t);
        }
        switch (e) {
            case LiteralExpr ignored -> { }
            case IdentifierExpr ignored -> { }
            case BinaryExpr b -> {
                collectExprShapes(b.left());
                collectExprShapes(b.right());
            }
            case UnaryExpr u -> collectExprShapes(u.expr());
            case CallExpr c -> {
                collectExprShapes(c.callee());
                for (ExpressionNode a : c.args()) {
                    collectExprShapes(a);
                }
            }
            case MemberAccessExpr m -> collectExprShapes(m.object());
            case IndexExpr ix -> {
                collectExprShapes(ix.array());
                collectExprShapes(ix.index());
            }
            case ArrayLiteralExpr al -> {
                for (ExpressionNode el : al.elements()) {
                    collectExprShapes(el);
                }
            }
            case ObjectLiteralExpr ol -> {
                for (Property p : ol.properties()) {
                    collectExprShapes(p.value());
                }
            }
            case FunctionExpr fe -> {
                for (Parameter p : fe.params()) {
                    collectTypeNode(p.type());
                }
                collectTypeNodes(fe.body().statements());
            }
            case HasExpr he -> collectExprShapes(he.object());
            case AssignmentExpr ae -> {
                collectExprShapes(ae.target());
                collectExprShapes(ae.value());
            }
            case TemplateLiteralExpr tl -> {
                for (ExpressionNode part : tl.parts()) {
                    collectExprShapes(part);
                }
            }
            case AwaitExpression aw -> collectExprShapes(aw.callee());
        }
    }

    /** Collects one type annotation through the silent resolver. */
    private void collectTypeNode(TypeNode tn) {
        Type t = silentResolveTypeNode(tn);
        if (t != null && !(t instanceof Type.Error)) {
            closeShape(t);
        }
    }

    // =========================================================================
    // Static entry points
    // =========================================================================

    /**
     * Generates Java source for a checked module. The module path defaults to
     * the source path.
     */
    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath) {
        return generate(program, result, sourcePath, sourcePath, Map.of());
    }

    /**
     * Generates Java source for a checked module with an explicit module path.
     * The class name is derived from the full module path (collision-safe:
     * {@code app/main} and {@code sub/main} derive {@code AppMain} and
     * {@code SubMain}).
     */
    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath) {
        return generate(program, result, sourcePath, modulePath, Map.of());
    }

    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            SemanticProfile semanticProfile) {
        return generate(program, result, sourcePath, modulePath, Map.of(),
            Map.of(), Map.of(), false, true, semanticProfile);
    }

    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions) {
        return generate(program, result, sourcePath, modulePath,
            importResolutions, Map.of());
    }

    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions,
                                            Map<String, Map<String, ClassDeclaration>> importedClasses) {

        return generate(program, result, sourcePath, modulePath,
            importResolutions, importedClasses, Map.of(), Map.of(),
            false, true);
    }

    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions,
                                            Map<String, Map<String, ClassDeclaration>> importedClasses,
                                            Map<String, Map<String, Type>> hostModules,
                                            boolean isEntry) {
        return generate(program, result, sourcePath, modulePath,
            importResolutions, importedClasses, hostModules, Map.of(),
            isEntry);
    }

    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions,
                                            Map<String, Map<String, ClassDeclaration>> importedClasses,
                                            Map<String, Map<String, Type>> hostModules,
                                            Map<String, Map<String,
                                                List<HostModuleDeclarations.HostField>>>
                                                hostClassDeclarations,
                                            boolean isEntry,
                                            boolean emitSharedTable) {
        return generate(program, result, sourcePath, modulePath,
            importResolutions, importedClasses, hostModules,
            hostClassDeclarations, isEntry, emitSharedTable,
            SemanticProfile.LEGACY_SAFE_INT);
    }

    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions,
                                            Map<String, Map<String, ClassDeclaration>> importedClasses,
                                            Map<String, Map<String, Type>> hostModules,
                                            Map<String, Map<String,
                                                List<HostModuleDeclarations.HostField>>>
                                                hostClassDeclarations,
                                            boolean isEntry,
                                            boolean emitSharedTable,
                                            SemanticProfile semanticProfile) {
        return generate(program, result, sourcePath, modulePath,
            importResolutions, importedClasses, hostModules,
            hostClassDeclarations, isEntry, emitSharedTable,
            semanticProfile, null);
    }

    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions,
                                            Map<String, Map<String, ClassDeclaration>> importedClasses,
                                            Map<String, Map<String, Type>> hostModules,
                                            Map<String, Map<String,
                                                List<HostModuleDeclarations.HostField>>>
                                                hostClassDeclarations,
                                            boolean isEntry,
                                            boolean emitSharedTable,
                                            SemanticProfile semanticProfile,
                                            List<Type> sharedShapes) {
        Function<String, CanonicalModuleIdentity> classification =
            standaloneClassification(modulePath, sourcePath);
        ModuleIdentityResolver.IdentityIndex standalone =
            ModuleIdentityResolver.buildIndex(
                classificationMap(modulePath, sourcePath));
        return generate(program, result, sourcePath, modulePath,
            importResolutions, importedClasses, hostModules,
            hostClassDeclarations, isEntry, emitSharedTable, standalone,
            classification, semanticProfile, sharedShapes, Map.of(),
            Map.of());
    }

    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions,
                                            Map<String, Map<String, ClassDeclaration>> importedClasses,
                                            Map<String, Map<String, Type>> hostModules,
                                            Map<String, Map<String,
                                                List<HostModuleDeclarations.HostField>>>
                                                hostClassDeclarations,
                                            boolean isEntry) {
        return generate(program, result, sourcePath, modulePath,
            importResolutions, importedClasses, hostModules,
            hostClassDeclarations, isEntry, isEntry,
            SemanticProfile.LEGACY_SAFE_INT);
    }

    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions,
                                            Map<String, Map<String, ClassDeclaration>> importedClasses,
                                            Map<String, Map<String, Type>> hostModules,
                                            boolean isEntry,
                                            SemanticProfile semanticProfile) {
        return generate(program, result, sourcePath, modulePath,
            importResolutions, importedClasses, hostModules, Map.of(),
            isEntry, isEntry, semanticProfile);
    }

    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions,
                                            Map<String, Map<String, ClassDeclaration>> importedClasses,
                                            Map<String, Map<String, Type>> hostModules,
                                            boolean isEntry,
                                            boolean emitSharedTable) {
        return generate(program, result, sourcePath, modulePath,
            importResolutions, importedClasses, hostModules, Map.of(),
            isEntry, emitSharedTable, SemanticProfile.LEGACY_SAFE_INT);
    }

    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions,
                                            Map<String, Map<String, ClassDeclaration>> importedClasses,
                                            Map<String, Map<String, Type>> hostModules,
                                            boolean isEntry,
                                            boolean emitSharedTable,
                                            SemanticProfile semanticProfile) {
        return generate(program, result, sourcePath, modulePath,
            importResolutions, importedClasses, hostModules, Map.of(),
            isEntry, emitSharedTable, semanticProfile, null);
    }

    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions,
                                            Map<String, Map<String, ClassDeclaration>> importedClasses,
                                            Map<String, Map<String, Type>> hostModules,
                                            boolean isEntry,
                                            boolean emitSharedTable,
                                            SemanticProfile semanticProfile,
                                            List<Type> sharedShapes) {
        Function<String, CanonicalModuleIdentity> classification =
            standaloneClassification(modulePath, sourcePath);
        ModuleIdentityResolver.IdentityIndex standalone =
            ModuleIdentityResolver.buildIndex(
                classificationMap(modulePath, sourcePath));
        return generate(program, result, sourcePath, modulePath,
            importResolutions, importedClasses, hostModules, Map.of(),
            isEntry, emitSharedTable, standalone, classification,
            semanticProfile, sharedShapes, Map.of(), Map.of());
    }

    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions,
                                            Map<String, Map<String, ClassDeclaration>> importedClasses,
                                            Map<String, Map<String, Type>> hostModules,
                                            Map<String, Map<String,
                                                List<HostModuleDeclarations.HostField>>>
                                                hostClassDeclarations,
                                            boolean isEntry,
                                            boolean emitSharedTable,
                                            CanonicalClassIdentityIndex identityIndex,
                                            Function<String, CanonicalModuleIdentity> moduleIdentities,
                                            SemanticProfile semanticProfile,
                                            List<Type> sharedShapes,
                                            Map<CanonicalClassIdentity, String> sharedClassDeclarations,
                                            Map<String, Map<String,
                                                List<HostModuleDeclarations.HostField>>>
                                                sharedHostClasses) {
        return generate(program, result, sourcePath, modulePath,
            importResolutions, importedClasses, hostModules,
            hostClassDeclarations, isEntry, emitSharedTable, identityIndex,
            moduleIdentities, semanticProfile, sharedShapes,
            sharedClassDeclarations, sharedHostClasses, Map.of(), Map.of());
    }

    public static JvmCodegenResult generate(ProgramNode program, CheckResult result,
                                            String sourcePath, String modulePath,
                                            Map<String, String> importResolutions,
                                            Map<String, Map<String, ClassDeclaration>> importedClasses,
                                            Map<String, Map<String, Type>> hostModules,
                                            Map<String, Map<String,
                                                List<HostModuleDeclarations.HostField>>>
                                                hostClassDeclarations,
                                            boolean isEntry,
                                            boolean emitSharedTable,
                                            CanonicalClassIdentityIndex identityIndex,
                                            Function<String, CanonicalModuleIdentity> moduleIdentities,
                                            SemanticProfile semanticProfile,
                                            List<Type> sharedShapes,
                                            Map<CanonicalClassIdentity, String> sharedClassDeclarations,
                                            Map<String, Map<String,
                                                List<HostModuleDeclarations.HostField>>>
                                                sharedHostClasses,
                                            Map<String, List<PlannedDefaultClass>>
                                                plansByModulePath,
                                            Map<String, ImportedModuleSurface>
                                                importedSurfaces) {
        Objects.requireNonNull(semanticProfile,
            "semanticProfile must not be null");
        JvmBackend backend = new JvmBackend(result.typeMap(), result.symbolTable(),
            sourcePath, modulePath, importResolutions, importedClasses,
            importedSurfaces, hostModules, hostClassDeclarations, isEntry,
            emitSharedTable, semanticProfile, sharedShapes, identityIndex,
            moduleIdentities, sharedClassDeclarations, sharedHostClasses);
        backend.plansByModulePath = Map.copyOf(plansByModulePath);
        return backend.generateProgram(program);
    }

    private static Map<String, CanonicalModuleIdentity> classificationMap(
            String modulePath, String sourcePath) {
        Map<String, CanonicalModuleIdentity> map = new LinkedHashMap<>();
        map.put("", CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        // List.of (not Set.of): modulePath and sourcePath may be
        // equal or null; iterate once per distinct non-empty path.
        for (String path : List.of(modulePath, sourcePath)) {
            if (path != null && !path.isEmpty()
                    && !map.containsKey(path)) {
                map.put(path,
                    new CanonicalModuleIdentity.ProjectModule(
                        new ProjectModuleIdentity(path, path, List.of())));
            }
        }
        return map;
    }

    public static String classNameFor(String modulePath) {
        String path = modulePath == null ? "" : modulePath;
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("[/.]")) {
            String cleaned = sanitizeSegment(segment);
            if (cleaned.isEmpty()) continue;
            sb.append(Character.toUpperCase(cleaned.charAt(0)))
                .append(cleaned.substring(1));
        }
        String result = sb.toString();
        if (result.isEmpty()) result = "Main";
        return JAVA_RESERVED.contains(result) ? result + "_" : result;
    }

    /** Sanitizes one module-path segment to a Java identifier. */
    private static String sanitizeSegment(String segment) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            boolean ok = Character.isJavaIdentifierPart(c);
            if (i == 0) ok = ok && Character.isJavaIdentifierStart(c);
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    // =========================================================================
    // Identifier translation
    // =========================================================================

    /**
     * Java reserved words. DEAL keywords are not identifiers, so this list is
     * exactly the Java keywords that can appear as DEAL identifiers.
     */
    private static final Set<String> JAVA_RESERVED = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch",
        "char", "class", "const", "continue", "default", "do", "double",
        "else", "enum", "extends", "final", "finally", "float", "for",
        "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private",
        "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while", "_",
        "true", "false", "null");

    /**
     * Emitted runtime-helper methods, by translated name → mapped Java
     * parameter types. A DEAL function whose translated name and mapped
     * parameter types match a helper exactly would emit a duplicate Java
     * method; such declarations are rejected with E6000 instead.
     */

    private static final Map<String, List<List<String>>> LEGACY_RUNTIME_HELPER_SIGNATURES = Map.ofEntries(
        Map.entry("intAdd", List.of(List.of("long", "long"),
            List.of("long", "long", "java.lang.String", "int", "int"))),
        Map.entry("intSub", List.of(List.of("long", "long"),
            List.of("long", "long", "java.lang.String", "int", "int"))),
        Map.entry("intMul", List.of(List.of("long", "long"),
            List.of("long", "long", "java.lang.String", "int", "int"))),
        Map.entry("intDiv", List.of(List.of("long", "long"),
            List.of("long", "long", "java.lang.String", "int", "int"))),
        Map.entry("intMod", List.of(List.of("long", "long"),
            List.of("long", "long", "java.lang.String", "int", "int"))),
        Map.entry("intPow", List.of(List.of("long", "long"),
            List.of("long", "long", "java.lang.String", "int", "int"))),
        Map.entry("intNeg", List.of(List.of("long"),
            List.of("long", "java.lang.String", "int", "int"))),
        Map.entry("numMod", List.of(List.of("double", "double"))),
        Map.entry("intFromNumber", List.of(List.of("double"),
            List.of("double", "java.lang.String", "int", "int"))),
        Map.entry("numberFromInt", List.of(List.of("long"))),
        Map.entry("scalarCompare", List.of(List.of("java.lang.String", "java.lang.String"))),
        Map.entry("checkInt", List.of(List.of("long"),
            List.of("long", "java.lang.String", "int", "int"))),
        Map.entry("__hasUnpairedSurrogate", List.of(List.of("java.lang.String"))),
        Map.entry("loopCond", List.of(List.of("boolean"))),
        Map.entry("booleanNotNull", List.of(List.of("java.lang.Boolean"),
            List.of("java.lang.Boolean", "java.lang.String", "int", "int"))),
        Map.entry("intFromNullable", List.of(List.of("java.lang.Long"),
            List.of("java.lang.Long", "java.lang.String", "int", "int"))),
        Map.entry("numberFromNullable", List.of(List.of("java.lang.Double"),
            List.of("java.lang.Double", "java.lang.String", "int", "int"))),
        Map.entry("checkSig", List.of(List.of("java.lang.String", "java.lang.String"))),

        Map.entry("bytesNew", List.of(List.of("long"),
            List.of("long", "java.lang.String", "int", "int"))),
        Map.entry("bytesLength", List.of(List.of("$DealRt.Bytes"),
            List.of("$DealRt.Bytes", "java.lang.String", "int", "int"))),
        Map.entry("bytesGet", List.of(List.of("$DealRt.Bytes", "long"),
            List.of("$DealRt.Bytes", "long", "java.lang.String", "int", "int"))),
        Map.entry("bytesSet", List.of(
            List.of("$DealRt.Bytes", "long", "long"),
            List.of("$DealRt.Bytes", "long", "long", "java.lang.String", "int", "int"))));

    private static final Map<String, List<List<String>>> INT32_RUNTIME_HELPER_SIGNATURES = Map.ofEntries(
        Map.entry("intAdd", List.of(List.of("int", "int"),
            List.of("int", "int", "java.lang.String", "int", "int"))),
        Map.entry("intSub", List.of(List.of("int", "int"),
            List.of("int", "int", "java.lang.String", "int", "int"))),
        Map.entry("intMul", List.of(List.of("int", "int"),
            List.of("int", "int", "java.lang.String", "int", "int"))),
        Map.entry("intDiv", List.of(List.of("int", "int"),
            List.of("int", "int", "java.lang.String", "int", "int"))),
        Map.entry("intMod", List.of(List.of("int", "int"),
            List.of("int", "int", "java.lang.String", "int", "int"))),
        Map.entry("intPow", List.of(List.of("int", "int"),
            List.of("int", "int", "java.lang.String", "int", "int"))),
        Map.entry("intNeg", List.of(List.of("int"),
            List.of("int", "java.lang.String", "int", "int"))),
        Map.entry("numMod", List.of(List.of("double", "double"))),
        Map.entry("numPow", List.of(List.of("double", "double"))),
        Map.entry("intFromNumber", List.of(List.of("double"),
            List.of("double", "java.lang.String", "int", "int"))),
        Map.entry("numberFromInt", List.of(List.of("int"))),
        Map.entry("scalarCompare", List.of(List.of("java.lang.String", "java.lang.String"))),
        Map.entry("checkInt", List.of(List.of("long"),
            List.of("long", "java.lang.String", "int", "int"))),
        Map.entry("__hasUnpairedSurrogate", List.of(List.of("java.lang.String"))),
        Map.entry("loopCond", List.of(List.of("boolean"))),
        Map.entry("booleanNotNull", List.of(List.of("java.lang.Boolean"),
            List.of("java.lang.Boolean", "java.lang.String", "int", "int"))),
        Map.entry("intFromNullable", List.of(List.of("java.lang.Integer"),
            List.of("java.lang.Integer", "java.lang.String", "int", "int"))),
        Map.entry("numberFromNullable", List.of(List.of("java.lang.Double"),
            List.of("java.lang.Double", "java.lang.String", "int", "int"))),
        Map.entry("checkSig", List.of(List.of("java.lang.String", "java.lang.String"))),
        Map.entry("bytesNew", List.of(List.of("long"),
            List.of("long", "java.lang.String", "int", "int"))),
        Map.entry("bytesLength", List.of(List.of("$DealRt.Bytes"),
            List.of("$DealRt.Bytes", "java.lang.String", "int", "int"))),
        Map.entry("bytesGet", List.of(List.of("$DealRt.Bytes", "int"),
            List.of("$DealRt.Bytes", "int", "java.lang.String", "int", "int"))),
        Map.entry("bytesSet", List.of(
            List.of("$DealRt.Bytes", "int", "int"),
            List.of("$DealRt.Bytes", "int", "int", "java.lang.String", "int", "int"))));

    private Map<String, List<List<String>>> runtimeHelperSignatures() {
        return int32Mode ? INT32_RUNTIME_HELPER_SIGNATURES
                         : LEGACY_RUNTIME_HELPER_SIGNATURES;
    }

    /**
     * Translates a DEAL identifier to a Java identifier. The encoding is
     * injective and collision-free: {@code $} → {@code $d} and {@code _} →
     * {@code $u} first (escaped names never start with {@code _}), then Java
     * reserved words are prefixed with {@code _} (reserved-prefixed names
     * always start with {@code _}). The two output sets are disjoint, so a
     * genuine DEAL identifier can never collide with a translated reserved
     * word.
     */
    /**
     * The module-class-qualified reference to a static member (method or
     * field) of this module's emitted class. Every static delegation
     * inside an anonymous wrapper/adapter class body goes through this
     * helper: an UNQUALIFIED name resolves against the anonymous class
     * first, so a DEAL function named {@code invoke} recursed into the
     * wrapper's own invoke method (a stack overflow at runtime) and a
     * function named {@code descriptor} — or a field read of that name —
     * collided with the wrapper shape class's descriptor field (a javac
     * failure after the CLI reported success). Qualification pins the
     * call to the module's static method/field, whatever the DEAL name.
     */
    private String qualifiedStatic(String member) {
        return classNameFor(modulePath) + "." + member;
    }

    public static String javaName(String dealIdentifier) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dealIdentifier.length(); i++) {
            char c = dealIdentifier.charAt(i);
            if (c == '$') sb.append("$d");
            else if (c == '_') sb.append("$u");
            else sb.append(c);
        }
        String encoded = sb.toString();
        if (JAVA_RESERVED.contains(encoded)) {
            return "_" + encoded;
        }
        return encoded;
    }

    // =========================================================================
    // Program emission
    // =========================================================================

    private JvmCodegenResult generateProgram(ProgramNode program) {
        List<StatementNode> statements = program.statements();
        for (int i = 0; i < statements.size(); i++) {
            StatementNode stmt = statements.get(i);
            if (stmt instanceof ImportDeclaration imp) {

                if (SUPPORTED_STDLIB_MODULES.contains(imp.modulePath())) {
                    importAliases.put(imp.alias(), imp.modulePath());
                    continue;
                }

                Map<String, Type> hostExports = hostModules.get(imp.modulePath());
                if (hostExports != null) {
                    if (validateHostExports(imp.modulePath(), hostExports,
                            imp.span())) {
                        hostAliases.put(imp.alias(), imp.modulePath());
                        importAliases.put(imp.alias(),
                            imp.modulePath().replace('/', '.'));
                        importAliasStatementIndices.put(imp.alias(), i);
                    }
                    continue;
                }
                String resolved = importResolutions.get(imp.modulePath());
                if (resolved != null) {
                    importAliases.put(imp.alias(), resolved);
                    importAliasStatementIndices.put(imp.alias(), i);
                } else {
                    unsupported("module imports other than compiled "
                        + "project modules and the supported stdlib "
                        + "modules (std/console, std/string, std/math, "
                        + "std/time, std/table, std/json) and host "
                        + "modules listed in the "
                        + "orchestrator's host-module map ('" + imp.modulePath() + "')",
                        imp.span());
                }
            } else if (stmt instanceof VariableDeclaration vd) {
                moduleFieldIndices.putIfAbsent(vd.name(), i);
                moduleFieldDecls.putIfAbsent(vd.name(), vd);
            } else if (stmt instanceof FunctionDeclaration fd) {
                moduleFunctions.putIfAbsent(fd.name(), fd);
                moduleFunctionIndices.putIfAbsent(fd.name(), i);
            } else if (stmt instanceof ExportDeclaration ed
                    && ed.declaration() instanceof FunctionDeclaration fd) {
                moduleFunctions.putIfAbsent(fd.name(), fd);
                moduleFunctionIndices.putIfAbsent(fd.name(), i);
            } else if (stmt instanceof ClassDeclaration cd) {
                moduleClasses.putIfAbsent(cd.name(), cd);
            } else if (stmt instanceof ExportDeclaration ed
                    && ed.declaration() instanceof ClassDeclaration cd) {

                moduleClasses.putIfAbsent(cd.name(), cd);
                if (cd.isJsonable()) jsonableClasses.add(cd);
            }
        }
        this.moduleStatements = List.copyOf(statements);

        for (Type shape : collectedShapes) {
            registerCollectedShape(shape);
        }
        computeTransitiveFieldReads();
        computeForwardFieldViolations();

        String className = classNameFor(modulePath);
        this.generatedClassName = className;
        // Defensive: a generated DEAL class name must never collide with the
        // module class name (reachable only for a module path whose derived
        // class name starts with the $C_ prefix, e.g. a path segment
        // "$C_Box"); an artifact with two same-named class declarations is
        // one javac rejects after the CLI reported success.
        for (Map.Entry<String, ClassDeclaration> ce : moduleClasses.entrySet()) {
            if (classNameForClass(ce.getKey()).equals(className)) {
                unsupported("class '" + ce.getKey() + "' whose generated "
                    + "class name collides with the module class name '"
                    + className + "'", ce.getValue().span());
            }
        }
        emitLine("// Generated by DEAL compiler — JVM backend (skeleton). DO NOT EDIT.");
        emitLine("// Source: " + sourcePath);
        emitLine("// Module: " + modulePath);
        emitLine();
        emitLine("public final class " + className + " {");
        indent++;
        emitRuntimeSupport();
        emitHostBindings();

        if (!jsonableClasses.isEmpty()
                || importAliases.containsValue("std/json")) {
            emitJsonRuntimeSupport();
        }

        // Function-wrapper shape classes are accumulated while statements
        // are emitted and spliced here, after the runtime support: the
        // class text must sit at class level, before the first member
        // that references it is ever executed.
        int wrapperInsertion = out.length();

        // Declarations (fields, functions, imports, exports) are emitted as
        // class members; every run of non-declaration module-level statements
        // is wrapped in a static initializer so it executes at class
        // initialization in source order — LuaJIT executes module-level
        // statements at load time. Interleaving members and static blocks
        // preserves the relative order of side-effecting initializers.
        moduleLevel = true;
        for (int i = 0; i < statements.size(); i++) {
            currentModuleStatementIndex = i;
            if (isModuleLevelDeclaration(statements.get(i))) {
                emitStatement(statements.get(i));
            } else {
                emitLine("static {");
                indent++;
                moduleLevel = false;
                while (i < statements.size()
                        && !isModuleLevelDeclaration(statements.get(i))) {
                    currentModuleStatementIndex = i;
                    StatementNode stmt = statements.get(i);
                    if (containsModuleReturn(stmt)) {
                        unsupported("module-level return (Java initializers cannot return)",
                            stmt.span());
                    } else {
                        emitStatement(stmt);
                        if (!statementCompletesNormally(stmt)) {
                            // Dead code after a non-completing statement:
                            // LuaJIT never executes it and javac rejects it
                            // as unreachable (JLS §14.21) — skip the rest
                            // of this static-block run. (Unreachable today:
                            // module-level returns are rejected above, but
                            // the guard keeps the invariant by
                            // construction.)
                            while (i < statements.size()
                                    && !isModuleLevelDeclaration(statements.get(i))) {
                                i++;
                            }
                            i--;
                            break;
                        }
                    }
                    i++;
                }
                i--;
                moduleLevel = true;
                indent--;
                emitLine("}");
            }
        }
        currentModuleStatementIndex = -1;
        moduleLevel = false;

        emitSharedCheckSeam();

        if (isEntry) {
            emitEntryPoint(program, className);
        }

        indent--;
        emitLine("}");

        if (emitSharedTable) {
            emitSharedScope();
        }

        if (arrayHelpers.length() > 0) {
            out.insert(wrapperInsertion, arrayHelpers.toString());
        }

        return new JvmCodegenResult(className, out.toString(), diagnostics,
            int32Mode, List.copyOf(runtimePlans));
    }

    /**
     * Emits the JVM entry point for a selected entry module (spec-v1.2
     * §No user-defined globals): {@code public static void main(String[]
     * args)} invoking the module's DEAL {@code main} export. The frontend
     * entry gate (E2010/E2011, the compilation orchestrator's v1.2
     * selected-entry rule) guarantees an exported non-async
     * {@code main(): null}; this defensive scan keeps the artifact valid
     * Java when the backend is driven directly (unit tests) without that
     * gate — a missing or mismatched {@code main} is an E6004 diagnostic
     * (the same backend gate the Lua backend raises) instead of a silent
     * broken artifact.
     */
    private void emitEntryPoint(ProgramNode program, String className) {
        FunctionDeclaration mainDecl = null;
        boolean foundAnyMain = false;
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ExportDeclaration ed
                    && ed.declaration() instanceof FunctionDeclaration fd) {
                if (fd.name().equals("main")) {
                    foundAnyMain = true;
                    if (fd.params().isEmpty()
                            && !fd.isAsync()
                            && resolveTypeNode(fd.returnType()) instanceof Type.Null) {
                        mainDecl = fd;
                    }
                }
            }
        }
        if (mainDecl == null) {
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E6004,
                "entry module must export non-async main(): null; found "
                + (foundAnyMain
                    ? "main with a different signature or an async marker"
                    : "no main export"),
                program.span()));
            return;
        }
        emitLine();
        emitLine("// Entry-module invocation (spec-v1.2 §No user-defined globals): the");
        emitLine("// backend invokes main() from the selected entry module. The reserved");
        emitLine("// async-export host mode (ISSUE-0161, parent D9) dispatches on the");
        emitLine("// generated argv marker: initialization already ran exactly once at");
        emitLine("// class load, the DEAL main() export runs exactly once here, and then");
        emitLine("// the reserved host entry selects, invokes, and checks exactly one");
        emitLine("// async export before printing the envelope line.");
        emitLine("public static void main(java.lang.String[] args) {");
        indent++;
        emitLine("if (args.length > 0 && \"" + ASYNC_EXPORT_HOST_ARG
            + "\".equals(args[0])) {");
        indent++;
        emitLine(javaName(mainDecl.name()) + "();");
        emitLine("java.lang.String line = args.length >= 3");
        indent++;
        emitLine("? $asyncExportHost(args[1], args[2])");
        emitLine(": $infrastructureEnvelope(\"invalid async-export host argv: expected [marker, exportName, returnDescriptor]\");");
        indent--;
        emitLine("java.lang.System.out.println(line);");
        emitLine("java.lang.System.out.flush();");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine(javaName(mainDecl.name()) + "();");
        indent--;
        emitLine("}");
        emitAsyncExportHostSurface(program, className);
    }

    private void emitAsyncExportHostSurface(ProgramNode program,
            String className) {
        // The per-export registry rows in source order and the invocation
        // bodies aligned by registry index (null for class exports, whose
        // selection is rejected before the invocation switch runs).
        java.util.List<String> rows = new java.util.ArrayList<>();
        java.util.List<String[]> calls = new java.util.ArrayList<>();
        for (StatementNode stmt : program.statements()) {
            if (!(stmt instanceof ExportDeclaration ed)) continue;
            if (ed.declaration() instanceof FunctionDeclaration fd) {
                java.util.List<Type> params = new java.util.ArrayList<>();
                for (Parameter p : fd.params()) {
                    params.add(resolveTypeNode(p.type()));
                }
                Type rt = resolveTypeNode(fd.returnType());
                Type.Func ft = new Type.Func(params, rt, fd.isAsync());
                String fail = fd.isAsync()
                    ? (params.isEmpty() ? "mismatch" : "param")
                    : "sync";
                rows.add("{" + quoteJavaString(fd.name()) + ", "
                    + quoteJavaString(typeDescriptor(ft)) + ", \"f\", \""
                    + fail + "\"},");
                // Parameterized exports never reach the invocation
                // switch: selection rejects their signature before it, so
                // no call body is emitted (a zero-argument call would not
                // compile against the parameterized method).
                calls.add(params.isEmpty()
                    ? (rt instanceof Type.Null
                        ? new String[]{ javaName(fd.name()) + "();",
                            "completion = null;" }
                        : new String[]{ "completion = "
                            + javaName(fd.name()) + "();" })
                    : null);
            } else if (ed.declaration() instanceof ClassDeclaration cd) {
                rows.add("{" + quoteJavaString(cd.name()) + ", "
                    + quoteJavaString(classIdentity(cd.name()))
                    + ", \"c\", \"mismatch\"},");
                calls.add(null);
            }
        }

        emitLine();
        emitLine("// ---- Reserved async-export host surface (ISSUE-0161, parent D9) ----");
        emitLine("// The production JvmAsyncExportInvoker spawns the generated");
        emitLine("// $AsyncExportHost launcher below with the reserved argv marker.");
        emitLine("// Initialization already ran exactly once at class load; the entry");
        emitLine("// dispatch ran main() exactly once; this entry then selects exactly");
        emitLine("// one export, invokes it once, checks the completion through the");
        emitLine("// $check seam (the canonical matcher realization) against the");
        emitLine("// byte-exact return descriptor, and encodes the checked value over");
        emitLine("// the JSON-encodable surface. Every $ name is unreachable from");
        emitLine("// javaName, so the reserved entry is never source-visible.");
        emitLine("private static final java.lang.String[][] $exports = new java.lang.String[][] {");
        indent++;
        if (rows.isEmpty()) {
            emitLine("};");
        } else {
            for (String row : rows) {
                emitLine(row);
            }
            emitLine("};");
        }
        indent--;

        emitLines(SELECT_HELPER_TEXT);

        emitLine("public static java.lang.String $asyncExportHost(java.lang.String exportName, java.lang.String returnDescriptor) {");
        indent++;
        emitLine("try {");
        indent++;
        emitLine("java.lang.Object[] sel = $asyncExportSelect($exports, exportName, returnDescriptor);");
        emitLine("if (sel[0] != null) {");
        indent++;
        emitLine("return $hostFailureEnvelope((java.lang.String) sel[0]);");
        indent--;
        emitLine("}");
        emitLine("java.lang.Object completion;");
        emitLine("switch (((java.lang.Integer) sel[1]).intValue()) {");
        indent++;
        for (int i = 0; i < calls.size(); i++) {
            String[] body = calls.get(i);
            if (body == null) continue;
            emitLine("case " + i + ":");
            indent++;
            for (String line : body) {
                emitLine(line);
            }
            emitLine("break;");
            indent--;
        }
        emitLine("default:");
        indent++;
        emitLine("return $hostFailureEnvelope(\"export '\" + exportName + \"' is not a function wrapper\");");
        indent--;
        indent--;
        emitLine("}");
        emitLine("java.lang.Object checked = $check(returnDescriptor, completion);");
        emitLine("java.lang.String valueJson;");
        emitLine("try {");
        indent++;
        emitLine("valueJson = $jsonValue(checked);");
        indent--;
        emitLine("} catch (DealError encode) {");
        indent++;
        emitLine("return $representationEnvelope(encode.getMessage());");
        indent--;
        emitLine("}");
        emitLine("return $valueEnvelope(returnDescriptor, valueJson);");
        indent--;
        emitLine("} catch (DealError e) {");
        indent++;
        emitLine("return $dealErrorEnvelope(e.code, e.getMessage());");
        indent--;
        emitLine("} catch (java.lang.Throwable t) {");
        indent++;
        emitLine("java.lang.Object[] cls = $classifyThrowable(t);");
        emitLine("if (\"deal-error\".equals(cls[0])) {");
        indent++;
        emitLine("return $dealErrorEnvelope((java.lang.String) cls[1], (java.lang.String) cls[2]);");
        indent--;
        emitLine("}");
        emitLine("return $infrastructureEnvelope((java.lang.String) cls[3]);");
        indent--;
        emitLine("}");
        indent--;
        emitLine("}");

        emitLines(ENVELOPE_HELPERS_TEXT.replace("__RESULT_PREFIX__",
            quoteJavaString(ASYNC_EXPORT_RESULT_PREFIX)));
        emitLines(CLASSIFY_HELPER_TEXT);
        emitJsonValueHelpers();

        // The nested launcher class: the invoker's process entry
        // ({@code <Entry>$AsyncExportHost}). It wraps the FIRST access to
        // the entry class, so a module-initialization error (the JVM
        // launcher would otherwise report an envelope-less
        // ExceptionInInitializerError before any generated code runs) and
        // a main() error classify into the envelope protocol through the
        // launcher's self-contained helpers (referencing entry statics in
        // the catch path would re-trigger a failed initializer).
        emitLine();
        emitLine("// The reserved host launcher (the production invoker's process");
        emitLine("// entry): wraps the first entry access so initialization and main()");
        emitLine("// errors classify into the envelope protocol.");
        emitLine("public static final class $AsyncExportHost {");
        indent++;
        emitLine("public static void main(java.lang.String[] args) {");
        indent++;
        emitLine("try {");
        indent++;
        emitLine(className + ".main(args);");
        indent--;
        emitLine("} catch (java.lang.Throwable t) {");
        indent++;
        emitLine("java.lang.Object[] cls = $classify(t);");
        emitLine("java.lang.String line;");
        emitLine("if (\"deal-error\".equals(cls[0])) {");
        indent++;
        emitLine("line = " + quoteJavaString(ASYNC_EXPORT_RESULT_PREFIX)
            + " + $obj(new java.lang.String[][]{ {\"status\",\"deal-error\"}, {\"code\",(java.lang.String) cls[1]}, {\"message\",(java.lang.String) cls[2]} });");
        indent--;
        emitLine("} else {");
        indent++;
        emitLine("line = " + quoteJavaString(ASYNC_EXPORT_RESULT_PREFIX)
            + " + $obj(new java.lang.String[][]{ {\"status\",\"infrastructure-failure\"}, {\"reason\",(java.lang.String) cls[3]} });");
        indent--;
        emitLine("}");
        emitLine("java.lang.System.out.println(line);");
        emitLine("java.lang.System.out.flush();");
        indent--;
        emitLine("}");
        indent--;
        emitLine("}");
        emitLines(LAUNCHER_HELPERS_TEXT);
        indent--;
        emitLine("}");
    }

    /** Emits one fixed helper body per non-indented line. */
    private void emitLines(String text) {
        for (String line : text.split("\n", -1)) {
            emitLine(line);
        }
    }

    /** The emitted generic selection helper of the async-export host
     * surface: registry-driven exact-name/duplicate/kind/signature
     * selection with the runtime half's pinned failure reasons. */
    private static final String SELECT_HELPER_TEXT = """
        static java.lang.Object[] $asyncExportSelect(java.lang.String[][] exports, java.lang.String exportName, java.lang.String returnDescriptor) {
            if (exportName == null) {
                return new java.lang.Object[]{ "exportName must be a string, got nil" };
            }
            if (returnDescriptor == null) {
                return new java.lang.Object[]{ "return descriptor is not a canonical descriptor: nil" };
            }
            java.lang.String expected = "async()->" + returnDescriptor;
            int found = -1;
            int occurrences = 0;
            for (int i = 0; i < exports.length; i++) {
                if (exports[i][0].equals(exportName)) { occurrences++; found = i; }
            }
            if (occurrences == 0) {
                return new java.lang.Object[]{ "missing export '" + exportName + "'" };
            }
            if (occurrences > 1) {
                return new java.lang.Object[]{ "duplicate export '" + exportName + "': the same wrapper appears under multiple export keys" };
            }
            java.lang.String sig = exports[found][1];
            if (!"f".equals(exports[found][2])) {
                return new java.lang.Object[]{ "export '" + exportName + "' is not a function wrapper" };
            }
            if (!sig.equals(expected)) {
                java.lang.String fail = exports[found][3];
                if ("sync".equals(fail)) {
                    return new java.lang.Object[]{ "export '" + exportName + "' is sync: expected '" + expected + "', got '" + sig + "'" };
                }
                if ("param".equals(fail)) {
                    return new java.lang.Object[]{ "export '" + exportName + "' is parameterized: expected '" + expected + "', got '" + sig + "'" };
                }
                return new java.lang.Object[]{ "export '" + exportName + "' signature mismatch: expected '" + expected + "', got '" + sig + "'" };
            }
            return new java.lang.Object[]{ null, java.lang.Integer.valueOf(found) };
        }
        """;

    /** The emitted closed-envelope emitters of the async-export host
     * surface (the shared envelope protocol realized by the Lua driver).
     * The {@code __RESULT_PREFIX__} placeholder is replaced with the
     * quoted shared marker at emission time. */
    private static final String ENVELOPE_HELPERS_TEXT = """
        static java.lang.String $envelopeQuote(java.lang.String s) {
            java.lang.StringBuilder sb = new java.lang.StringBuilder("\\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\\\\""); break;
                    case '\\\\': sb.append("\\\\\\\\"); break;
                    case '\\b': sb.append("\\\\b"); break;
                    case '\\f': sb.append("\\\\f"); break;
                    case '\\n': sb.append("\\\\n"); break;
                    case '\\r': sb.append("\\\\r"); break;
                    case '\\t': sb.append("\\\\t"); break;
                    default:
                        if (c < 0x20) sb.append(java.lang.String.format(java.util.Locale.ROOT, "\\\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            return sb.append('"').toString();
        }
        static java.lang.String $envelopeObject(java.lang.String[][] fields) {
            java.lang.StringBuilder sb = new java.lang.StringBuilder("{");
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) sb.append(',');
                sb.append($envelopeQuote(fields[i][0])).append(':').append($envelopeQuote(fields[i][1]));
            }
            return sb.append('}').toString();
        }
        static java.lang.String $valueEnvelope(java.lang.String descriptor, java.lang.String valueJson) {
            return __RESULT_PREFIX__ + $envelopeObject(new java.lang.String[][]{ {"status","value"}, {"descriptor",descriptor}, {"value",valueJson} });
        }
        static java.lang.String $dealErrorEnvelope(java.lang.String code, java.lang.String message) {
            return __RESULT_PREFIX__ + $envelopeObject(new java.lang.String[][]{ {"status","deal-error"}, {"code",code}, {"message",message} });
        }
        static java.lang.String $hostFailureEnvelope(java.lang.String reason) {
            return __RESULT_PREFIX__ + $envelopeObject(new java.lang.String[][]{ {"status","host-failure"}, {"reason",reason} });
        }
        static java.lang.String $representationEnvelope(java.lang.String reason) {
            return __RESULT_PREFIX__ + $envelopeObject(new java.lang.String[][]{ {"status","representation-failure"}, {"reason",reason} });
        }
        static java.lang.String $infrastructureEnvelope(java.lang.String reason) {
            return __RESULT_PREFIX__ + $envelopeObject(new java.lang.String[][]{ {"status","infrastructure-failure"}, {"reason",reason} });
        }
        """;

    /** The emitted throwable classifier of the async-export host surface:
     * DEAL errors (any module's nested DealError, unwrapped from a
     * failed initializer) classify as deal-error with code and message
     * unchanged; everything else is infrastructure. */
    private static final String CLASSIFY_HELPER_TEXT = """
        static java.lang.Object[] $classifyThrowable(java.lang.Throwable t) {
            java.lang.Throwable u = t;
            while (u instanceof java.lang.ExceptionInInitializerError && u.getCause() != null) {
                u = u.getCause();
            }
            if ("DealError".equals(u.getClass().getSimpleName())) {
                try {
                    java.lang.reflect.Field f = u.getClass().getDeclaredField("code");
                    f.setAccessible(true);
                    java.lang.String msg = u.getMessage();
                    if (msg == null) msg = java.lang.String.valueOf(u);
                    return new java.lang.Object[]{ "deal-error", java.lang.String.valueOf(f.get(u)), msg };
                } catch (java.lang.ReflectiveOperationException ignored) {
                    // a foreign DealError without an accessible code field is infrastructure
                }
            }
            return new java.lang.Object[]{ null, null, null, java.lang.String.valueOf(u) };
        }
        """;

    /** The emitted self-contained helpers of the nested host launcher
     * (usable even when the entry initializer failed). */
    private static final String LAUNCHER_HELPERS_TEXT = """
        static java.lang.String $quote(java.lang.String s) {
            java.lang.StringBuilder sb = new java.lang.StringBuilder("\\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\\\\""); break;
                    case '\\\\': sb.append("\\\\\\\\"); break;
                    case '\\b': sb.append("\\\\b"); break;
                    case '\\f': sb.append("\\\\f"); break;
                    case '\\n': sb.append("\\\\n"); break;
                    case '\\r': sb.append("\\\\r"); break;
                    case '\\t': sb.append("\\\\t"); break;
                    default:
                        if (c < 0x20) sb.append(java.lang.String.format(java.util.Locale.ROOT, "\\\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            return sb.append('"').toString();
        }
        static java.lang.String $obj(java.lang.String[][] fields) {
            java.lang.StringBuilder sb = new java.lang.StringBuilder("{");
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) sb.append(',');
                sb.append($quote(fields[i][0])).append(':').append($quote(fields[i][1]));
            }
            return sb.append('}').toString();
        }
        static java.lang.Object[] $classify(java.lang.Throwable t) {
            java.lang.Throwable u = t;
            while (u instanceof java.lang.ExceptionInInitializerError && u.getCause() != null) {
                u = u.getCause();
            }
            if ("DealError".equals(u.getClass().getSimpleName())) {
                try {
                    java.lang.reflect.Field f = u.getClass().getDeclaredField("code");
                    f.setAccessible(true);
                    java.lang.String msg = u.getMessage();
                    if (msg == null) msg = java.lang.String.valueOf(u);
                    return new java.lang.Object[]{ "deal-error", java.lang.String.valueOf(f.get(u)), msg };
                } catch (java.lang.ReflectiveOperationException ignored) {
                    // a foreign DealError without an accessible code field is infrastructure
                }
            }
            return new java.lang.Object[]{ null, null, null, java.lang.String.valueOf(u) };
        }
        """;

    /** Emits the value encoder of the async-export host surface: the
     * checked completion renders as JSON text over the JSON-encodable
     * surface (null, booleans, ints, numbers, strings, lists, maps,
     * tables, and the emitted primitive-array wrappers). NaN/Infinity and
     * every non-encodable value raise the DealError the orchestration
     * entry reclassifies as representation-failure — never a value and
     * never a propagated operation error. */
    private void emitJsonValueHelpers() {
        emitLines("""
            // D1: the origin (file/line/column) parameters thread through the
            // encoder recursion into every raise it performs; the short
            // overloads pass the explicit absent sentinel (null, -1, -1).
            static java.lang.String $jsonValue(java.lang.Object v) { return $jsonValue(v, null, -1, -1); }
            static java.lang.String $jsonValue(java.lang.Object v, java.lang.String oFile, int oLine, int oCol) {
                java.lang.StringBuilder sb = new java.lang.StringBuilder();
                $jsonAppend(sb, v, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), oFile, oLine, oCol);
                return sb.toString();
            }
            static void $jsonAppend(java.lang.StringBuilder sb, java.lang.Object v, java.util.Set<java.lang.Object> stack) { $jsonAppend(sb, v, stack, null, -1, -1); }
            static void $jsonAppend(java.lang.StringBuilder sb, java.lang.Object v, java.util.Set<java.lang.Object> stack, java.lang.String oFile, int oLine, int oCol) {
                if (v == null) { sb.append("null"); return; }
                if (v instanceof java.lang.Boolean b) { sb.append(b.booleanValue() ? "true" : "false"); return; }
                if (v instanceof java.lang.String s) { sb.append($envelopeQuote(s)); return; }
            """);
        emitLine(int32Mode
            ? "if (v instanceof java.lang.Integer i) { sb.append(i.toString()); return; }"
            : "if (v instanceof java.lang.Long l) { sb.append(l.toString()); return; }");
        emitLines("""
                if (v instanceof java.lang.Number n) {
                    double d = n.doubleValue();
                    if (java.lang.Double.isNaN(d)) throw new DealError("E8001", "cannot encode NaN as JSON", oFile, oLine, oCol);
                    if (java.lang.Double.isInfinite(d)) throw new DealError("E8001", "cannot encode Infinity as JSON", oFile, oLine, oCol);
                    sb.append(java.lang.Double.toString(d));
                    return;
                }
                if (v instanceof java.util.List<?> list) {
                    if (!stack.add(v)) throw new DealError("E8001", "value is not JSON-shaped", oFile, oLine, oCol);
                    sb.append('[');
                    boolean first = true;
                    for (java.lang.Object e : list) { if (!first) sb.append(','); first = false; $jsonAppend(sb, e, stack, oFile, oLine, oCol); }
                    sb.append(']');
                    stack.remove(v);
                    return;
                }
                if (v instanceof java.util.Map<?, ?> map) {
                    if (!stack.add(v)) throw new DealError("E8001", "value is not JSON-shaped", oFile, oLine, oCol);
                    sb.append('{');
                    boolean first = true;
                    for (java.util.Map.Entry<?, ?> e : map.entrySet()) { if (!first) sb.append(','); first = false; sb.append($envelopeQuote(java.lang.String.valueOf(e.getKey()))); sb.append(':'); $jsonAppend(sb, e.getValue(), stack, oFile, oLine, oCol); }
                    sb.append('}');
                    stack.remove(v);
                    return;
                }
                if (v instanceof $DealRt.Table t) {
                    if (!stack.add(v)) throw new DealError("E8001", "value is not JSON-shaped", oFile, oLine, oCol);
                    java.util.ArrayList<java.lang.Object> arr = t.$array();
                    if (arr != null) {
                        sb.append('[');
                        boolean first = true;
                        for (java.lang.Object e : arr) { if (!first) sb.append(','); first = false; $jsonAppend(sb, e, stack, oFile, oLine, oCol); }
                        sb.append(']');
                    } else {
                        sb.append('{');
                        boolean first = true;
                        for (java.util.Map.Entry<java.lang.String, java.lang.Object> e : t.$entries().entrySet()) { if (!first) sb.append(','); first = false; sb.append($envelopeQuote(e.getKey())); sb.append(':'); $jsonAppend(sb, e.getValue(), stack, oFile, oLine, oCol); }
                        sb.append('}');
                    }
                    stack.remove(v);
                    return;
                }
            """);
        emitLine("if (v instanceof $DealRt.__IntArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine(int32Mode
            ? "for (int e : a.data) { if (!first) sb.append(','); first = false; sb.append(java.lang.Integer.toString(e)); }"
            : "for (long e : a.data) { if (!first) sb.append(','); first = false; sb.append(java.lang.Long.toString(e)); }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__NumberArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (double e : a.data) { if (!first) sb.append(','); first = false; if (java.lang.Double.isNaN(e)) throw new DealError(\"E8001\", \"cannot encode NaN as JSON\", oFile, oLine, oCol); if (java.lang.Double.isInfinite(e)) throw new DealError(\"E8001\", \"cannot encode Infinity as JSON\", oFile, oLine, oCol); sb.append(java.lang.Double.toString(e)); }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__StringArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (java.lang.String e : a.data) { if (!first) sb.append(','); first = false; sb.append($envelopeQuote(e)); }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__BooleanArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (boolean e : a.data) { if (!first) sb.append(','); first = false; sb.append(e ? \"true\" : \"false\"); }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__IntOrNullArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine(int32Mode
            ? "for (java.lang.Integer e : a.data) { if (!first) sb.append(','); first = false; if (e == null) { sb.append(\"null\"); } else { sb.append(e.toString()); } }"
            : "for (java.lang.Long e : a.data) { if (!first) sb.append(','); first = false; if (e == null) { sb.append(\"null\"); } else { sb.append(e.toString()); } }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__NumberOrNullArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (java.lang.Double e : a.data) { if (!first) sb.append(','); first = false; if (e == null) { sb.append(\"null\"); } else { if (java.lang.Double.isNaN(e)) throw new DealError(\"E8001\", \"cannot encode NaN as JSON\", oFile, oLine, oCol); if (java.lang.Double.isInfinite(e)) throw new DealError(\"E8001\", \"cannot encode Infinity as JSON\", oFile, oLine, oCol); sb.append(java.lang.Double.toString(e)); } }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__StringOrNullArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (java.lang.String e : a.data) { if (!first) sb.append(','); first = false; if (e == null) { sb.append(\"null\"); } else { sb.append($envelopeQuote(e)); } }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__BooleanOrNullArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (java.lang.Boolean e : a.data) { if (!first) sb.append(','); first = false; if (e == null) { sb.append(\"null\"); } else { sb.append(e.booleanValue() ? \"true\" : \"false\"); } }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLines("""
                throw new DealError("E8001", "value is not JSON-shaped", oFile, oLine, oCol);
            }
            """);
    }

    /** Class-body members at module level (vs. load-time statements). */
    private static boolean isModuleLevelDeclaration(StatementNode stmt) {
        return stmt instanceof VariableDeclaration
            || stmt instanceof FunctionDeclaration
            || stmt instanceof ExportDeclaration
            || stmt instanceof ImportDeclaration
            || stmt instanceof ClassDeclaration;
    }

    /** True when stmt — or a nested block/if (not a nested function body) —
     * contains a return statement. Java initializers cannot contain return,
     * so module-level returns are rejected instead of emitting invalid Java. */
    private static boolean containsModuleReturn(StatementNode stmt) {
        return switch (stmt) {
            case ReturnStatement rs -> true;
            case Block b -> {
                boolean found = false;
                for (StatementNode s : b.statements()) {
                    if (containsModuleReturn(s)) { found = true; break; }
                }
                yield found;
            }
            case IfStatement is -> {
                boolean found = containsModuleReturn(is.thenBlock());
                if (!found && is.elseBranch().isPresent()) {
                    Either<IfStatement, Block> branch = is.elseBranch().get();
                    if (branch instanceof Either.Left<IfStatement, Block> left) {
                        found = containsModuleReturn(left.value());
                    } else {
                        found = containsModuleReturn(
                            ((Either.Right<IfStatement, Block>) branch).value());
                    }
                }
                yield found;
            }
            case WhileStatement ws -> containsModuleReturn(ws.body());
            case ForStatement fs -> containsModuleReturn(fs.body());
            case ForOfStatement fos -> containsModuleReturn(fos.body());
            case TryStatement ts -> containsModuleReturn(ts.tryBlock())
                || containsModuleReturn(ts.catchBlock());
            case ThrowStatement ts -> false;
            default -> false;
        };
    }

    // =========================================================================
    // Module-level call / later-field detection
    // =========================================================================

    /**
     * Computes, for every module-level function, (a) the set of module
     * fields its body reads and (b) the set of module functions its body
     * reaches by calls — both transitively, through calls to other
     * module-level functions. Used to reject module-level calls whose
     * (transitive) body reads a field, or reaches a function, declared
     * later than the call site: LuaJIT fails at load for such reads (a
     * field is nil and a function value is unassigned until its
     * declaration runs) while Java would silently read the field's
     * default value or run the hoisted method.
     */
    private void computeTransitiveFieldReads() {
        Map<String, Set<String>> directReads = new LinkedHashMap<>();
        Map<String, Set<String>> directCalls = new LinkedHashMap<>();
        Map<String, Set<String>> directImportReads = new LinkedHashMap<>();
        Map<String, Set<String>> directFieldAssigns = new LinkedHashMap<>();
        Map<String, Set<String>> directIndirectCalls = new LinkedHashMap<>();
        Map<String, Set<String>> directFnValueReads = new LinkedHashMap<>();
        for (Map.Entry<String, FunctionDeclaration> e : moduleFunctions.entrySet()) {
            Set<String> reads = new LinkedHashSet<>();
            Set<String> calls = new LinkedHashSet<>();
            Set<String> imports = new LinkedHashSet<>();
            Set<String> assigns = new LinkedHashSet<>();
            Set<String> indirect = new LinkedHashSet<>();
            Set<String> fnValues = new LinkedHashSet<>();
            collectBodyReferences(e.getValue(), reads, calls, imports, fnValues);
            collectBodyFieldAssignments(e.getValue(), assigns);
            collectBodyIndirectCalls(e.getValue(), indirect);
            directReads.put(e.getKey(), reads);
            directCalls.put(e.getKey(), calls);
            directImportReads.put(e.getKey(), imports);
            directFieldAssigns.put(e.getKey(), assigns);
            directIndirectCalls.put(e.getKey(), indirect);
            directFnValueReads.put(e.getKey(), fnValues);
        }
        for (String name : moduleFunctions.keySet()) {
            transitiveFieldReads.put(name, closureReads(name, directReads,
                directCalls, new LinkedHashMap<>(), new HashSet<>()));
            transitiveFunctionCalls.put(name, closureCalls(name, directCalls,
                new LinkedHashMap<>(), new HashSet<>()));
            transitiveImportReads.put(name, closureReads(name, directImportReads,
                directCalls, new LinkedHashMap<>(), new HashSet<>()));
            transitiveAssignedFields.put(name, closureReads(name,
                directFieldAssigns, directCalls, new LinkedHashMap<>(),
                new HashSet<>()));
            transitiveIndirectCalls.put(name, closureReads(name,
                directIndirectCalls, directCalls, new LinkedHashMap<>(),
                new HashSet<>()));
            transitiveFunctionValueReads.put(name, closureReads(name,
                directFnValueReads, directCalls, new LinkedHashMap<>(),
                new HashSet<>()));
        }
    }

    /** Transitive closure over the module-level call graph: the function
     * itself plus every module function reachable through its body's
     * calls (cycles handled by the in-progress guard). */
    private static Set<String> closureCalls(String name,
            Map<String, Set<String>> directCalls,
            Map<String, Set<String>> memo, Set<String> inProgress) {
        Set<String> cached = memo.get(name);
        if (cached != null) return cached;
        Set<String> result = new LinkedHashSet<>();
        result.add(name);
        if (inProgress.add(name)) {
            for (String callee : directCalls.getOrDefault(name, Set.of())) {
                result.addAll(closureCalls(callee, directCalls, memo,
                    inProgress));
            }
            inProgress.remove(name);
        }
        memo.put(name, result);
        return result;
    }

    /** Transitive closure over the module-level call graph (cycles handled
     * by the in-progress guard). */
    private static Set<String> closureReads(String name,
            Map<String, Set<String>> directReads,
            Map<String, Set<String>> directCalls,
            Map<String, Set<String>> memo, Set<String> inProgress) {
        Set<String> cached = memo.get(name);
        if (cached != null) return cached;
        Set<String> result = new LinkedHashSet<>(
            directReads.getOrDefault(name, Set.of()));
        if (inProgress.add(name)) {
            for (String callee : directCalls.getOrDefault(name, Set.of())) {
                result.addAll(closureReads(callee, directReads, directCalls,
                    memo, inProgress));
            }
            inProgress.remove(name);
        }
        memo.put(name, result);
        return result;
    }

    /**
     * Walks a function body collecting (a) module-field names read in value
     * positions, excluding identifiers shadowed by parameters or locals (a
     * function-local shadow of a module field is not a field read), and
     * (b) module-level functions called by name. Locals are tracked
     * scope-by-scope; nested function declarations are separate scopes and
     * unsupported by the backend (rejected later), so they are not walked.
     */
    private void collectBodyReferences(FunctionDeclaration fd,
            Set<String> fieldReads, Set<String> calledFunctions,
            Set<String> importReads, Set<String> functionValueReads) {
        Deque<Set<String>> locals = new ArrayDeque<>();
        Set<String> params = new LinkedHashSet<>();
        for (Parameter p : fd.params()) params.add(p.name());
        locals.push(params);
        collectStatementListRefs(fd.body().statements(), locals,
            fieldReads, calledFunctions, importReads, functionValueReads);
    }

    private void collectStatementListRefs(List<StatementNode> stmts,
            Deque<Set<String>> locals, Set<String> fieldReads,
            Set<String> calledFunctions, Set<String> importReads,
            Set<String> functionValueReads) {
        for (StatementNode stmt : stmts) {
            collectStatementRefs(stmt, locals, fieldReads, calledFunctions,
                importReads, functionValueReads);
        }
    }

    private void collectStatementRefs(StatementNode stmt,
            Deque<Set<String>> locals, Set<String> fieldReads,
            Set<String> calledFunctions, Set<String> importReads,
            Set<String> functionValueReads) {
        switch (stmt) {
            case VariableDeclaration vd -> {
                collectExprRefs(vd.initializer(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
                locals.peek().add(vd.name());
            }
            case ReturnStatement rs -> rs.expr().ifPresent(
                e -> collectExprRefs(e, locals, fieldReads, calledFunctions,
                    importReads, functionValueReads));
            case ExpressionStatement es ->
                collectExprRefs(es.expr(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
            case IfStatement is -> {
                collectExprRefs(is.condition(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
                collectBlockRefs(is.thenBlock(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
                if (is.elseBranch().isPresent()) {
                    switch (is.elseBranch().get()) {
                        case Either.Left<IfStatement, Block> left ->
                            collectStatementRefs(left.value(), locals,
                                fieldReads, calledFunctions, importReads,
                                functionValueReads);
                        case Either.Right<IfStatement, Block> right ->
                            collectBlockRefs(right.value(), locals,
                                fieldReads, calledFunctions, importReads,
                                functionValueReads);
                    }
                }
            }
            case WhileStatement ws -> {
                collectExprRefs(ws.condition(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
                collectBlockRefs(ws.body(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
            }
            case Block b -> collectBlockRefs(b, locals, fieldReads,
                calledFunctions, importReads, functionValueReads);
            // Unsupported statement kinds (for/for-of, try, nested
            // functions, classes, …) are rejected with E6000 when emitted;
            // nothing to walk here.
            default -> { }
        }
    }

    private void collectBlockRefs(Block b, Deque<Set<String>> locals,
            Set<String> fieldReads, Set<String> calledFunctions,
            Set<String> importReads, Set<String> functionValueReads) {
        locals.push(new LinkedHashSet<>());
        collectStatementListRefs(b.statements(), locals, fieldReads,
            calledFunctions, importReads, functionValueReads);
        locals.pop();
    }

    private void collectExprRefs(ExpressionNode e, Deque<Set<String>> locals,
            Set<String> fieldReads, Set<String> calledFunctions,
            Set<String> importReads, Set<String> functionValueReads) {
        switch (e) {
            case IdentifierExpr id -> {
                if (!isLocallyBound(locals, id.name())
                        && symbols.resolve(id.name()) instanceof Symbol.VariableSymbol
                        && moduleFieldIndices.containsKey(id.name())) {
                    fieldReads.add(id.name());
                }
                // An import alias in a value position is E6000 at emission;
                // record it anyway so a module-level call of a function
                // that reaches an import declared after the call site is
                // rejected (LuaJIT reads the not-yet-required global).
                if (importAliasStatementIndices.containsKey(id.name())) {
                    importReads.add(id.name());
                }

                if (symbols.resolve(id.name()) instanceof Symbol.FunctionSymbol
                        && moduleFunctions.containsKey(id.name())
                        && !isLocallyBound(locals, id.name())) {
                    functionValueReads.add(id.name());
                }
            }
            case BinaryExpr bin -> {
                collectExprRefs(bin.left(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
                collectExprRefs(bin.right(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
            }
            case UnaryExpr u ->
                collectExprRefs(u.expr(), locals, fieldReads, calledFunctions,
                    importReads, functionValueReads);
            case CallExpr call -> {
                if (call.callee() instanceof IdentifierExpr id
                        && symbols.resolve(id.name()) instanceof Symbol.FunctionSymbol
                        && moduleFunctions.containsKey(id.name())) {
                    calledFunctions.add(id.name());
                } else {
                    collectExprRefs(call.callee(), locals, fieldReads,
                        calledFunctions, importReads, functionValueReads);
                }
                for (ExpressionNode arg : call.args()) {
                    collectExprRefs(arg, locals, fieldReads, calledFunctions,
                        importReads, functionValueReads);
                }
            }
            // The member-access object is the import alias for an imported
            // direct call (lib.add(...)) — walked so the alias lands in
            // importReads.
            case MemberAccessExpr mae ->
                collectExprRefs(mae.object(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);

            case AwaitExpression aw ->
                collectExprRefs(aw.callee(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
            // Assignment targets are writes, not reads; LuaJIT and Java
            // agree on write order (the write happens, then the later field
            // initializer overwrites), so only the value side is walked.
            // An INDEX target's array and index expressions are value
            // positions (reads) — a later-declared field read there is a
            // load-time nil read under LuaJIT (attempt to index nil) and a
            // forward static-field reference under Java — so they are
            // walked like any other read.
            case AssignmentExpr ae -> {
                collectExprRefs(ae.value(), locals, fieldReads, calledFunctions,
                    importReads, functionValueReads);
                if (ae.target() instanceof IndexExpr idx) {
                    collectExprRefs(idx.array(), locals, fieldReads,
                        calledFunctions, importReads, functionValueReads);
                    collectExprRefs(idx.index(), locals, fieldReads,
                        calledFunctions, importReads, functionValueReads);
                }
            }

            case IndexExpr idx -> {
                collectExprRefs(idx.array(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
                collectExprRefs(idx.index(), locals, fieldReads,
                    calledFunctions, importReads, functionValueReads);
            }
            case ArrayLiteralExpr al -> {
                for (ExpressionNode elem : al.elements()) {
                    collectExprRefs(elem, locals, fieldReads, calledFunctions,
                        importReads, functionValueReads);
                }
            }

            case ObjectLiteralExpr ol -> {
                for (Property prop : ol.properties()) {
                    collectExprRefs(prop.value(), locals, fieldReads,
                        calledFunctions, importReads, functionValueReads);
                }
                if (typeOf(ol) instanceof Type.Class cls
                        && isLocalClassType(cls)) {
                    ClassDeclaration cd = moduleClasses.get(cls.name());
                    if (cd != null) {
                        for (ClassField cf : cd.fields()) {
                            cf.defaultExpr().ifPresent(
                                d -> collectExprRefs(d, locals, fieldReads,
                                    calledFunctions, importReads,
                                    functionValueReads));
                        }
                    }
                }
            }
            // Template interpolations are value positions; the literal
            // parts carry no references.
            case TemplateLiteralExpr tl -> {
                for (ExpressionNode part : tl.parts()) {
                    collectExprRefs(part, locals, fieldReads, calledFunctions,
                        importReads, functionValueReads);
                }
            }
            // Literals and unsupported forms (rejected later) are not walked.
            default -> { }
        }
    }

    private static boolean isLocallyBound(Deque<Set<String>> locals, String name) {
        for (Set<String> scope : locals) {
            if (scope.contains(name)) return true;
        }
        return false;
    }

    /**
     * Collects into {@code assigned} the module fields {@code fd}'s body
     * assigns (an assignment expression whose target is an identifier
     * resolving to a module field and not shadowed by a parameter or
     * local), in every value position (statements, call arguments,
     * object-literal property values, array-literal elements, index
     * operands, class-construction defaults) — the same shapes
     * {@link #collectExprRefs} walks. Used by
     * {@link #computeTransitiveFieldReads} for
     * {@link #transitiveAssignedFields}: a module-level call of a
     * function whose body assigns a function-typed field retargets that
     * field at load time, so the value-set walk must observe it.
     */
    private void collectBodyFieldAssignments(FunctionDeclaration fd,
            Set<String> assigned) {
        Deque<Set<String>> locals = new ArrayDeque<>();
        Set<String> params = new LinkedHashSet<>();
        for (Parameter p : fd.params()) params.add(p.name());
        locals.push(params);
        collectStatementFieldAssignments(fd.body().statements(), locals,
            assigned);
    }

    private void collectStatementFieldAssignments(List<StatementNode> stmts,
            Deque<Set<String>> locals, Set<String> assigned) {
        for (StatementNode stmt : stmts) {
            switch (stmt) {
                case VariableDeclaration vd -> {
                    collectExprFieldAssignments(vd.initializer(), locals,
                        assigned);
                    locals.peek().add(vd.name());
                }
                case ReturnStatement rs -> rs.expr().ifPresent(
                    e -> collectExprFieldAssignments(e, locals, assigned));
                case ExpressionStatement es -> collectExprFieldAssignments(
                    es.expr(), locals, assigned);
                case IfStatement is -> {
                    collectExprFieldAssignments(is.condition(), locals,
                        assigned);
                    collectBlockFieldAssignments(is.thenBlock(), locals,
                        assigned);
                    if (is.elseBranch().isPresent()) {
                        switch (is.elseBranch().get()) {
                            case Either.Left<IfStatement, Block> left ->
                                collectStatementFieldAssignments(
                                    List.of(left.value()), locals, assigned);
                            case Either.Right<IfStatement, Block> right ->
                                collectBlockFieldAssignments(right.value(),
                                    locals, assigned);
                        }
                    }
                }
                case WhileStatement ws -> {
                    collectExprFieldAssignments(ws.condition(), locals,
                        assigned);
                    collectBlockFieldAssignments(ws.body(), locals, assigned);
                }
                case Block b -> collectBlockFieldAssignments(b, locals,
                    assigned);
                // Unsupported statement kinds (for/for-of, try, nested
                // functions, classes, …) are rejected with E6000 when
                // emitted; nothing to walk here.
                default -> { }
            }
        }
    }

    private void collectBlockFieldAssignments(Block b,
            Deque<Set<String>> locals, Set<String> assigned) {
        locals.push(new LinkedHashSet<>());
        collectStatementFieldAssignments(b.statements(), locals, assigned);
        locals.pop();
    }

    private void collectExprFieldAssignments(ExpressionNode e,
            Deque<Set<String>> locals, Set<String> assigned) {
        switch (e) {
            case AssignmentExpr ae -> {
                collectExprFieldAssignments(ae.value(), locals, assigned);
                if (ae.target() instanceof IdentifierExpr id
                        && !isLocallyBound(locals, id.name())
                        && moduleFieldIndices.containsKey(id.name())) {
                    assigned.add(id.name());
                }
                // A non-identifier target's sub-expressions are value
                // positions: an assignment hidden there executes at the
                // assignment, exactly like a bare target.
                switch (ae.target()) {
                    case IndexExpr idx -> {
                        collectExprFieldAssignments(idx.array(), locals,
                            assigned);
                        collectExprFieldAssignments(idx.index(), locals,
                            assigned);
                    }
                    case MemberAccessExpr mae ->
                        collectExprFieldAssignments(mae.object(), locals,
                            assigned);
                    default -> { }
                }
            }
            case BinaryExpr bin -> {
                collectExprFieldAssignments(bin.left(), locals, assigned);
                collectExprFieldAssignments(bin.right(), locals, assigned);
            }
            case UnaryExpr u ->
                collectExprFieldAssignments(u.expr(), locals, assigned);
            case CallExpr call -> {
                collectExprFieldAssignments(call.callee(), locals, assigned);
                for (ExpressionNode arg : call.args()) {
                    collectExprFieldAssignments(arg, locals, assigned);
                }
            }

            case AwaitExpression aw ->
                collectExprFieldAssignments(aw.callee(), locals, assigned);
            case MemberAccessExpr mae ->
                collectExprFieldAssignments(mae.object(), locals, assigned);
            case IndexExpr idx -> {
                collectExprFieldAssignments(idx.array(), locals, assigned);
                collectExprFieldAssignments(idx.index(), locals, assigned);
            }
            case ArrayLiteralExpr al -> {
                for (ExpressionNode elem : al.elements()) {
                    collectExprFieldAssignments(elem, locals, assigned);
                }
            }
            case ObjectLiteralExpr ol -> {
                for (Property prop : ol.properties()) {
                    collectExprFieldAssignments(prop.value(), locals,
                        assigned);
                }
                if (typeOf(ol) instanceof Type.Class cls
                        && isLocalClassType(cls)) {
                    ClassDeclaration cd = moduleClasses.get(cls.name());
                    if (cd != null) {
                        for (ClassField cf : cd.fields()) {
                            cf.defaultExpr().ifPresent(
                                d -> collectExprFieldAssignments(d, locals,
                                    assigned));
                        }
                    }
                }
            }
            case TemplateLiteralExpr tl -> {
                for (ExpressionNode part : tl.parts()) {
                    collectExprFieldAssignments(part, locals, assigned);
                }
            }
            default -> { }
        }
    }

    private void collectBodyIndirectCalls(FunctionDeclaration fd,
            Set<String> indirect) {
        for (StatementNode stmt : fd.body().statements()) {
            collectStatementIndirectCalls(stmt, indirect);
        }
    }

    private void collectStatementIndirectCalls(StatementNode stmt,
            Set<String> indirect) {
        switch (stmt) {
            case VariableDeclaration vd ->
                collectExprIndirectCalls(vd.initializer(), indirect);
            case ReturnStatement rs -> rs.expr().ifPresent(
                e -> collectExprIndirectCalls(e, indirect));
            case ExpressionStatement es ->
                collectExprIndirectCalls(es.expr(), indirect);
            case IfStatement is -> {
                collectExprIndirectCalls(is.condition(), indirect);
                collectBlockIndirectCalls(is.thenBlock(), indirect);
                if (is.elseBranch().isPresent()) {
                    switch (is.elseBranch().get()) {
                        case Either.Left<IfStatement, Block> left ->
                            collectStatementIndirectCalls(left.value(),
                                indirect);
                        case Either.Right<IfStatement, Block> right ->
                            collectBlockIndirectCalls(right.value(), indirect);
                    }
                }
            }
            case WhileStatement ws -> {
                collectExprIndirectCalls(ws.condition(), indirect);
                collectBlockIndirectCalls(ws.body(), indirect);
            }
            case Block b -> collectBlockIndirectCalls(b, indirect);
            // Unsupported statement kinds (for/for-of, try, nested
            // functions, classes, …) are rejected with E6000 when
            // emitted; nothing to walk here.
            default -> { }
        }
    }

    private void collectBlockIndirectCalls(Block b, Set<String> indirect) {
        for (StatementNode stmt : b.statements()) {
            collectStatementIndirectCalls(stmt, indirect);
        }
    }

    private void collectExprIndirectCalls(ExpressionNode e,
            Set<String> indirect) {
        switch (e) {
            case AwaitExpression aw ->
                collectExprIndirectCalls(aw.callee(), indirect);
            case CallExpr call -> {
                ExpressionNode callee = call.callee();
                if (callee instanceof IdentifierExpr id) {
                    Symbol sym = symbols.resolve(id.name());
                    boolean direct = sym instanceof Symbol.FunctionSymbol
                        && moduleFunctions.containsKey(id.name());
                    boolean intrinsic = sym instanceof Symbol.IntrinsicSymbol;
                    if (!direct && !intrinsic
                            && typeOf(callee) instanceof Type.Func) {
                        indirect.add(id.name());
                    }
                } else if (callee instanceof MemberAccessExpr mae
                        && mae.object() instanceof IdentifierExpr oid
                        && importAliases.containsKey(oid.name())) {
                    // An imported-module member call: covered by the
                    // import-hazard analysis, not an indirect call.
                } else {
                    if (typeOf(callee) instanceof Type.Func) {
                        indirect.add(UNKNOWN_HELD_VALUE);
                    }
                    collectExprIndirectCalls(callee, indirect);
                }
                for (ExpressionNode arg : call.args()) {
                    collectExprIndirectCalls(arg, indirect);
                }
            }
            case BinaryExpr bin -> {
                collectExprIndirectCalls(bin.left(), indirect);
                collectExprIndirectCalls(bin.right(), indirect);
            }
            case UnaryExpr u ->
                collectExprIndirectCalls(u.expr(), indirect);
            case AssignmentExpr ae -> {
                collectExprIndirectCalls(ae.value(), indirect);
                switch (ae.target()) {
                    case IndexExpr idx -> {
                        collectExprIndirectCalls(idx.array(), indirect);
                        collectExprIndirectCalls(idx.index(), indirect);
                    }
                    case MemberAccessExpr mae ->
                        collectExprIndirectCalls(mae.object(), indirect);
                    default -> { }
                }
            }
            case MemberAccessExpr mae ->
                collectExprIndirectCalls(mae.object(), indirect);
            case IndexExpr idx -> {
                collectExprIndirectCalls(idx.array(), indirect);
                collectExprIndirectCalls(idx.index(), indirect);
            }
            case ArrayLiteralExpr al -> {
                for (ExpressionNode elem : al.elements()) {
                    collectExprIndirectCalls(elem, indirect);
                }
            }
            case ObjectLiteralExpr ol -> {
                for (Property prop : ol.properties()) {
                    collectExprIndirectCalls(prop.value(), indirect);
                }
                if (typeOf(ol) instanceof Type.Class cls
                        && isLocalClassType(cls)) {
                    ClassDeclaration cd = moduleClasses.get(cls.name());
                    if (cd != null) {
                        for (ClassField cf : cd.fields()) {
                            cf.defaultExpr().ifPresent(
                                d -> collectExprIndirectCalls(d, indirect));
                        }
                    }
                }
            }
            case TemplateLiteralExpr tl -> {
                for (ExpressionNode part : tl.parts()) {
                    collectExprIndirectCalls(part, indirect);
                }
            }
            default -> { }
        }
    }

    /** The name of a module field declared at or after {@code callIndex}
     * that {@code functionName}'s body reads transitively, or
     * {@code null}. {@code >=} also catches a field's own initializer
     * calling a function that reads the field being initialized
     * ({@code let x: int = f()} with {@code f} reading {@code x}: LuaJIT
     * reads nil there and fails at load, Java would read the default). */
    private String laterFieldRead(String functionName, int callIndex) {
        for (String field : transitiveFieldReads.getOrDefault(functionName,
                Set.of())) {
            Integer idx = moduleFieldIndices.get(field);
            if (idx != null && idx >= callIndex) return field;
        }
        return null;
    }

    private String laterImportRead(String functionName, int callIndex) {
        for (String alias : transitiveImportReads.getOrDefault(functionName,
                Set.of())) {
            Integer idx = importAliasStatementIndices.get(alias);
            if (idx != null && idx > callIndex) return alias;
        }
        return null;
    }

    /** The name of a module function declared at or after {@code callIndex}
     * that {@code functionName}'s body reaches transitively (the callee
     * itself included), or {@code null}. LuaJIT assigns each function
     * value at its declaration point in source order, so any module-level
     * call that reaches a not-yet-declared function fails at load with a
     * nil read; Java hoists methods and would silently run. */
    private String laterFunctionCall(String functionName, int callIndex) {
        for (String reached : transitiveFunctionCalls.getOrDefault(
                functionName, Set.of(functionName))) {
            Integer idx = moduleFunctionIndices.get(reached);
            if (idx != null && idx >= callIndex) return reached;
        }
        return null;
    }

    private String laterFunctionValueRead(String functionName, int callIndex) {
        for (String fn : transitiveFunctionValueReads.getOrDefault(
                functionName, Set.of())) {
            Integer idx = moduleFunctionIndices.get(fn);
            if (idx != null && idx >= callIndex) return fn;
        }
        return null;
    }

    private String moduleIndirectCallRisk(String fieldName) {
        // The field's runtime value at the call site is statically known
        // only when its initializer AND every module-level assignment to
        // it before the call site are bare module-function or intrinsic
        // identifiers; every other shape (a call result, an adapter
        // expression, an unknown field) is conservatively rejected. When
        // the value set IS known, the same load-time guards as for
        // direct module-level calls apply to every function the field
        // may hold: the JVM static field mirrors LuaJIT's load-time
        // local (the static-initializer interleaving preserves source
        // order and control flow), so the last executed assignment
        // determines the value on both backends.
        int callIndex = currentModuleStatementIndex;
        Set<String> possible = new LinkedHashSet<>();
        boolean unknown = collectPossibleHeldFunctions(fieldName, callIndex,
            new HashSet<>(), possible);
        if (unknown) {
            return "module-level indirect call through '" + fieldName
                + "' whose value is not statically known (an initializer "
                + "or assignment that is not a bare module-function/"
                + "intrinsic identifier)";
        }
        for (String fn : possible) {
            String later = laterFieldRead(fn, callIndex);
            if (later != null) {
                return "module-level indirect call through '" + fieldName
                    + "' whose value ('" + fn + "') (transitively) reads the "
                    + "module field '" + later + "' declared at or after the "
                    + "call site (LuaJIT fails at load with a nil read; Java "
                    + "would silently read the default value)";
            }
            String laterFn = laterFunctionCall(fn, callIndex);
            if (laterFn != null) {
                return "module-level indirect call through '" + fieldName
                    + "' whose value ('" + fn + "') reaches function '"
                    + laterFn + "' declared at or after the call site (LuaJIT "
                    + "assigns function values at their declaration point and "
                    + "fails at load with a nil read; Java hoists methods and "
                    + "would silently run)";
            }
            String laterImport = laterImportRead(fn, callIndex);
            if (laterImport != null) {
                return "module-level indirect call through '" + fieldName
                    + "' whose value ('" + fn + "') (transitively) uses the "
                    + "import '" + laterImport + "' declared at or after the "
                    + "call site (LuaJIT has not run the require yet and "
                    + "fails at load; Java would silently initialize the "
                    + "imported class)";
            }
            String indirect = transitiveIndirectCalls
                .getOrDefault(fn, Set.of()).stream().findFirst()
                .orElse(null);
            if (indirect != null) {
                return "module-level indirect call through '" + fieldName
                    + "' whose value ('" + fn + "') (transitively) invokes "
                    + "the function value '" + indirect + "' (an indirect "
                    + "call executed at load time — the held value is not "
                    + "statically known to the load-time guard, so its "
                    + "load-time hazards cannot be checked; LuaJIT fails at "
                    + "load when the invoked function reaches a "
                    + "not-yet-declared value, Java would silently run the "
                    + "hoisted method)";
            }
        }
        return null;
    }

    /**
     * The module functions assigned to {@code fieldName} by module-level
     * statements between its declaration and the call site, INCLUDING the
     * call's own top-level statement ({@code callIndex} inclusive), or
     * {@code null} when any assignment's value is not a statically known
     * bare module-function/intrinsic identifier. The JVM
     * static-initializer interleaving mirrors LuaJIT's load-time
     * execution (source order and control flow), so every function this
     * walk observes is one the field may genuinely hold at the call site.
     * The call's own statement is walked because an assignment evaluated
     * earlier within it — a sub-expression of the call's enclosing
     * initializer/condition, or a statement inside the same while/if/block
     * body — genuinely precedes the call at load time (LuaJIT executes it
     * before the read); the walk over-approximates inside that one
     * statement (assignments after the call position count too), and the
     * over-approximation only ever adds a conservative rejection, never a
     * silent divergence. A module-level CALL of another function in the
     * walked statements is a potential assignment source: the invoked
     * body's transitive assignments to the field make the value set not
     * statically known (see {@link #exprAssignedFunctions}).
     */
    private List<String> moduleLevelAssignedFunctions(String fieldName,
            int callIndex) {
        Integer decl = moduleFieldIndices.get(fieldName);
        if (decl == null) return List.of();
        List<String> result = new ArrayList<>();
        for (int i = decl + 1; i <= callIndex && i < moduleStatements.size(); i++) {
            if (!stmtAssignedFunctions(moduleStatements.get(i), fieldName,
                    result)) {
                return null;
            }
        }
        return result;
    }

    /** True when every assignment to {@code name} inside {@code stmt}
     * assigns a bare module-function or intrinsic identifier (collecting
     * the module functions into {@code out}); false when any assigned
     * value is not statically known. */
    private boolean stmtAssignedFunctions(StatementNode stmt, String name,
            List<String> out) {
        return switch (stmt) {
            case ExpressionStatement es -> exprAssignedFunctions(es.expr(),
                name, out);
            case VariableDeclaration vd -> exprAssignedFunctions(vd.initializer(),
                name, out);
            case ReturnStatement rs -> rs.expr().isEmpty()
                || exprAssignedFunctions(rs.expr().get(), name, out);
            case IfStatement is -> exprAssignedFunctions(is.condition(), name, out)
                && stmtAssignedFunctions(is.thenBlock(), name, out)
                && (is.elseBranch().isEmpty()
                    || elseBranchAssignedFunctions(is.elseBranch().get(), name, out));
            case WhileStatement ws -> exprAssignedFunctions(ws.condition(), name, out)
                && stmtAssignedFunctions(ws.body(), name, out);
            case Block b -> blockAssignedFunctions(b, name, out);
            default -> true;
        };
    }

    private boolean elseBranchAssignedFunctions(
            Either<IfStatement, Block> branch, String name, List<String> out) {
        return switch (branch) {
            case Either.Left<IfStatement, Block> left ->
                stmtAssignedFunctions(left.value(), name, out);
            case Either.Right<IfStatement, Block> right ->
                blockAssignedFunctions(right.value(), name, out);
        };
    }

    private boolean blockAssignedFunctions(Block b, String name,
            List<String> out) {
        for (StatementNode stmt : b.statements()) {
            if (!stmtAssignedFunctions(stmt, name, out)) return false;
        }
        return true;
    }

    private boolean exprAssignedFunctions(ExpressionNode e, String name,
            List<String> out) {
        return switch (e) {
            case AssignmentExpr ae -> {
                if (ae.target() instanceof IdentifierExpr id
                        && id.name().equals(name)) {
                    yield knownFunctionValue(ae.value(), out);
                }
                boolean ok = exprAssignedFunctions(ae.value(), name, out);
                if (!ok) yield false;
                // A non-identifier target's sub-expressions (an index
                // operand or a member-access object) are value positions
                // evaluated at the assignment: `t[g = one] = v` hides the
                // same load-time assignment as `{ x: (g = one) }` does.
                yield switch (ae.target()) {
                    case IndexExpr idx -> exprAssignedFunctions(idx.array(),
                        name, out)
                        && exprAssignedFunctions(idx.index(), name, out);
                    case MemberAccessExpr mae ->
                        exprAssignedFunctions(mae.object(), name, out);
                    default -> true;
                };
            }
            case BinaryExpr bin -> exprAssignedFunctions(bin.left(), name, out)
                && exprAssignedFunctions(bin.right(), name, out);
            case UnaryExpr u -> exprAssignedFunctions(u.expr(), name, out);
            case CallExpr call -> {
                // A module-level call executed before the guarded field
                // read is a potential assignment source: the invoked
                // function's body (transitively) may assign the field —
                // LuaJIT executes the call and the assignment at load,
                // so the value set must observe it (an assignment inside
                // the called body, an indirect call whose held function
                // may assign the field, or any function-typed callee
                // whose invoked body cannot be analyzed all make the
                // value set not statically known).
                if (callMayAssignField(call, name)) yield false;
                boolean ok = exprAssignedFunctions(call.callee(), name, out);
                for (ExpressionNode arg : call.args()) {
                    if (!ok) break;
                    ok = exprAssignedFunctions(arg, name, out);
                }
                yield ok;
            }
            case MemberAccessExpr mae -> exprAssignedFunctions(mae.object(), name, out);
            case IndexExpr idx -> exprAssignedFunctions(idx.array(), name, out)
                && exprAssignedFunctions(idx.index(), name, out);
            case ArrayLiteralExpr al -> {
                boolean ok = true;
                for (ExpressionNode elem : al.elements()) {
                    if (!exprAssignedFunctions(elem, name, out)) {
                        ok = false;
                        break;
                    }
                }
                yield ok;
            }
            case ObjectLiteralExpr ol -> {
                boolean ok = true;
                for (Property prop : ol.properties()) {
                    if (!exprAssignedFunctions(prop.value(), name, out)) {
                        ok = false;
                        break;
                    }
                }
                // Class-construction defaults are value positions
                // evaluated at the construction site (mirroring
                // collectExprRefs): an assignment hidden in a default
                // expression executes at load exactly like one hidden in
                // a property value.
                if (ok && typeOf(ol) instanceof Type.Class cls
                        && isLocalClassType(cls)) {
                    ClassDeclaration cd = moduleClasses.get(cls.name());
                    if (cd != null) {
                        for (ClassField cf : cd.fields()) {
                            if (cf.defaultExpr().isPresent()
                                    && !exprAssignedFunctions(
                                        cf.defaultExpr().get(), name, out)) {
                                ok = false;
                                break;
                            }
                        }
                    }
                }
                yield ok;
            }
            case TemplateLiteralExpr tl -> {
                boolean ok = true;
                for (ExpressionNode part : tl.parts()) {
                    if (!exprAssignedFunctions(part, name, out)) {
                        ok = false;
                        break;
                    }
                }
                yield ok;
            }
            default -> true;
        };
    }

    /** True when {@code value} is a bare module-function or intrinsic
     * identifier (collecting the module function's name into {@code out});
     * false for any other value shape. */
    private boolean knownFunctionValue(ExpressionNode value, List<String> out) {
        if (!(value instanceof IdentifierExpr id)) return false;
        Symbol sym = symbols.resolve(id.name());
        if (sym instanceof Symbol.FunctionSymbol
                && moduleFunctions.containsKey(id.name())) {
            out.add(id.name());
            return true;
        }
        if (sym instanceof Symbol.IntrinsicSymbol) {
            return true; // reads no fields, reaches no module functions
        }
        return false;
    }

    /** True when a module-level call of {@code call} executed before the
     * guarded field read may assign {@code name} — a direct call of a
     * module function whose body assigns the field (transitively,
     * {@link #transitiveAssignedFields}); an indirect call through a
     * function-typed module field whose static value superset
     * ({@link #fieldValueSuperset}) contains a function that (transitively)
     * assigns the field, or whose superset is unknown; or any other
     * function-typed callee (a local, parameter, call-result, or
     * member-access expression) whose invoked body cannot be analyzed.
     * Every other callee (intrinsics, non-function values) cannot assign
     * a field. Conservative — only ever adds a rejection. */
    private boolean callMayAssignField(CallExpr call, String name) {
        ExpressionNode callee = call.callee();
        if (callee instanceof IdentifierExpr cid) {
            Symbol csym = symbols.resolve(cid.name());
            if (csym instanceof Symbol.FunctionSymbol
                    && moduleFunctions.containsKey(cid.name())) {
                return transitiveAssignedFields
                    .getOrDefault(cid.name(), Set.of()).contains(name);
            }
            if (csym instanceof Symbol.IntrinsicSymbol) {
                return false; // pure conversion — reads and writes nothing
            }
            if (csym instanceof Symbol.VariableSymbol
                    && moduleFieldIndices.containsKey(cid.name())
                    && typeOf(callee) instanceof Type.Func) {
                Set<String> superset = fieldValueSuperset(cid.name());
                if (superset.contains(UNKNOWN_HELD_VALUE)) return true;
                for (String fn : superset) {
                    if (transitiveAssignedFields.getOrDefault(fn, Set.of())
                            .contains(name)) {
                        return true;
                    }
                }
                return false;
            }
            if (typeOf(callee) instanceof Type.Func) {
                // A local/parameter function-typed binding invoked in the
                // walked statements: its held value is not tracked —
                // conservative.
                return true;
            }
            return false;
        }
        // A call-result or member-access callee with function type: the
        // invoked body cannot be analyzed — conservative.
        return typeOf(callee) instanceof Type.Func;
    }

    /** The static value superset of the function-typed module field
     * {@code name}: every module function it may hold at any load-time
     * read (memoized; see {@link #fieldValueSupersets}). */
    private Set<String> fieldValueSuperset(String name) {
        Set<String> memo = fieldValueSupersets.get(name);
        if (memo != null) return memo;
        Set<String> result = fieldValueSupersetInner(name, new HashSet<>());
        fieldValueSupersets.put(name, result);
        return result;
    }

    private Set<String> fieldValueSupersetInner(String name,
            Set<String> inProgress) {
        if (!inProgress.add(name)) {
            // A cycle in the field-initializer reference graph —
            // impossible for valid programs (module fields only reference
            // earlier fields), but stay conservative.
            return Set.of(UNKNOWN_HELD_VALUE);
        }
        Set<String> result = new LinkedHashSet<>();
        VariableDeclaration vd = moduleFieldDecls.get(name);
        if (vd != null) {
            ExpressionNode init = vd.initializer();
            if (init instanceof IdentifierExpr id) {
                Symbol sym = symbols.resolve(id.name());
                if (sym instanceof Symbol.FunctionSymbol
                        && moduleFunctions.containsKey(id.name())) {
                    result.add(id.name());
                } else if (sym instanceof Symbol.IntrinsicSymbol) {
                    // The intrinsic wrapper holds no module function.
                } else if (sym instanceof Symbol.VariableSymbol
                        && moduleFieldIndices.containsKey(id.name())) {
                    // An equal-signature snapshot or an arity adapter over
                    // another function-valued field: the invoked function
                    // is that field's value, so its superset is included.
                    result.addAll(fieldValueSupersetInner(id.name(),
                        inProgress));
                } else {
                    result.add(UNKNOWN_HELD_VALUE);
                }
            } else {
                result.add(UNKNOWN_HELD_VALUE);
            }
        }
        // EVERY assignment to the field anywhere in the module (module-level
        // statements and every function body — hidden value positions
        // included) adds its bare module-function values; any non-identifier
        // assigned value makes the superset unknown. The scan
        // over-approximates (assignments in bodies that never run at load
        // count too) — used only for conservative rejection.
        boolean unknown = false;
        for (StatementNode stmt : moduleStatements) {
            if (!assignedFieldValues(stmt, name, result)) {
                unknown = true;
                break;
            }
        }
        if (!unknown) {
            for (FunctionDeclaration fd : moduleFunctions.values()) {
                if (!assignedFieldValues(fd.body(), name, result)) {
                    unknown = true;
                    break;
                }
            }
        }
        inProgress.remove(name);
        if (unknown) result.add(UNKNOWN_HELD_VALUE);
        return result;
    }

    /** True when every assignment to the module field {@code name} inside
     * {@code stmt} assigns a bare module-function identifier (collected
     * into {@code out}); false when any assigned value is not statically
     * known. */
    private boolean assignedFieldValues(StatementNode stmt, String name,
            Set<String> out) {
        return switch (stmt) {
            case ExpressionStatement es ->
                assignedFieldValuesExpr(es.expr(), name, out);
            case VariableDeclaration vd ->
                assignedFieldValuesExpr(vd.initializer(), name, out);
            case ReturnStatement rs -> rs.expr().isEmpty()
                || assignedFieldValuesExpr(rs.expr().get(), name, out);
            case IfStatement is -> assignedFieldValuesExpr(is.condition(),
                    name, out)
                && assignedFieldValues(is.thenBlock(), name, out)
                && (is.elseBranch().isEmpty()
                    || assignedFieldValuesBranch(is.elseBranch().get(), name,
                        out));
            case WhileStatement ws -> assignedFieldValuesExpr(ws.condition(),
                    name, out)
                && assignedFieldValues(ws.body(), name, out);
            case Block b -> assignedFieldValuesList(b.statements(), name, out);
            default -> true;
        };
    }

    private boolean assignedFieldValuesBranch(
            Either<IfStatement, Block> branch, String name, Set<String> out) {
        return switch (branch) {
            case Either.Left<IfStatement, Block> left ->
                assignedFieldValues(left.value(), name, out);
            case Either.Right<IfStatement, Block> right ->
                assignedFieldValuesList(right.value().statements(), name, out);
        };
    }

    private boolean assignedFieldValuesList(List<StatementNode> stmts,
            String name, Set<String> out) {
        for (StatementNode stmt : stmts) {
            if (!assignedFieldValues(stmt, name, out)) return false;
        }
        return true;
    }

    private boolean assignedFieldValuesExpr(ExpressionNode e, String name,
            Set<String> out) {
        return switch (e) {
            case AssignmentExpr ae -> {
                boolean ok = assignedFieldValuesExpr(ae.value(), name, out);
                if (!ok) yield false;
                if (ae.target() instanceof IdentifierExpr id
                        && id.name().equals(name)) {
                    if (ae.value() instanceof IdentifierExpr vid) {
                        Symbol sym = symbols.resolve(vid.name());
                        if (sym instanceof Symbol.FunctionSymbol
                                && moduleFunctions.containsKey(vid.name())) {
                            out.add(vid.name());
                            yield ok;
                        }
                        if (sym instanceof Symbol.IntrinsicSymbol) {
                            yield ok; // holds no module function
                        }
                    }
                    yield false; // a non-identifier value — unknown
                }
                yield switch (ae.target()) {
                    case IndexExpr idx ->
                        assignedFieldValuesExpr(idx.array(), name, out)
                            && assignedFieldValuesExpr(idx.index(), name, out);
                    case MemberAccessExpr mae ->
                        assignedFieldValuesExpr(mae.object(), name, out);
                    default -> ok;
                };
            }
            case BinaryExpr bin -> assignedFieldValuesExpr(bin.left(), name, out)
                && assignedFieldValuesExpr(bin.right(), name, out);
            case UnaryExpr u -> assignedFieldValuesExpr(u.expr(), name, out);
            case CallExpr call -> {
                boolean ok = assignedFieldValuesExpr(call.callee(), name, out);
                for (ExpressionNode arg : call.args()) {
                    if (!ok) break;
                    ok = assignedFieldValuesExpr(arg, name, out);
                }
                yield ok;
            }
            case MemberAccessExpr mae ->
                assignedFieldValuesExpr(mae.object(), name, out);
            case IndexExpr idx -> assignedFieldValuesExpr(idx.array(), name, out)
                && assignedFieldValuesExpr(idx.index(), name, out);
            case ArrayLiteralExpr al -> {
                boolean ok = true;
                for (ExpressionNode elem : al.elements()) {
                    if (!assignedFieldValuesExpr(elem, name, out)) {
                        ok = false;
                        break;
                    }
                }
                yield ok;
            }
            case ObjectLiteralExpr ol -> {
                boolean ok = true;
                for (Property prop : ol.properties()) {
                    if (!assignedFieldValuesExpr(prop.value(), name, out)) {
                        ok = false;
                        break;
                    }
                }
                if (ok && typeOf(ol) instanceof Type.Class cls
                        && isLocalClassType(cls)) {
                    ClassDeclaration cd = moduleClasses.get(cls.name());
                    if (cd != null) {
                        for (ClassField cf : cd.fields()) {
                            if (cf.defaultExpr().isPresent()
                                    && !assignedFieldValuesExpr(
                                        cf.defaultExpr().get(), name, out)) {
                                ok = false;
                                break;
                            }
                        }
                    }
                }
                yield ok;
            }
            case TemplateLiteralExpr tl -> {
                boolean ok = true;
                for (ExpressionNode part : tl.parts()) {
                    if (!assignedFieldValuesExpr(part, name, out)) {
                        ok = false;
                        break;
                    }
                }
                yield ok;
            }
            default -> true;
        };
    }

    /**
     * Collects into {@code possible} every module function the
     * function-typed field {@code fieldName} may hold when it is read at
     * load-time statement index {@code readIndex} (the INCLUSIVE end of
     * the assignment walk — the call's own statement is walked, see
     * {@link #moduleLevelAssignedFunctions}): the value set of its
     * initializer at its
     * declaration (see {@link #heldFunctionsAtDeclaration}) plus every
     * statically-known module function assigned to the field between the
     * declaration and {@code readIndex}. Returns true when any value is
     * not statically known (a call result, a non-field identifier, an
     * unknown adapter value, an assignment inside a load-time-called
     * function body, or a sibling indirect call whose field may hold an
     * assigning function) — the caller conservatively rejects the
     * call. The JVM static-initializer interleaving mirrors LuaJIT's
     * load-time execution, so every function collected here is one the
     * field may genuinely hold at the read.
     */
    private boolean collectPossibleHeldFunctions(String fieldName,
            int readIndex, Set<String> inProgress, Set<String> possible) {
        if (!inProgress.add(fieldName)) {
            // A cycle in the field-reference graph. Impossible for valid
            // programs (module fields only reference earlier fields —
            // forward references are rejected), but stay conservative.
            return true;
        }
        boolean unknown = heldFunctionsAtDeclaration(fieldName,
            inProgress, possible);
        List<String> assigned = moduleLevelAssignedFunctions(fieldName,
            readIndex);
        if (assigned == null) {
            unknown = true;
        } else {
            possible.addAll(assigned);
        }
        inProgress.remove(fieldName);
        return unknown;
    }

    /**
     * The value set of {@code fieldName}'s initializer evaluated at the
     * field's declaration position, collected into {@code possible}
     * (true = not statically known). A bare module-function identifier
     * holds that function; an intrinsic identifier holds the intrinsic
     * wrapper (safe — reads no fields, reaches no module functions); an
     * identifier naming another function-valued field reads THAT field
     * at the position the lowering reads it: the outer field's
     * declaration index for an equal-signature initializer (both
     * backends read the field once at initializer time — a snapshot),
     * or the CALL SITE ({@link #currentModuleStatementIndex}) for an
     * arity-extension adapter over the field (LuaJIT's adapter body
     * re-reads the binding on every invoke, and the emitted JVM adapter
     * reads the static field live — so the inner field's assignments
     * before the call site retarget the adapter). Any other initializer
     * shape is unknown.
     */
    private boolean heldFunctionsAtDeclaration(String fieldName,
            Set<String> inProgress, Set<String> possible) {
        VariableDeclaration vd = moduleFieldDecls.get(fieldName);
        if (vd == null || !(vd.initializer() instanceof IdentifierExpr id)) {
            return true;
        }
        Symbol sym = symbols.resolve(id.name());
        if (sym instanceof Symbol.FunctionSymbol
                && moduleFunctions.containsKey(id.name())) {
            possible.add(id.name());
            return false;
        }
        if (sym instanceof Symbol.IntrinsicSymbol) {
            return false;
        }
        if (sym instanceof Symbol.VariableSymbol
                && moduleFieldIndices.containsKey(id.name())) {
            Type declared = declaredFieldType(fieldName);
            int innerReadIndex = isArityAdapter(typeOf(id), declared)
                ? currentModuleStatementIndex
                : moduleFieldIndices.get(fieldName);
            return collectPossibleHeldFunctions(id.name(), innerReadIndex,
                inProgress, possible);
        }
        return true;
    }

    /** The declared type of the module field {@code fieldName}: the
     * checker-refined symbol type (annotated or inferred), falling back
     * to the initializer's static type. */
    private Type declaredFieldType(String fieldName) {
        Symbol sym = symbols.resolve(fieldName);
        if (sym instanceof Symbol.VariableSymbol vs && vs.type() != null) {
            return vs.type();
        }
        VariableDeclaration vd = moduleFieldDecls.get(fieldName);
        return vd != null ? typeOf(vd.initializer()) : Type.Error.INSTANCE;
    }

    /** True when assigning a value of static type {@code actual} to a
     * function-typed position of declared type {@code target} lowers to
     * an arity-extension adapter (the only non-equal shape
     * {@link Types#isAssignable} admits — fewer actual parameters,
     * identical prefix and return types) instead of a plain wrapper
     * read. */
    private static boolean isArityAdapter(Type actual, Type target) {
        if (!(target instanceof Type.Func tf) || !(actual instanceof Type.Func af)) {
            return false;
        }
        return Types.isAssignable(af, tf) && !Types.equals(af, tf);
    }

    /**
     * Computes, for every module-level function, the first module field
     * declared AFTER the function that its body accesses without full
     * parity, recording reads without a dominating write in
     * {@link #forwardReadViolations} and writes in
     * {@link #forwardWriteViolations}. LuaJIT: a function declared before
     * a field does not capture the module-local — the local does not
     * exist when the function value is created — so every access in its
     * body binds to the GLOBAL of the same name at call time.
     * <ul>
     * <li>A READ of a later-declared field without a dominating write
     * inside the function reads the global nil at call time and fails
     * (E8001) unless a prior write established it, while Java silently
     * reads the initialized static field — rejected rather than silently
     * diverging. Writes inside called functions do not establish
     * dominance (conservative: a callee's writes may be conditional),
     * and a write inside a taken-only branch does not dominate reads
     * after the branch (LuaJIT would read the global nil when the
     * branch is not taken) — both shapes are rejected.</li>
     * <li>A WRITE to a later-declared field is rejected outright (every
     * position: statement, block, if/else branch, return value, call
     * argument): LuaJIT writes the GLOBAL, leaving the module-local
     * untouched so later readers observe the initializer value, while
     * Java would write the static field and pollute every later reader.
     * The write-then-read shape ({@code x = 5; return x;}) is included —
     * the function's own read observes the global write under LuaJIT,
     * but the polluted Java field remains observable by later readers.
     * Writes to fields declared BEFORE the function stay allowed (the
     * upvalue/static-field write — full parity, pinned by a
     * cross-backend fixture).</li>
     * </ul>
     */
    private void computeForwardFieldViolations() {
        for (Map.Entry<String, FunctionDeclaration> e : moduleFunctions.entrySet()) {
            Integer declIdx = moduleFunctionIndices.get(e.getKey());
            if (declIdx == null) continue;
            Set<String> readViolations = new LinkedHashSet<>();
            Set<String> writeViolations = new LinkedHashSet<>();
            Deque<Set<String>> locals = new ArrayDeque<>();
            Set<String> params = new LinkedHashSet<>();
            for (Parameter p : e.getValue().params()) params.add(p.name());
            locals.push(params);
            walkDominanceList(e.getValue().body().statements(), locals,
                new LinkedHashSet<>(), declIdx, readViolations, writeViolations);
            if (!readViolations.isEmpty()) {
                forwardReadViolations.put(e.getKey(),
                    readViolations.iterator().next());
            }
            if (!writeViolations.isEmpty()) {
                forwardWriteViolations.put(e.getKey(),
                    writeViolations.iterator().next());
            }
        }
    }

    /** Straight-line dominance walk: writes persist across blocks, so a
     * write inside a block stays visible to following statements (LuaJIT
     * agreement: a global write inside a block persists after it). */
    private void walkDominanceList(List<StatementNode> stmts,
            Deque<Set<String>> locals, Set<String> written, int fnDeclIdx,
            Set<String> readViolations, Set<String> writeViolations) {
        for (StatementNode stmt : stmts) {
            walkDominanceStmt(stmt, locals, written, fnDeclIdx,
                readViolations, writeViolations);
        }
    }

    private void walkDominanceStmt(StatementNode stmt, Deque<Set<String>> locals,
            Set<String> written, int fnDeclIdx, Set<String> readViolations,
            Set<String> writeViolations) {
        switch (stmt) {
            case VariableDeclaration vd -> {
                walkDominanceExpr(vd.initializer(), locals, written,
                    fnDeclIdx, readViolations, writeViolations);
                locals.peek().add(vd.name());
            }
            case ReturnStatement rs -> rs.expr().ifPresent(
                e -> walkDominanceExpr(e, locals, written, fnDeclIdx,
                    readViolations, writeViolations));
            case ExpressionStatement es ->
                walkDominanceExpr(es.expr(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
            case IfStatement is -> {
                walkDominanceExpr(is.condition(), locals, written,
                    fnDeclIdx, readViolations, writeViolations);
                Set<String> thenWritten = new LinkedHashSet<>(written);
                locals.push(new LinkedHashSet<>());
                walkDominanceList(is.thenBlock().statements(), locals,
                    thenWritten, fnDeclIdx, readViolations, writeViolations);
                locals.pop();
                if (is.elseBranch().isPresent()) {
                    Set<String> elseWritten = new LinkedHashSet<>(written);
                    switch (is.elseBranch().get()) {
                        case Either.Left<IfStatement, Block> left -> {
                            locals.push(new LinkedHashSet<>());
                            walkDominanceStmt(left.value(), locals, elseWritten,
                                fnDeclIdx, readViolations, writeViolations);
                            locals.pop();
                        }
                        case Either.Right<IfStatement, Block> right -> {
                            locals.push(new LinkedHashSet<>());
                            walkDominanceList(right.value().statements(), locals,
                                elseWritten, fnDeclIdx, readViolations,
                                writeViolations);
                            locals.pop();
                        }
                    }
                    // Definitely written after the if/else: written before
                    // the branch plus the intersection of both branches.
                    Set<String> merged = new LinkedHashSet<>(written);
                    for (String f : thenWritten) {
                        if (elseWritten.contains(f)) merged.add(f);
                    }
                    written.clear();
                    written.addAll(merged);
                }
                // else: an if without an else branch writes nothing
                // definitely — the then-writes stay unmerged (conservative:
                // LuaJIT takes the branch at runtime, but the not-taken
                // path reads the global nil, so no dominance is claimed).
            }
            case Block b -> {
                locals.push(new LinkedHashSet<>());
                walkDominanceList(b.statements(), locals, written,
                    fnDeclIdx, readViolations, writeViolations);
                locals.pop();
            }
            case WhileStatement ws -> {
                walkDominanceExpr(ws.condition(), locals, written,
                    fnDeclIdx, readViolations, writeViolations);
                Set<String> bodyWritten = new LinkedHashSet<>(written);
                locals.push(new LinkedHashSet<>());
                walkDominanceList(ws.body().statements(), locals, bodyWritten,
                    fnDeclIdx, readViolations, writeViolations);
                locals.pop();
                // Writes inside the loop body do not dominate anything
                // after the loop: the body may execute zero times, so a
                // later read would still hit LuaJIT's global nil on the
                // not-taken path. bodyWritten is deliberately discarded
                // (conservative, like the taken-only-branch rule).
            }
            // Unsupported statement kinds (for/for-of, try, nested
            // functions, classes, …) are rejected with E6000 when emitted;
            // nothing to walk here.
            default -> { }
        }
    }

    /**
     * Walks an expression collecting reads of later-declared module fields
     * not dominated by a write (violations) and adding field writes to
     * {@code written}. Identifiers shadowed by locals/parameters are local
     * uses, not field uses (mirrors {@code collectExprRefs}); call
     * arguments are walked left to right, so a write in an earlier
     * argument is visible to a read in a later one (LuaJIT agreement).
     */
    private void walkDominanceExpr(ExpressionNode e, Deque<Set<String>> locals,
            Set<String> written, int fnDeclIdx, Set<String> readViolations,
            Set<String> writeViolations) {
        switch (e) {
            case IdentifierExpr id -> {
                String name = id.name();
                if (!isLocallyBound(locals, name)
                        && symbols.resolve(name) instanceof Symbol.VariableSymbol
                        && moduleFieldIndices.containsKey(name)) {
                    Integer idx = moduleFieldIndices.get(name);
                    if (idx != null && idx > fnDeclIdx && !written.contains(name)) {
                        readViolations.add(name);
                    }
                }
            }
            case BinaryExpr bin -> {
                walkDominanceExpr(bin.left(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
                walkDominanceExpr(bin.right(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
            }
            case UnaryExpr u ->
                walkDominanceExpr(u.expr(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
            case CallExpr call -> {

                if (call.callee() instanceof IdentifierExpr) {
                    walkDominanceExpr(call.callee(), locals, written,
                        fnDeclIdx, readViolations, writeViolations);
                }
                for (ExpressionNode arg : call.args()) {
                    walkDominanceExpr(arg, locals, written, fnDeclIdx,
                        readViolations, writeViolations);
                }
            }

            case AwaitExpression aw ->
                walkDominanceExpr(aw.callee(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
            case AssignmentExpr ae -> {
                walkDominanceExpr(ae.value(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
                if (ae.target() instanceof IdentifierExpr id) {
                    String name = id.name();
                    if (!isLocallyBound(locals, name)
                            && symbols.resolve(name) instanceof Symbol.VariableSymbol
                            && moduleFieldIndices.containsKey(name)) {
                        Integer idx = moduleFieldIndices.get(name);
                        // A write to a field declared after the function is
                        // rejected outright (global-vs-static-field
                        // divergence), in every position the assignment can
                        // appear: statement, block, if/else branch, return
                        // value, call argument.
                        if (idx != null && idx > fnDeclIdx) {
                            writeViolations.add(name);
                        }
                        written.add(name);
                    }
                } else if (ae.target() instanceof IndexExpr idx) {

                    walkDominanceExpr(idx.array(), locals, written, fnDeclIdx,
                        readViolations, writeViolations);
                    walkDominanceExpr(idx.index(), locals, written, fnDeclIdx,
                        readViolations, writeViolations);
                }
            }
            case MemberAccessExpr mae ->
                walkDominanceExpr(mae.object(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
            case IndexExpr idx -> {
                walkDominanceExpr(idx.array(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
                walkDominanceExpr(idx.index(), locals, written, fnDeclIdx,
                    readViolations, writeViolations);
            }
            case ArrayLiteralExpr al -> {
                for (ExpressionNode elem : al.elements()) {
                    walkDominanceExpr(elem, locals, written, fnDeclIdx,
                        readViolations, writeViolations);
                }
            }

            case ObjectLiteralExpr ol -> {
                for (Property prop : ol.properties()) {
                    walkDominanceExpr(prop.value(), locals, written, fnDeclIdx,
                        readViolations, writeViolations);
                }
                if (typeOf(ol) instanceof Type.Class cls
                        && isLocalClassType(cls)) {
                    ClassDeclaration cd = moduleClasses.get(cls.name());
                    if (cd != null) {
                        for (ClassField cf : cd.fields()) {
                            cf.defaultExpr().ifPresent(
                                d -> walkDominanceExpr(d, locals, written,
                                    fnDeclIdx, readViolations, writeViolations));
                        }
                    }
                }
            }
            case TemplateLiteralExpr tl -> {
                for (ExpressionNode part : tl.parts()) {
                    walkDominanceExpr(part, locals, written, fnDeclIdx,
                        readViolations, writeViolations);
                }
            }
            // Literals and unsupported forms (rejected later) are not walked.
            default -> { }
        }
    }

    // =========================================================================
    // Runtime support (emitted once per class)
    // =========================================================================

    private void emitRuntimeSupport() {
        emitLine("// ---- DEAL JVM skeleton runtime support ----");
        // The per-artifact DEAL-source path constant (D1): a same-module
        // raise site passes it as the origin file value, so the captured
        // error names the module that actually raised — a companion
        // module's throw carries the companion's OWN path (the lane
        // normalizes it back to the companion's corpus path through the
        // deployment map).
        emitLine("static final java.lang.String __SRC = "
            + quoteJavaString(sourcePath == null ? "" : sourcePath) + ";");

        emitLine("/** DEAL runtime error: the complete DEALRuntimeError field surface. */");
        emitLine("static final class DealError extends java.lang.RuntimeException {");
        emitLine("    final java.lang.String code;");
        emitLine("    final java.lang.String file;");
        emitLine("    final int line;");
        emitLine("    final int column;");
        emitLine("    final java.lang.String expected;");
        emitLine("    final java.lang.String actual;");
        emitLine("    final java.lang.Integer frames;");
        emitLine("    final java.lang.String cause;");
        emitLine("    // The explicit absent-origin path: file=null, line=-1, column=-1.");
        emitLine("    DealError(java.lang.String code, java.lang.String message) {");
        emitLine("        this(code, message, null, -1, -1, null, null, null, null);");
        emitLine("    }");
        emitLine("    // The origin-threading path: the span group rides together.");
        emitLine("    DealError(java.lang.String code, java.lang.String message, java.lang.String oFile, int oLine, int oCol) {");
        emitLine("        this(code, message, oFile, oLine, oCol, null, null, null, null);");
        emitLine("    }");
        emitLine("    // The origin path with the closed expected/actual pair (the host");
        emitLine("    // boundary raises): frames/cause stay unpopulated, never fabricated.");
        emitLine("    DealError(java.lang.String code, java.lang.String message, java.lang.String oFile, int oLine, int oCol, java.lang.String expected, java.lang.String actual) {");
        emitLine("        this(code, message, oFile, oLine, oCol, expected, actual, null, null);");
        emitLine("    }");
        emitLine("    // The complete-field path (the closed optional fields included).");
        emitLine("    DealError(java.lang.String code, java.lang.String message, java.lang.String oFile, int oLine, int oCol, java.lang.String expected, java.lang.String actual, java.lang.Integer frames, java.lang.String cause) {");
        emitLine("        super(message);");
        emitLine("        this.code = code;");
        emitLine("        this.file = oFile;");
        emitLine("        this.line = oLine;");
        emitLine("        this.column = oCol;");
        emitLine("        this.expected = expected;");
        emitLine("        this.actual = actual;");
        emitLine("        this.frames = frames;");
        emitLine("        this.cause = cause;");
        emitLine("    }");
        emitLine("}");
        if (int32Mode) {

            emitLine("// DEAL int: signed 32-bit under DEAL_V1_2_INT32 (jvm-v12-int32-bytes D1).");
            emitLine("// checkInt gates every wider/boxed value crossing a declared int boundary on");
            emitLine("// [-2147483648, 2147483647] (E8004 before the narrowing, never silent).");
            // D11 (closed table, re-derived against the on-disk sidecars):
            // every checkInt route pins "int out of safe range" — the absInt
            // result gate and the declared-int time boundary alike — so the
            // literal is the helper-wide default, and the origin-threading
            // overload carries the (today: absent) span group.
            emitLine("static int checkInt(long v) { return checkInt(v, null, -1, -1); }");
            emitLine("static int checkInt(long v, java.lang.String oFile, int oLine, int oCol) { if (v > 2147483647L || v < -2147483648L) throw new DealError(\"E8004\", \"int out of safe range\", oFile, oLine, oCol); return (int) v; }");
            emitLine("// int arithmetic: exact int32; E8004 overflow, E8005 division by zero, E8006 negative exponent.");
            // D11: intAdd's pinned text is uniform ("int out of safe range"
            // at the return/operand positions and at the declared-int
            // binding-initializer addition alike), so the origin-threading
            // overload carries the span group only.
            emitLine("static int intAdd(int a, int b) { return intAdd(a, b, null, -1, -1); }");
            emitLine("static int intAdd(int a, int b, java.lang.String oFile, int oLine, int oCol) { try { return java.lang.Math.addExact(a, b); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\", oFile, oLine, oCol); } }");
            emitLine("static int intSub(int a, int b) { return intSub(a, b, null, -1, -1); }");
            emitLine("static int intSub(int a, int b, java.lang.String oFile, int oLine, int oCol) { try { return java.lang.Math.subtractExact(a, b); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\", oFile, oLine, oCol); } }");
            emitLine("static int intMul(int a, int b) { return intMul(a, b, null, -1, -1); }");
            emitLine("static int intMul(int a, int b, java.lang.String oFile, int oLine, int oCol) { try { return java.lang.Math.multiplyExact(a, b); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\", oFile, oLine, oCol); } }");

            emitLine("static int intDiv(int a, int b) { return intDiv(a, b, null, -1, -1); }");
            emitLine("static int intDiv(int a, int b, java.lang.String oFile, int oLine, int oCol) { if (b == 0) throw new DealError(\"E8005\", \"integer division by zero\", oFile, oLine, oCol); if (a == java.lang.Integer.MIN_VALUE && b == -1) throw new DealError(\"E8004\", \"int out of safe range\", oFile, oLine, oCol); return a / b; }");
            emitLine("static int intMod(int a, int b) { return intMod(a, b, null, -1, -1); }");
            emitLine("static int intMod(int a, int b, java.lang.String oFile, int oLine, int oCol) { if (b == 0) throw new DealError(\"E8005\", \"integer division by zero\", oFile, oLine, oCol); return a % b; }");
            // NaN/Infinity first (deal/runtime.lua check_int parity); only
            // finite values outside the signed32 range report E8004 (the
            // int32-pow-overflow pin keeps the "int out of safe range" text).
            emitLine("static int intPow(int a, int b) { return intPow(a, b, null, -1, -1); }");
            emitLine("static int intPow(int a, int b, java.lang.String oFile, int oLine, int oCol) { if (b < 0) throw new DealError(\"E8006\", \"integer exponent must be non-negative\", oFile, oLine, oCol); double p = java.lang.Math.pow((double) a, (double) b); if (java.lang.Double.isNaN(p)) throw new DealError(\"E8001\", \"expected int, got NaN\", oFile, oLine, oCol, \"int\", \"NaN\", null, null); if (java.lang.Double.isInfinite(p)) throw new DealError(\"E8001\", \"expected int, got infinity\", oFile, oLine, oCol, \"int\", \"infinity\", null, null); if (p > 2147483647.0 || p < -2147483648.0) throw new DealError(\"E8004\", \"int out of safe range\", oFile, oLine, oCol); return (int) p; }");
            emitLine("static int intNeg(int a) { return intNeg(a, null, -1, -1); }");
            emitLine("static int intNeg(int a, java.lang.String oFile, int oLine, int oCol) { try { return java.lang.Math.negateExact(a); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\", oFile, oLine, oCol); } }");
            emitLine("// number %: Lua-style floored modulo (a - floor(a/b)*b), unlike Java's truncated %.");
            emitLine("static double numMod(double a, double b) { return a - java.lang.Math.floor(a / b) * b; }");
            emitLine("// number **: IEEE-754 pow with the pinned v1.2 special-case table");
            emitLine("// (SharedValueSemantics.numberPow): pow(1.0, NaN) = 1.0 and");
            emitLine("// pow(±1.0, ±Infinity) = 1.0 — the two Java-vs-IEEE deviations — corrected before Math.pow.");
            emitLine("static double numPow(double a, double b) { if (a == 1.0 && java.lang.Double.isNaN(b)) return 1.0; if (java.lang.Math.abs(a) == 1.0 && java.lang.Double.isInfinite(b)) return 1.0; return java.lang.Math.pow(a, b); }");
            emitLine("// int(v) / number(v) conversion intrinsics (E8001 bad value, E8004 out of range).");
            // D11: the E8004 arm splits by profile — the signed-int32 profile
            // pins "int out of safe range" (int-conversion-out-of-range)
            // while the legacy-safe-int arm keeps "int out of range"
            // (runtime/int-convert-range).
            emitLine("static int intFromNumber(double v) { return intFromNumber(v, null, -1, -1); }");
            emitLine("static int intFromNumber(double v, java.lang.String oFile, int oLine, int oCol) { if (java.lang.Double.isNaN(v)) throw new DealError(\"E8001\", \"expected int, got NaN\", oFile, oLine, oCol, \"int\", \"NaN\", null, null); if (java.lang.Double.isInfinite(v)) throw new DealError(\"E8001\", \"expected int, got infinity\", oFile, oLine, oCol, \"int\", \"infinity\", null, null); if (v != java.lang.Math.floor(v)) throw new DealError(\"E8001\", \"expected int, got non-integer number\", oFile, oLine, oCol, \"int\", \"number\", null, null); if (v > 2147483647.0 || v < -2147483648.0) throw new DealError(\"E8004\", \"int out of safe range\", oFile, oLine, oCol); return (int) v; }");
            emitLine("static double numberFromInt(int v) { return (double) v; }");
        } else {
            emitLine("// DEAL int safe range: ±(2^53-1), mirroring deal/runtime.lua's");
            emitLine("// check_int (v < -9007199254740991 or v > 9007199254740991 raises");
            emitLine("// E8004). Every int-producing operation checks its result, exactly");
            emitLine("// like LuaJIT's int_add = check_int(a + b) family.");
            emitLine("static long checkInt(long v) { return checkInt(v, null, -1, -1); }");
            emitLine("static long checkInt(long v, java.lang.String oFile, int oLine, int oCol) { if (v > 9007199254740991L || v < -9007199254740991L) throw new DealError(\"E8004\", \"int out of safe range\", oFile, oLine, oCol); return v; }");
            emitLine("// int arithmetic: E8004 out of safe range, E8005 division by zero, E8006 negative exponent.");
            emitLine("static long intAdd(long a, long b) { return intAdd(a, b, null, -1, -1); }");
            emitLine("static long intAdd(long a, long b, java.lang.String oFile, int oLine, int oCol) { try { return checkInt(java.lang.Math.addExact(a, b), oFile, oLine, oCol); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\", oFile, oLine, oCol); } }");
            emitLine("static long intSub(long a, long b) { return intSub(a, b, null, -1, -1); }");
            emitLine("static long intSub(long a, long b, java.lang.String oFile, int oLine, int oCol) { try { return checkInt(java.lang.Math.subtractExact(a, b), oFile, oLine, oCol); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\", oFile, oLine, oCol); } }");
            emitLine("static long intMul(long a, long b) { return intMul(a, b, null, -1, -1); }");
            emitLine("static long intMul(long a, long b, java.lang.String oFile, int oLine, int oCol) { try { return checkInt(java.lang.Math.multiplyExact(a, b), oFile, oLine, oCol); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\", oFile, oLine, oCol); } }");
            emitLine("static long intDiv(long a, long b) { return intDiv(a, b, null, -1, -1); }");
            emitLine("static long intDiv(long a, long b, java.lang.String oFile, int oLine, int oCol) { if (b == 0L) throw new DealError(\"E8005\", \"integer division by zero\", oFile, oLine, oCol); try { return checkInt(a / b, oFile, oLine, oCol); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\", oFile, oLine, oCol); } }");
            emitLine("static long intMod(long a, long b) { return intMod(a, b, null, -1, -1); }");
            emitLine("static long intMod(long a, long b, java.lang.String oFile, int oLine, int oCol) { if (b == 0L) throw new DealError(\"E8005\", \"integer division by zero\", oFile, oLine, oCol); try { return checkInt(a % b, oFile, oLine, oCol); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\", oFile, oLine, oCol); } }");
            // NaN/Infinity first, matching deal/runtime.lua's check_int (LuaJIT
            // "expected int, got infinity" for e.g. `10 ** 400`); only finite
            // values outside the int safe range report E8004.
            emitLine("static long intPow(long a, long b) { return intPow(a, b, null, -1, -1); }");
            emitLine("static long intPow(long a, long b, java.lang.String oFile, int oLine, int oCol) { if (b < 0L) throw new DealError(\"E8006\", \"integer exponent must be non-negative\", oFile, oLine, oCol); double p = java.lang.Math.pow((double) a, (double) b); if (java.lang.Double.isNaN(p)) throw new DealError(\"E8001\", \"expected int, got NaN\", oFile, oLine, oCol, \"int\", \"NaN\", null, null); if (java.lang.Double.isInfinite(p)) throw new DealError(\"E8001\", \"expected int, got infinity\", oFile, oLine, oCol, \"int\", \"infinity\", null, null); if (p > 9007199254740991.0 || p < -9007199254740991.0) throw new DealError(\"E8004\", \"int out of safe range\", oFile, oLine, oCol); return (long) p; }");
            emitLine("static long intNeg(long a) { return intNeg(a, null, -1, -1); }");
            emitLine("static long intNeg(long a, java.lang.String oFile, int oLine, int oCol) { try { return checkInt(java.lang.Math.negateExact(a), oFile, oLine, oCol); } catch (java.lang.ArithmeticException e) { throw new DealError(\"E8004\", \"int out of safe range\", oFile, oLine, oCol); } }");
            emitLine("// number %: Lua-style floored modulo (a - floor(a/b)*b), unlike Java's truncated %.");
            emitLine("static double numMod(double a, double b) { return a - java.lang.Math.floor(a / b) * b; }");
            emitLine("// int(v) / number(v) conversion intrinsics (E8001 bad value, E8004 out of range).");
            // D11: the legacy-safe-int intFromNumber E8004 arm pins
            // "int out of range" (runtime/int-convert-range) — the one
            // surviving split of the E8004 family.
            emitLine("static long intFromNumber(double v) { return intFromNumber(v, null, -1, -1); }");
            emitLine("static long intFromNumber(double v, java.lang.String oFile, int oLine, int oCol) { if (java.lang.Double.isNaN(v)) throw new DealError(\"E8001\", \"expected int, got NaN\", oFile, oLine, oCol, \"int\", \"NaN\", null, null); if (java.lang.Double.isInfinite(v)) throw new DealError(\"E8001\", \"expected int, got infinity\", oFile, oLine, oCol, \"int\", \"infinity\", null, null); if (v != java.lang.Math.floor(v)) throw new DealError(\"E8001\", \"expected int, got non-integer number\", oFile, oLine, oCol, \"int\", \"number\", null, null); if (v > 9007199254740991.0 || v < -9007199254740991.0) throw new DealError(\"E8004\", \"int out of range\", oFile, oLine, oCol); return (long) v; }");
            emitLine("static double numberFromInt(long v) { return (double) v; }");
        }
        emitLine("// ---- DEAL v1.2 bytes runtime (ISSUE-0158 int32-bytes lane) ----");
        emitLine("// The shared $DealRt.Bytes carrier wraps a Java byte[] (zero-filled");
        emitLine("// by construction); the logical length is immutable signed-int32,");
        emitLine("// reads yield unsigned 0..255, and a write changes exactly one byte");
        emitLine("// (never appends). bytesNew gates the length through checkInt");
        emitLine("// (E8004 out of range), a negative length is E8012, and allocation");
        emitLine("// exhaustion is E8001 with no published object — the runtime.lua");
        emitLine("// bytes_new error set (deal-v1.2-int32-and-bytes-architecture D4).");
        // D1: every raising helper gains the origin (file/line/column)
        // parameters and propagates them unchanged into every raise it
        // performs; the short overload passes the explicit absent sentinel
        // (null, -1, -1) — no raise site passes an actual origin yet.
        emitLine("static $DealRt.Bytes bytesNew(long length) { return bytesNew(length, null, -1, -1); }");
        emitLine("static $DealRt.Bytes bytesNew(long length, java.lang.String oFile, int oLine, int oCol) {");
        emitLine("    long n = checkInt(length, oFile, oLine, oCol);");
        emitLine("    if (n < 0L) throw new DealError(\"E8012\", \"bytes length must be non-negative\", oFile, oLine, oCol);");
        emitLine("    if (n > 2147483647L) throw new DealError(\"E8004\", " + (int32Mode ? "\"int out of safe range\"" : "\"int out of range\"") + ", oFile, oLine, oCol);");
        emitLine("    try { return new $DealRt.Bytes(new byte[(int) n]); }");
        emitLine("    catch (java.lang.OutOfMemoryError e) { throw new DealError(\"E8001\", \"bytes allocation failed\", oFile, oLine, oCol); }");
        emitLine("}");
        emitLine("// The immutable signed-int32 logical allocation length of a bytes buffer.");
        emitLine("// The helpers' int carriers match the backend-wide int mode exactly");
        emitLine("// (ISSUE-0375 carrier switch): primitive int under DEAL_V1_2_INT32 and");
        emitLine("// the legacy long carrier under LEGACY_SAFE_INT. Every emitted bytes");
        emitLine("// call site already produces the profile's int carrier for the index,");
        emitLine("// the value, and the int result, so the helper parameters follow it —");
        emitLine("// a v1.2 bytes program compiled through the default (legacy) production");
        emitLine("// invocation must never produce an artifact javac rejects. The E8012");
        emitLine("// index and E8013 value gates always run BEFORE the narrowing inside the");
        emitLine("// long-parameter legacy arms, so the (int) casts are never silent.");
        emitLine(int32Mode
            ? "static int bytesLength($DealRt.Bytes b) { return bytesLength(b, null, -1, -1); }"
            : "static long bytesLength($DealRt.Bytes b) { return bytesLength(b, null, -1, -1); }");
        emitLine(int32Mode
            ? "static int bytesLength($DealRt.Bytes b, java.lang.String oFile, int oLine, int oCol) { if (b == null) throw new DealError(\"E8001\", \"expected bytes, got null\", oFile, oLine, oCol); return b.data.length; }"
            : "static long bytesLength($DealRt.Bytes b, java.lang.String oFile, int oLine, int oCol) { if (b == null) throw new DealError(\"E8001\", \"expected bytes, got null\", oFile, oLine, oCol); return b.data.length; }");
        emitLine("// The unsigned byte (0..255) at index i, 0 <= i < b.length (E8012 otherwise).");
        emitLine(int32Mode
            ? "static int bytesGet($DealRt.Bytes b, int i) { return bytesGet(b, i, null, -1, -1); }"
            : "static long bytesGet($DealRt.Bytes b, long i) { return bytesGet(b, i, null, -1, -1); }");
        emitLine(int32Mode
            ? "static int bytesGet($DealRt.Bytes b, int i, java.lang.String oFile, int oLine, int oCol) { if (b == null) throw new DealError(\"E8001\", \"expected bytes, got null\", oFile, oLine, oCol); if (i < 0 || i >= b.data.length) throw new DealError(\"E8012\", \"bytes index out of bounds\", oFile, oLine, oCol); return b.data[i] & 0xFF; }"
            : "static long bytesGet($DealRt.Bytes b, long i, java.lang.String oFile, int oLine, int oCol) { if (b == null) throw new DealError(\"E8001\", \"expected bytes, got null\", oFile, oLine, oCol); if (i < 0 || i >= b.data.length) throw new DealError(\"E8012\", \"bytes index out of bounds\", oFile, oLine, oCol); return b.data[(int) i] & 0xFF; }");
        emitLine("// Write byte value v (0..255) at index i and return the written value.");
        emitLine("// A failed write (E8012 index, E8013 value range) changes no storage.");
        emitLine(int32Mode
            ? "static int bytesSet($DealRt.Bytes b, int i, int v) { return bytesSet(b, i, v, null, -1, -1); }"
            : "static long bytesSet($DealRt.Bytes b, long i, long v) { return bytesSet(b, i, v, null, -1, -1); }");
        emitLine(int32Mode
            ? "static int bytesSet($DealRt.Bytes b, int i, int v, java.lang.String oFile, int oLine, int oCol) { if (b == null) throw new DealError(\"E8001\", \"expected bytes, got null\", oFile, oLine, oCol); if (i < 0 || i >= b.data.length) throw new DealError(\"E8012\", \"bytes index out of bounds\", oFile, oLine, oCol); if (v < 0 || v > 255) throw new DealError(\"E8013\", \"bytes value out of range\", oFile, oLine, oCol); b.data[i] = (byte) v; return v; }"
            : "static long bytesSet($DealRt.Bytes b, long i, long v, java.lang.String oFile, int oLine, int oCol) { if (b == null) throw new DealError(\"E8001\", \"expected bytes, got null\", oFile, oLine, oCol); if (i < 0 || i >= b.data.length) throw new DealError(\"E8012\", \"bytes index out of bounds\", oFile, oLine, oCol); if (v < 0 || v > 255) throw new DealError(\"E8013\", \"bytes value out of range\", oFile, oLine, oCol); b.data[(int) i] = (byte) v; return v; }");
        emitLine("// string ordering: Unicode scalar-value order. LuaJIT orders bytewise in");
        emitLine("// UTF-8, which is scalar-value order — including supplementary characters");
        emitLine("// (String.compareTo's UTF-16 code-unit order diverges there).");
        emitLine("static int scalarCompare(java.lang.String a, java.lang.String b) { int i = 0; int j = 0; while (i < a.length() && j < b.length()) { int ca = a.codePointAt(i); int cb = b.codePointAt(j); if (ca != cb) { return java.lang.Integer.compare(ca, cb); } i += java.lang.Character.charCount(ca); j += java.lang.Character.charCount(cb); } return java.lang.Integer.compare(a.length() - i, b.length() - j); }");
        emitLine("// while conditions route through this identity helper so javac never sees a");
        emitLine("// constant-expression condition (JLS §14.21): a constant-true condition");
        emitLine("// would make statements after the loop unreachable and a constant-false");
        emitLine("// condition would make the loop body unreachable — both javac errors.");
        emitLine("static boolean loopCond(boolean v) { return v; }");
        emitLine("// Import initialization trigger (ISSUE-0096): importers invoke this");
        emitLine("// no-op so the imported class initializes (JLS §12.4.1) exactly where");
        emitLine("// LuaJIT runs require. The name contains '$', which DEAL identifiers");
        emitLine("// cannot contain, so it can never collide with a user function.");
        emitLine("static void __init$() {}");
        emitLine("// ---- JVM host ABI runtime support (ISSUE-0100) ----");
        emitLine("// Load-time presence check for one declared host export: the host");
        emitLine("// module class's static method must exist with the descriptor-derived");
        emitLine("// parameter classes (spec-v1.2 \u00a7Host ABI and interoperability: the host module runtime");
        emitLine("// object must expose every declared export; a missing declared export");
        emitLine("// is a load-time error). Extra host methods are never looked up.");
        emitLine("// D1: the origin (file/line/column) parameters thread through every");
        emitLine("// raise this helper performs; the short overload passes the explicit");
        emitLine("// absent sentinel (null, -1, -1). The missing-export text is the");
        emitLine("// pinned canonical message (the descriptor stays in the reported");
        emitLine("// expected/actual surface, not in the message).");
        emitLine("static java.lang.reflect.Method __hostMethod(java.lang.Class<?> h, java.lang.String module, java.lang.String name, java.lang.String desc, java.lang.Class<?>[] params) { return __hostMethod(h, module, name, desc, params, null, -1, -1); }");
        emitLine("static java.lang.reflect.Method __hostMethod(java.lang.Class<?> h, java.lang.String module, java.lang.String name, java.lang.String desc, java.lang.Class<?>[] params, java.lang.String oFile, int oLine, int oCol) {");
        emitLine("    try { return h.getDeclaredMethod(name, params); }");
        emitLine("    catch (java.lang.NoSuchMethodException e) {");
        emitLine("        throw new DealError(\"E8011\", \"missing host export '\" + name + \"' in module '\" + module + \"'\", oFile, oLine, oCol);");
        emitLine("    }");
        emitLine("}");
        emitLine("// Reflective invocation of a checked host export: DEAL errors the host");
        emitLine("// raises propagate as-is; other causes surface as E8010 (the host");
        emitLine("// boundary never leaks a raw foreign exception into DEAL code).");
        emitLine("static java.lang.Object __hostInvoke(java.lang.reflect.Method m, java.lang.Object[] args) { return __hostInvoke(m, args, null, -1, -1); }");
        emitLine("static java.lang.Object __hostInvoke(java.lang.reflect.Method m, java.lang.Object[] args, java.lang.String oFile, int oLine, int oCol) {");
        emitLine("    try { return m.invoke(null, args); }");
        emitLine("    catch (java.lang.reflect.InvocationTargetException e) {");
        emitLine("        java.lang.Throwable c = e.getCause();");
        emitLine("        if (c instanceof RuntimeException rr) throw rr;");
        emitLine("        if (c instanceof Error er) throw er;");
        emitLine("        throw new DealError(\"E8010\", \"host function raised: \" + c, oFile, oLine, oCol);");
        emitLine("    }");
        emitLine("    catch (java.lang.IllegalAccessException | java.lang.IllegalArgumentException e) {");
        emitLine("        throw new DealError(\"E8010\", \"host function invocation failed: \" + e, oFile, oLine, oCol);");
        emitLine("    }");
        emitLine("}");
        emitLine("// Host-boundary return check: validates the dynamic value that crossed");
        emitLine("// the untyped host boundary against the declared return descriptor.");
        emitLine("// Sync returns raise E8010 on a mismatch (host-module-abi D3 case 2);");
        emitLine("// async completion values raise E8001 at the await site (the LuaJIT");
        emitLine("// await-site completion check). ISSUE-0106 (v1.2 boundary string");
        emitLine("// validation): the string branch also scans for unpaired UTF-16");
        emitLine("// surrogate code units (spec-v1.2 \u00a7JVM value mapping) and raises");
        emitLine("// the branch's error code (E8010 sync, E8001 completion) with the");
        emitLine("// seam's rejection message. Java null is the DEAL null sentinel");
        emitLine("// (spec-v1.2 \u00a7JVM value mapping): it passes only where the declared");
        emitLine("// descriptor permits it (?T or null), and every other context rejects");
        emitLine("// it — the spec forbids exposing Java null as DEAL null across an");
        emitLine("// untyped boundary without validation. ISSUE-0303 (D1/D2/D4) extends");
        emitLine("// the checked rows: array returns validate the carrier and elements");
        emitLine("// (E8010 on any inner failure — never an internal E8001/E8003);");
        emitLine("// function-typed returns are never wrapped — a raw or foreign value");
        emitLine("// fails E8010 and a properly wrapped $DealRt.FnValue with a");
        emitLine("// byte-equal descriptor passes; class-typed returns validate nominal");
        emitLine("// identity through the shared seam (E8001 for a foreign identity).");
        emitLine("// ISSUE-0570: class-element array returns ride the shared");
        emitLine("// per-class $HostArr$ carrier with the same D2 boundary checks");
        emitLine("// (carrier/wrong-kind element failures wrap as E8010) and the");
        emitLine("// nominal E8001 propagates for a foreign-identity element — the");
        emitLine("// class-typed identity rule extended to class elements.");
        emitLine("static java.lang.Object __hostCheck(java.lang.String desc, java.lang.Object v, java.lang.String fn, boolean completion) { return __hostCheck(desc, v, fn, completion, null, -1, -1); }");
        emitLine("static java.lang.Object __hostCheck(java.lang.String desc, java.lang.Object v, java.lang.String fn, boolean completion, java.lang.String oFile, int oLine, int oCol) {");
        emitLine("    java.lang.String d = desc;");
        emitLine("    while (d.startsWith(\"?\")) {");
        emitLine("        if (v == null) return null;");
        emitLine("        d = d.substring(1);");
        emitLine("    }");
        emitLine("    if (d.startsWith(\"[\")) {");
        emitLine("        try { return $hostCheckArray(d, v, oFile, oLine, oCol); }");
        emitLine("        catch (DealError inner) {");
        emitLine("            if (\"E8001\".equals(inner.code) && inner.getMessage().startsWith(\"expected instance of \")) throw inner;");
        emitLine("            throw __hostReturnFail(completion, desc, v, __hostInnerMessage(d, v, inner), oFile, oLine, oCol);");
        emitLine("        }");
        emitLine("    }");
        emitLine("    if (d.startsWith(\"(\") || d.startsWith(\"async(\")) {");
        emitLine("        if (v instanceof $DealRt.FnValue f && checkSig(d, f.descriptor())) return f;");
        emitLine("        throw __hostReturnFail(completion, desc, v, __hostInnerMessage(d, v, null), oFile, oLine, oCol);");
        emitLine("    }");
        emitLine("    switch (d) {");
        emitLine("        case \"int\":");
        if (int32Mode) {

            emitLine("            if (v instanceof java.lang.Integer i) return checkInt(i, oFile, oLine, oCol);");
            emitLine("            if (v instanceof java.lang.Long l) return checkInt(l.longValue(), oFile, oLine, oCol);");
        } else {
            emitLine("            if (v instanceof java.lang.Long l) return checkInt(l.longValue(), oFile, oLine, oCol);");
        }
        emitLine("            break;");
        emitLine("        case \"number\":");
        emitLine("            if (v instanceof java.lang.Double dd) return dd;");
        emitLine("            break;");
        emitLine("        case \"boolean\":");
        emitLine("            if (v instanceof java.lang.Boolean b) return b;");
        emitLine("            break;");
        emitLine("        case \"string\":");
        emitLine("            if (v instanceof java.lang.String s) { java.lang.String __reason = __unpairedSurrogateReason(s); if (__reason != null) throw __hostReturnFail(completion, desc, v, __reason, oFile, oLine, oCol); return s; }");
        emitLine("            break;");
        emitLine("        case \"bytes\":");
        emitLine("            if (v instanceof $DealRt.Bytes b) return b;");
        emitLine("            break;");
        emitLine("        case \"null\":");
        emitLine("            if (v == null) return null;");
        emitLine("            break;");
        emitLine("    }");
        emitLine("    if (d.indexOf('@') == 0) return $check(desc, v, oFile, oLine, oCol);");
        emitLine("    throw __hostReturnFail(completion, desc, v, __hostInnerMessage(d, v, null), oFile, oLine, oCol);");
        emitLine("}");
        emitLine("// The host return/completion failure: a sync return carries the");
        emitLine("// Lua-mirrored \"return value 1 type mismatch: <reason>\" text with the");
        emitLine("// declared descriptor as expected and the closed runtime-kind");
        emitLine("// projection as actual; the await-site completion check keeps the");
        emitLine("// bare reason text (the LuaJIT check_type surface) and E8001.");
        emitLine("static DealError __hostReturnFail(boolean completion, java.lang.String desc, java.lang.Object v, java.lang.String reason, java.lang.String oFile, int oLine, int oCol) {");
        emitLine("    return new DealError(completion ? \"E8001\" : \"E8010\", completion ? reason : \"return value 1 type mismatch: \" + reason, oFile, oLine, oCol, desc, __hostKind(v));");
        emitLine("}");
        emitLine("// The Lua-mirrored inner reason text of a failed host-boundary check —");
        emitLine("// the composition the parameter/return wraps prepend (deal/runtime.lua's");
        emitLine("// check_type messages, byte-for-byte, including the E8010 signature-");
        emitLine("// mismatch text passed through unchanged).");
        emitLine("static java.lang.String __hostInnerMessage(java.lang.String d, java.lang.Object v, DealError inner) {");
        emitLine("    if (inner != null && (\"E8010\".equals(inner.code) || \"E8003\".equals(inner.code))) return inner.getMessage();");
        emitLine("    if (d.startsWith(\"[\")) return \"expected array\";");
        emitLine("    if (d.startsWith(\"(\") || d.startsWith(\"async(\")) return \"expected function\";");
        emitLine("    switch (d) {");
        emitLine("        case \"null\": return \"expected null\";");
        emitLine("        case \"int\":");
        emitLine("            if (v instanceof java.lang.Double dd) { if (dd.isNaN()) return \"expected int, got NaN\"; if (dd.isInfinite()) return \"expected int, got infinity\"; if (dd % 1.0 != 0.0) return \"expected int, got non-integer number\"; }");
        emitLine("            return \"expected int\";");
        emitLine("        case \"number\": return \"expected number\";");
        emitLine("        case \"boolean\": return \"expected boolean\";");
        emitLine("        case \"string\":");
        emitLine("            if (v instanceof java.lang.String s) { java.lang.String r = __unpairedSurrogateReason(s); if (r != null) return r; }");
        emitLine("            return \"expected string\";");
        emitLine("        case \"bytes\": return \"expected bytes\";");
        emitLine("        case \"table\": return \"expected table\";");
        emitLine("    }");
        emitLine("    if (d.indexOf('@') == 0) return \"expected class instance\";");
        emitLine("    return \"expected \" + d;");
        emitLine("}");
        emitLine("// The closed runtime-kind projection of a host-boundary value (the");
        emitLine("// LuaJIT type() analog): Java scalars/strings/bytes project to their");
        emitLine("// DEAL kinds, every DEAL composite (table, class instance, array");
        emitLine("// carrier, and the wrapped function-value carrier — Lua's");
        emitLine("// {__kind=\"function\"} table) projects to table, and a raw host");
        emitLine("// function value projects to function.");
        emitLine("static java.lang.String __hostKind(java.lang.Object v) {");
        emitLine("    if (v == null) return \"null\";");
        emitLine("    if (v instanceof java.lang.String) return \"string\";");
        emitLine("    if (v instanceof java.lang.Long || v instanceof java.lang.Integer || v instanceof java.lang.Double) return \"number\";");
        emitLine("    if (v instanceof java.lang.Boolean) return \"boolean\";");
        emitLine("    if (v instanceof $DealRt.Bytes) return \"bytes\";");
        emitLine("    if (v instanceof $DealRt.FnValue) return \"table\";");
        emitLine("    for (java.lang.Class<?> k : v.getClass().getInterfaces()) { int abstractMethods = 0; for (java.lang.reflect.Method m : k.getMethods()) { if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) abstractMethods++; } if (abstractMethods == 1) return \"function\"; }");
        emitLine("    return \"table\";");
        emitLine("}");
        emitLine("// The malformed-encoding reason of a string carrying an unpaired UTF-16");
        emitLine("// surrogate code unit (the JVM analog of the LuaJIT UTF-8 walk's split,");
        emitLine("// ISSUE-0598): a string that is exactly one unpaired surrogate code");
        emitLine("// point names the surrogate (the complete ED A0..BF sequence analog);");
        emitLine("// every other malformed string is the general invalid-encoding reason.");
        emitLine("// Returns null for a scalar-valid string.");
        emitLine("static java.lang.String __unpairedSurrogateReason(java.lang.String s) {");
        emitLine("    boolean malformed = false;");
        emitLine("    for (int i = 0; i < s.length(); i++) {");
        emitLine("        char c = s.charAt(i);");
        emitLine("        if (java.lang.Character.isHighSurrogate(c) && i + 1 < s.length() && java.lang.Character.isLowSurrogate(s.charAt(i + 1))) { i++; }");
        emitLine("        else if (java.lang.Character.isHighSurrogate(c) || java.lang.Character.isLowSurrogate(c)) { malformed = true; break; }");
        emitLine("    }");
        emitLine("    if (!malformed) return null;");
        emitLine("    return s.length() == 1 ? \"expected string, got UTF-16 surrogate code point\" : \"expected string, got invalid UTF-8 encoding\";");
        emitLine("}");
        emitLine("// Host-boundary parameter check (ISSUE-0303 D2): every wrapper");
        emitLine("// parameter is validated against the declared parameter descriptor");
        emitLine("// at the call. Any inner failure — scalar/string/nullable kinds,");
        emitLine("// array carrier/element mismatches, function descriptor deltas —");
        emitLine("// raises E8010 \"parameter {i} type mismatch\" (never E8001/E8003");
        emitLine("// from the boundary, the LuaJIT reference wrap shape); class-typed");
        emitLine("// parameters keep the nominal E8001 identity check unwrapped (D4).");
        emitLine("// The DEAL null (Java null here) passes a nullable descriptor's ?");
        emitLine("// prefix through. The checked value crosses to the host method as");
        emitLine("// the shared carrier (array wrapper / typed $DealRt function");
        emitLine("// wrapper / synthesized record), never a converted or lambda");
        emitLine("// value. ISSUE-0570: class-element array parameters ride the");
        emitLine("// shared per-class $HostArr$ carrier with the same D2 boundary");
        emitLine("// checks (carrier/wrong-kind element failures wrap as E8010) and");
        emitLine("// the nominal E8001 propagates for a foreign-identity element —");
        emitLine("// the class-typed identity rule extended to class elements.");
        emitLine("static java.lang.Object __hostParamCheck(int i, java.lang.String desc, java.lang.Object v) { return __hostParamCheck(i, desc, v, null, -1, -1); }");
        emitLine("static java.lang.Object __hostParamCheck(int i, java.lang.String desc, java.lang.Object v, java.lang.String oFile, int oLine, int oCol) {");
        emitLine("    java.lang.String d = desc;");
        emitLine("    while (d.startsWith(\"?\")) {");
        emitLine("        if (v == null) return null;");
        emitLine("        d = d.substring(1);");
        emitLine("    }");
        emitLine("    if (d.indexOf('@') == 0) return $check(desc, v, oFile, oLine, oCol);");
        emitLine("    try {");
        emitLine("        if (d.startsWith(\"[\")) return $hostCheckArray(d, v, oFile, oLine, oCol);");
        emitLine("        return $check(desc, v, oFile, oLine, oCol);");
        emitLine("    } catch (DealError inner) {");
        emitLine("        if (\"E8001\".equals(inner.code) && inner.getMessage().startsWith(\"expected instance of \")) throw inner;");
        emitLine("        throw new DealError(\"E8010\", \"parameter \" + i + \" type mismatch: \" + __hostInnerMessage(d, v, inner), oFile, oLine, oCol, desc, __hostKind(v));");
        emitLine("    }");
        emitLine("}");
        emitLine("// Load-time capture of a declared host class's <C>_defaults map");
        emitLine("// (ISSUE-0303 D4): the synthesized record construction depends on");
        emitLine("// the host's mandatory defaults field; a missing or non-map field");
        emitLine("// raises the load-time E8011.");
        emitLine("static java.util.Map<java.lang.String, java.lang.Object> __hostDefaults(java.lang.Class<?> h, java.lang.String module, java.lang.String name) { return __hostDefaults(h, module, name, null, -1, -1); }");
        emitLine("static java.util.Map<java.lang.String, java.lang.Object> __hostDefaults(java.lang.Class<?> h, java.lang.String module, java.lang.String name, java.lang.String oFile, int oLine, int oCol) {");
        emitLine("    try {");
        emitLine("        java.lang.reflect.Field f = h.getDeclaredField(name + \"_defaults\");");
        emitLine("        f.setAccessible(true);");
        emitLine("        java.lang.Object v = f.get(null);");
        emitLine("        if (v instanceof java.util.Map m) return m;");
        emitLine("        throw new DealError(\"E8011\", \"host class '\" + name + \"' in module '\" + module + \"' has a non-map defaults value\", oFile, oLine, oCol);");
        emitLine("    } catch (java.lang.NoSuchFieldException e) {");
        emitLine("        throw new DealError(\"E8011\", \"host class '\" + name + \"' in module '\" + module + \"' is missing its defaults field (\" + name + \"_defaults)\", oFile, oLine, oCol);");
        emitLine("    } catch (java.lang.IllegalAccessException e) {");
        emitLine("        throw new DealError(\"E8011\", \"host class '\" + name + \"' in module '\" + module + \"' defaults are inaccessible\", oFile, oLine, oCol);");
        emitLine("    }");
        emitLine("}");
        emitLine("// Defensive missing-default lookup for a host-class construction");
        emitLine("// omitting a required field (checker-gated unreachable: the");
        emitLine("// checker rejects omissions of required fields without a");
        emitLine("// declaration default, and host declarations carry no default");
        emitLine("// expressions in the corpus). Raises E8001 instead of storing");
        emitLine("// a silent zero value.");
        emitLine("static java.lang.Object __hostMissingDefault(java.util.Map<java.lang.String, java.lang.Object> defaults, java.lang.String name, java.lang.String cls) { return __hostMissingDefault(defaults, name, cls, null, -1, -1); }");
        emitLine("static java.lang.Object __hostMissingDefault(java.util.Map<java.lang.String, java.lang.Object> defaults, java.lang.String name, java.lang.String cls, java.lang.String oFile, int oLine, int oCol) {");
        emitLine("    java.lang.Object v = defaults.get(name);");
        emitLine("    if (v == null) throw new DealError(\"E8001\", \"missing default for field '\" + name + \"' of class '\" + cls + \"'\", oFile, oLine, oCol);");
        emitLine("    return v;");
        emitLine("}");
        emitLine("// ---- DEAL classes and tables (ISSUE-0095) ----");
        emitLine("// Nominal identity base: every generated DEAL class extends $Base and");
        emitLine("// carries its spec ClassDescriptor (@<modulePath>/<Name>). The shared");
        emitLine("// runtime-check seam $check(descriptor, value) (ISSUE-0110, emitted");
        emitLine("// after the module body) dispatches the nominal checks on those");
        emitLine("// descriptor strings; javaName translates user identifiers without");
        emitLine("// raw '$', so no user function or class can collide with the fixed");
        emitLine("// $check helper.");
        emitLine("static class $Base {");
        emitLine("    final java.lang.String $identity;");
        emitLine("    $Base(java.lang.String identity) { this.$identity = identity; }");
        emitLine("}");
        emitLine("// Minimal DEAL table: the shared $DealRt.Table class (emitted by the");
        emitLine("// entry module's artifact — every module references the same class, so");
        emitLine("// table values cross module boundaries with shared identity). Property");
        emitLine("// values box: DEAL ints are Long, numbers Double, booleans Boolean,");
        emitLine("// strings String, classes the generated class instances, nested tables");
        emitLine("// $DealRt.Table.");
        emitLine("// ISSUE-0102 table field writes: $tPut stores the value and");
        emitLine("// returns it (generic, so an expression-position write keeps");
        emitLine("// its DEAL value); $tRemove deletes a dynamic key. DEAL table");
        emitLine("// keys are strings; the call site's key expression is");
        emitLine("// string-typed, so the cast is a defensive static proof.");
        emitLine("static <V> V $tPut($DealRt.Table t, java.lang.String k, V v) { t.put(k, v); return v; }");
        emitLine("static void $tRemove($DealRt.Table t, java.lang.Object k) { t.remove((java.lang.String) k); }");
        emitLine("static $DealRt.__StringArray __tableKeys($DealRt.Table t) { return new $DealRt.__StringArray(t.keys()); }");
        emitLine("// ISSUE-0102 Error support: unwrap the DEAL code of any");
        emitLine("// RuntimeException whose class is an emitted module's nested");
        emitLine("// DealError (each module declares its own, so no shared type");
        emitLine("// exists). Mirrors the runner's reportError unwrap.");
        emitLine("static java.lang.String __errorCode(java.lang.Object e) {");
        emitLine("    if (e == null || !\"DealError\".equals(e.getClass().getSimpleName())) return null;");
        emitLine("    try {");
        emitLine("        java.lang.reflect.Field f = e.getClass().getDeclaredField(\"code\");");
        emitLine("        f.setAccessible(true);");
        emitLine("        return java.lang.String.valueOf(f.get(e));");
        emitLine("    } catch (java.lang.ReflectiveOperationException ex) { return null; }");
        emitLine("}");
        emitLine("// Human-readable DEAL identity of a dynamic value for E8001 messages");
        emitLine("// (mirrors LuaJIT's actual_class reporting in check_type).");
        emitLine("static java.lang.String $describe(java.lang.Object v) {");
        emitLine("    if (v instanceof $Base b) return b.$identity;");
        emitLine("    if (v == null) return \"null\";");
        emitLine("    if (v instanceof $DealRt.Table) return \"table\";");
        emitLine("    return v.getClass().getSimpleName();");
        emitLine("}");
        emitLine("// The closed runtime-kind projection of a dynamic value (D4):");
        emitLine("// the Lua `type` vocabulary the boundary raises pin as the");
        emitLine("// `actual` field (nil/string/number/boolean/table), extended with");
        emitLine("// the shared bytes and function carriers and the module-qualified");
        emitLine("// identity of a class instance.");
        emitLine("static java.lang.String $kindOf(java.lang.Object v) {");
        emitLine("    if (v == null) return \"nil\";");
        emitLine("    if (v instanceof java.lang.String) return \"string\";");
        emitLine("    if (v instanceof java.lang.Double) return \"number\";");
        emitLine("    if (v instanceof java.lang.Integer || v instanceof java.lang.Long) return \"number\";");
        emitLine("    if (v instanceof java.lang.Boolean) return \"boolean\";");
        emitLine("    if (v instanceof $DealRt.Table) return \"table\";");
        emitLine("    if (v instanceof $DealRt.Bytes) return \"bytes\";");
        emitLine("    if (v instanceof $DealRt.FnValue) return \"function\";");
        emitLine("    java.lang.String identity = $identityOf(v);");
        emitLine("    if (identity != null) return identity;");
        emitLine("    return $describe(v);");
        emitLine("}");
        emitLine("// The DEAL identity of any generated class instance, ACROSS modules");
        emitLine("// (ISSUE-0109): each emitted module declares its own nested $Base,");
        emitLine("// so a foreign module's instances are not instanceof this module's");
        emitLine("// $Base. Every $Base carries the package-private field $identity");
        emitLine("// holding the spec ClassDescriptor (@<modulePath>/<Name>);");
        emitLine("// $identityOf reads it structurally so a wrong-module nominal check");
        emitLine("// reports the actual module-qualified identity exactly like");
        emitLine("// LuaJIT's actual_class reporting in check_type. The '$' in the");
        emitLine("// field name is unspellable in DEAL (javaName escapes '$'), so no");
        emitLine("// user-declared field can ever match it.");
        emitLine("static java.lang.String $identityOf(java.lang.Object v) {");
        emitLine("    if (v instanceof $Base b) return b.$identity;");
        emitLine("    if (v == null) return null;");
        emitLine("    java.lang.Class<?> k = v.getClass();");
        emitLine("    while (k != null && k != java.lang.Object.class) {");
        emitLine("        try {");
        emitLine("            java.lang.reflect.Field f = k.getDeclaredField(\"$identity\");");
        emitLine("            f.setAccessible(true);");
        emitLine("            return java.lang.String.valueOf(f.get(v));");
        emitLine("        } catch (java.lang.NoSuchFieldException e) {");
        emitLine("            k = k.getSuperclass();");
        emitLine("        } catch (java.lang.ReflectiveOperationException e) {");
        emitLine("            return null;");
        emitLine("        }");
        emitLine("    }");
        emitLine("    return null;");
        emitLine("}");
        emitLine("// The table-typed boundary check now lives in the shared");
        emitLine("// descriptor-driven seam (ISSUE-0110): table reads emit");
        emitLine("// $check(\"table\", v) and the seam raises E8001 \"expected table,");
        emitLine("// got ...\" for a non-table value — the same shape the retired");
        emitLine("// per-kind table-boundary helper raised.");
        emitLine("// Function-signature check helper (E8010): wrapper descriptors are");
        emitLine("// compared through this method so javac never proves the checking");
        emitLine("// wrapper's initializer throw constant (JLS requires an instance");
        emitLine("// initializer to be able to complete normally).");
        emitLine("static boolean checkSig(java.lang.String expected, java.lang.String actual) { return expected.equals(actual); }");
        emitLine("// ISSUE-0301: every function-value wrapper implements the shared");
        emitLine("// $DealRt.FnValue interface so the shared seam's function-");
        emitLine("// descriptor branch can validate a dynamically read function");
        emitLine("// value against its runtime descriptor across module boundaries.");
        emitLine();

        String intFnShape = registerWrapperShape(new Type.Func(
            List.of(Type.Number.INSTANCE), Type.Int.INSTANCE));
        String numberFnShape = registerWrapperShape(new Type.Func(
            List.of(Type.Int.INSTANCE), Type.Number.INSTANCE));
        emitLine("static final " + intFnShape + " _int$fn = new " + intFnShape + "() {");
        emitLine("    @Override");
        emitLine(int32Mode
            ? "    int invoke(double p0) { return intFromNumber(p0); }"
            : "    long invoke(double p0) { return intFromNumber(p0); }");
        emitLine("};");
        emitLine("static final " + numberFnShape + " _number$fn = new " + numberFnShape + "() {");
        emitLine("    @Override");
        emitLine(int32Mode
            ? "    double invoke(int p0) { return numberFromInt(p0); }"
            : "    double invoke(long p0) { return numberFromInt(p0); }");
        emitLine("};");
        emitLine();
        emitLine("// ---- DEAL primitive array runtime support (ISSUE-0094) ----");
        emitLine("// int[]/number[]/string[]/boolean[] map to the SHARED $DealRt");
        emitLine("// mutable wrapper classes (ISSUE-0301 D3) — the spec's specialized");
        emitLine("// primitive array wrapper (spec-v1.2 §JVM value mapping). The");
        emitLine("// wrapper identity is stable across appends (writing at i == length");
        emitLine("// grows the wrapped storage in place), so aliases observe every");
        emitLine("// write exactly like LuaJIT's shared 1-based table.");
        emitLine("// array reads: a negative index is E8002 (LuaJIT's emitted negative-index");
        emitLine("// check); an index past the end is E8001 \"expected <T>\" — LuaJIT reads nil");
        emitLine("// there and the read site's typed boundary fails with exactly that shape");
        emitLine("// (spec §Bounds and nil behavior: `let x: int = xs[99]` → nil is not int). A");
        emitLine("// primitive Java array cannot yield nil, so the JVM read raises the boundary");
        emitLine("// failure directly. Every raise carries the read site's index-expression");
        emitLine("// origin; the past-end boundary failure of the primitive element reads and");
        emitLine("// the bytes element read additionally carries the CONSUMER's boundary-check");
        emitLine("// origin (ISSUE-0605): the declared contextual target annotation for an");
        emitLine("// annotated binding initializer (the pinned `let value: int = values[2];`");
        emitLine("// 8:14 convention), the read expression itself otherwise. The int past-end");
        emitLine("// raise carries the closed D4 projection (expected \"int\", actual \"nil\").");
        emitLine(int32Mode
            ? "static int __intArrayRead($DealRt.__IntArray a, long i) { return __intArrayRead(a, i, null, -1, -1, null, -1, -1); }"
            : "static long __intArrayRead($DealRt.__IntArray a, long i) { return __intArrayRead(a, i, null, -1, -1, null, -1, -1); }");
        emitLine(int32Mode
            ? "static int __intArrayRead($DealRt.__IntArray a, long i, java.lang.String oFile, int oLine, int oCol, java.lang.String bFile, int bLine, int bCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) throw new DealError(\"E8001\", \"expected int\", bFile, bLine, bCol, \"int\", \"nil\", null, null); return a.data[(int) i]; }"
            : "static long __intArrayRead($DealRt.__IntArray a, long i, java.lang.String oFile, int oLine, int oCol, java.lang.String bFile, int bLine, int bCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) throw new DealError(\"E8001\", \"expected int\", bFile, bLine, bCol, \"int\", \"nil\", null, null); return a.data[(int) i]; }");
        emitLine("static double __numberArrayRead($DealRt.__NumberArray a, long i) { return __numberArrayRead(a, i, null, -1, -1, null, -1, -1); }");
        emitLine("static double __numberArrayRead($DealRt.__NumberArray a, long i, java.lang.String oFile, int oLine, int oCol, java.lang.String bFile, int bLine, int bCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) throw new DealError(\"E8001\", \"expected number, got null\", bFile, bLine, bCol); return a.data[(int) i]; }");
        emitLine("static java.lang.String __stringArrayRead($DealRt.__StringArray a, long i) { return __stringArrayRead(a, i, null, -1, -1, null, -1, -1); }");
        emitLine("static java.lang.String __stringArrayRead($DealRt.__StringArray a, long i, java.lang.String oFile, int oLine, int oCol, java.lang.String bFile, int bLine, int bCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) throw new DealError(\"E8001\", \"expected string, got null\", bFile, bLine, bCol); return a.data[(int) i]; }");
        emitLine("static boolean __booleanArrayRead($DealRt.__BooleanArray a, long i) { return __booleanArrayRead(a, i, null, -1, -1, null, -1, -1); }");
        emitLine("static boolean __booleanArrayRead($DealRt.__BooleanArray a, long i, java.lang.String oFile, int oLine, int oCol, java.lang.String bFile, int bLine, int bCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) throw new DealError(\"E8001\", \"expected boolean, got null\", bFile, bLine, bCol); return a.data[(int) i]; }");
        emitLine("// boxed array reads for === / !== operand positions (ISSUE-0094 rework):");
        emitLine("// the read-site contract (spec §Bounds and nil behavior) applies no typed");
        emitLine("// boundary to a comparison operand, so LuaJIT reads nil past the end and");
        emitLine("// computes nil === v (false) / nil !== v (true) / nil === nil (true) on");
        emitLine("// that value instead of raising E8001. A primitive Java read cannot yield");
        emitLine("// nil, so the comparison position boxes the read: null past the end (the");
        emitLine("// LuaJIT nil), the element value otherwise. A negative index still raises");
        emitLine("// E8002 — LuaJIT emits that check unconditionally at the read, whatever");
        emitLine("// the surrounding position.");
        emitLine(int32Mode
            ? "static java.lang.Integer __intArrayReadBoxed($DealRt.__IntArray a, long i) { return __intArrayReadBoxed(a, i, null, -1, -1); }"
            : "static java.lang.Long __intArrayReadBoxed($DealRt.__IntArray a, long i) { return __intArrayReadBoxed(a, i, null, -1, -1); }");
        emitLine(int32Mode
            ? "static java.lang.Integer __intArrayReadBoxed($DealRt.__IntArray a, long i, java.lang.String oFile, int oLine, int oCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) return null; return java.lang.Integer.valueOf(a.data[(int) i]); }"
            : "static java.lang.Long __intArrayReadBoxed($DealRt.__IntArray a, long i, java.lang.String oFile, int oLine, int oCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) return null; return java.lang.Long.valueOf(a.data[(int) i]); }");
        emitLine("static java.lang.Double __numberArrayReadBoxed($DealRt.__NumberArray a, long i) { return __numberArrayReadBoxed(a, i, null, -1, -1); }");
        emitLine("static java.lang.Double __numberArrayReadBoxed($DealRt.__NumberArray a, long i, java.lang.String oFile, int oLine, int oCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) return null; return java.lang.Double.valueOf(a.data[(int) i]); }");
        emitLine("static java.lang.String __stringArrayReadBoxed($DealRt.__StringArray a, long i) { return __stringArrayReadBoxed(a, i, null, -1, -1); }");
        emitLine("static java.lang.String __stringArrayReadBoxed($DealRt.__StringArray a, long i, java.lang.String oFile, int oLine, int oCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) return null; return a.data[(int) i]; }");
        emitLine("static java.lang.Boolean __booleanArrayReadBoxed($DealRt.__BooleanArray a, long i) { return __booleanArrayReadBoxed(a, i, null, -1, -1); }");
        emitLine("static java.lang.Boolean __booleanArrayReadBoxed($DealRt.__BooleanArray a, long i, java.lang.String oFile, int oLine, int oCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) return null; return java.lang.Boolean.valueOf(a.data[(int) i]); }");
        emitLine("// boolean boundary check for nil-aware && / || results (ISSUE-0094 rework):");
        emitLine("// a past-end boolean[] read in an and/or operand is Lua's nil — the");
        emitLine("// operand is falsy and the Lua result can itself be nil (nil and x yields");
        emitLine("// nil, nil or x yields x, true and nil yields nil). emitShortCircuit lowers such");
        emitLine("// expressions to boxed java.lang.Boolean temporaries (null = the Lua nil),");
        emitLine("// and every typed boolean boundary (declaration initializer, if/while");
        emitLine("// condition, return, argument, assignment, element write/literal) converts");
        emitLine("// with this helper: null fails exactly where LuaJIT's check_boolean(nil)");
        emitLine("// fails — E8001 \"expected boolean, got null\" (LuaJIT: E8001 \"expected");
        emitLine("// boolean\").");
        emitLine("static boolean booleanNotNull(java.lang.Boolean v) { return booleanNotNull(v, null, -1, -1); }");
        emitLine("static boolean booleanNotNull(java.lang.Boolean v, java.lang.String oFile, int oLine, int oCol) { if (v == null) throw new DealError(\"E8001\", \"expected boolean, got null\", oFile, oLine, oCol); return v; }");
        emitLine("// array writes: 0 <= i <= length (E8002 otherwise); i == length appends one");
        emitLine("// element (spec §Array writes); the stored value is runtime-checked against the");
        emitLine("// element type — int elements route through checkInt (E8004, like LuaJIT's");
        emitLine("// check_int at the write), while the JVM static type system proves the");
        emitLine("// number/string/boolean element checks redundant (spec-v1.2 §JVM backend");
        emitLine("// contract). The write check runs after the value expression has been");
        emitLine("// evaluated — the helper call's Java arguments evaluate left to right before");
        emitLine("// the bounds check, per spec-v1.2 §Operational semantics rule 3.");
        emitLine(int32Mode
            ? "static int __intArrayWrite($DealRt.__IntArray a, long i, int v) { return __intArrayWrite(a, i, v, null, -1, -1); }"
            : "static long __intArrayWrite($DealRt.__IntArray a, long i, long v) { return __intArrayWrite(a, i, v, null, -1, -1); }");
        emitLine(int32Mode
            ? "static int __intArrayWrite($DealRt.__IntArray a, long i, int v, java.lang.String oFile, int oLine, int oCol) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\", oFile, oLine, oCol); if (i == (long) a.data.length) { int[] nd = new int[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }"
            : "static long __intArrayWrite($DealRt.__IntArray a, long i, long v, java.lang.String oFile, int oLine, int oCol) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\", oFile, oLine, oCol); v = checkInt(v, oFile, oLine, oCol); if (i == (long) a.data.length) { long[] nd = new long[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("static double __numberArrayWrite($DealRt.__NumberArray a, long i, double v) { return __numberArrayWrite(a, i, v, null, -1, -1); }");
        emitLine("static double __numberArrayWrite($DealRt.__NumberArray a, long i, double v, java.lang.String oFile, int oLine, int oCol) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\", oFile, oLine, oCol); if (i == (long) a.data.length) { double[] nd = new double[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("static java.lang.String __stringArrayWrite($DealRt.__StringArray a, long i, java.lang.String v) { return __stringArrayWrite(a, i, v, null, -1, -1); }");
        emitLine("static java.lang.String __stringArrayWrite($DealRt.__StringArray a, long i, java.lang.String v, java.lang.String oFile, int oLine, int oCol) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\", oFile, oLine, oCol); if (i == (long) a.data.length) { java.lang.String[] nd = new java.lang.String[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("static boolean __booleanArrayWrite($DealRt.__BooleanArray a, long i, boolean v) { return __booleanArrayWrite(a, i, v, null, -1, -1); }");
        emitLine("static boolean __booleanArrayWrite($DealRt.__BooleanArray a, long i, boolean v, java.lang.String oFile, int oLine, int oCol) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\", oFile, oLine, oCol); if (i == (long) a.data.length) { boolean[] nd = new boolean[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine();
        emitLine("// ---- DEAL nullable and nullable-array runtime support (ISSUE-0108) ----");
        emitLine("// (T | null)[] maps to the SHARED $DealRt wrappers over BOXED");
        emitLine("// elements (java.lang.Long[], java.lang.Double[],");
        emitLine("// java.lang.String[], java.lang.Boolean[]): Java null is the DEAL");
        emitLine("// null element, exactly like LuaJIT's table storage where nil/");
        emitLine("// __NULL is the null element. Reads yield null past the end (the");
        emitLine("// LuaJIT nil), and a negative index still raises E8002 (LuaJIT");
        emitLine("// emits that check unconditionally at the read). Writes accept");
        emitLine("// null (check_nullable permits it) and check only the index");
        emitLine("// bounds.");
        emitLine(int32Mode
            ? "static java.lang.Integer __intOrNullArrayWrite($DealRt.__IntOrNullArray a, long i, java.lang.Integer v) { return __intOrNullArrayWrite(a, i, v, null, -1, -1); }"
            : "static java.lang.Long __intOrNullArrayWrite($DealRt.__IntOrNullArray a, long i, java.lang.Long v) { return __intOrNullArrayWrite(a, i, v, null, -1, -1); }");
        emitLine(int32Mode
            ? "static java.lang.Integer __intOrNullArrayWrite($DealRt.__IntOrNullArray a, long i, java.lang.Integer v, java.lang.String oFile, int oLine, int oCol) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\", oFile, oLine, oCol); if (i == (long) a.data.length) { java.lang.Integer[] nd = new java.lang.Integer[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }"
            : "static java.lang.Long __intOrNullArrayWrite($DealRt.__IntOrNullArray a, long i, java.lang.Long v, java.lang.String oFile, int oLine, int oCol) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\", oFile, oLine, oCol); if (i == (long) a.data.length) { java.lang.Long[] nd = new java.lang.Long[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("static java.lang.Double __numberOrNullArrayWrite($DealRt.__NumberOrNullArray a, long i, java.lang.Double v) { return __numberOrNullArrayWrite(a, i, v, null, -1, -1); }");
        emitLine("static java.lang.Double __numberOrNullArrayWrite($DealRt.__NumberOrNullArray a, long i, java.lang.Double v, java.lang.String oFile, int oLine, int oCol) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\", oFile, oLine, oCol); if (i == (long) a.data.length) { java.lang.Double[] nd = new java.lang.Double[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("static java.lang.String __stringOrNullArrayWrite($DealRt.__StringOrNullArray a, long i, java.lang.String v) { return __stringOrNullArrayWrite(a, i, v, null, -1, -1); }");
        emitLine("static java.lang.String __stringOrNullArrayWrite($DealRt.__StringOrNullArray a, long i, java.lang.String v, java.lang.String oFile, int oLine, int oCol) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\", oFile, oLine, oCol); if (i == (long) a.data.length) { java.lang.String[] nd = new java.lang.String[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("static java.lang.Boolean __booleanOrNullArrayWrite($DealRt.__BooleanOrNullArray a, long i, java.lang.Boolean v) { return __booleanOrNullArrayWrite(a, i, v, null, -1, -1); }");
        emitLine("static java.lang.Boolean __booleanOrNullArrayWrite($DealRt.__BooleanOrNullArray a, long i, java.lang.Boolean v, java.lang.String oFile, int oLine, int oCol) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\", oFile, oLine, oCol); if (i == (long) a.data.length) { java.lang.Boolean[] nd = new java.lang.Boolean[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("// (T | null)[] reads: null past the end (the LuaJIT nil — a valid");
        emitLine("// nullable element, so NO E8001 at the read) and the stored boxed");
        emitLine("// element otherwise; a negative index still raises E8002 (LuaJIT");
        emitLine("// emits that check unconditionally at the read).");
        emitLine(int32Mode
            ? "static java.lang.Integer __intOrNullArrayRead($DealRt.__IntOrNullArray a, long i) { return __intOrNullArrayRead(a, i, null, -1, -1); }"
            : "static java.lang.Long __intOrNullArrayRead($DealRt.__IntOrNullArray a, long i) { return __intOrNullArrayRead(a, i, null, -1, -1); }");
        emitLine(int32Mode
            ? "static java.lang.Integer __intOrNullArrayRead($DealRt.__IntOrNullArray a, long i, java.lang.String oFile, int oLine, int oCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) return null; return a.data[(int) i]; }"
            : "static java.lang.Long __intOrNullArrayRead($DealRt.__IntOrNullArray a, long i, java.lang.String oFile, int oLine, int oCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) return null; return a.data[(int) i]; }");
        emitLine("static java.lang.Double __numberOrNullArrayRead($DealRt.__NumberOrNullArray a, long i) { return __numberOrNullArrayRead(a, i, null, -1, -1); }");
        emitLine("static java.lang.Double __numberOrNullArrayRead($DealRt.__NumberOrNullArray a, long i, java.lang.String oFile, int oLine, int oCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) return null; return a.data[(int) i]; }");
        emitLine("static java.lang.String __stringOrNullArrayRead($DealRt.__StringOrNullArray a, long i) { return __stringOrNullArrayRead(a, i, null, -1, -1); }");
        emitLine("static java.lang.String __stringOrNullArrayRead($DealRt.__StringOrNullArray a, long i, java.lang.String oFile, int oLine, int oCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) return null; return a.data[(int) i]; }");
        emitLine("static java.lang.Boolean __booleanOrNullArrayRead($DealRt.__BooleanOrNullArray a, long i) { return __booleanOrNullArrayRead(a, i, null, -1, -1); }");
        emitLine("static java.lang.Boolean __booleanOrNullArrayRead($DealRt.__BooleanOrNullArray a, long i, java.lang.String oFile, int oLine, int oCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) return null; return a.data[(int) i]; }");
        emitLine("// ---- DEAL bytes-array runtime support (ISSUE-0160 recursive");
        emitLine("// bytes-bearing closure) ----");
        emitLine("// bytes[] / (bytes | null)[] map to the SHARED $DealRt");
        emitLine("// __BytesArray / __BytesOrNullArray wrappers over");
        emitLine("// $DealRt.Bytes[] storage: reference elements (alias-observed");
        emitLine("// writes, reference identity), the pinned bounds policy (E8002");
        emitLine("// negative index / gap write, append at i == length), and the");
        emitLine("// element policy: bytes[] rejects the null element with E8001");
        emitLine("// \"expected bytes, got null\", (bytes | null)[] accepts it as the");
        emitLine("// DEAL null element. Reads past the end raise the element-typed");
        emitLine("// E8001 \"expected bytes, got null\" (LuaJIT reads nil there and the");
        emitLine("// read site's typed boundary fails with that shape); the boxed");
        emitLine("// read yields null past the end for === / !== operand positions");
        emitLine("// (no typed boundary at a comparison operand — LuaJIT computes");
        emitLine("// the comparison on nil).");
        emitLine("static $DealRt.Bytes __bytesArrayRead($DealRt.__BytesArray a, long i) { return __bytesArrayRead(a, i, null, -1, -1, null, -1, -1); }");
        emitLine("static $DealRt.Bytes __bytesArrayRead($DealRt.__BytesArray a, long i, java.lang.String oFile, int oLine, int oCol, java.lang.String bFile, int bLine, int bCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) throw new DealError(\"E8001\", \"expected bytes, got null\", bFile, bLine, bCol); return a.data[(int) i]; }");
        emitLine("static $DealRt.Bytes __bytesArrayReadBoxed($DealRt.__BytesArray a, long i) { return __bytesArrayReadBoxed(a, i, null, -1, -1); }");
        emitLine("static $DealRt.Bytes __bytesArrayReadBoxed($DealRt.__BytesArray a, long i, java.lang.String oFile, int oLine, int oCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) return null; return a.data[(int) i]; }");
        emitLine("static $DealRt.Bytes __bytesArrayWrite($DealRt.__BytesArray a, long i, $DealRt.Bytes v) { return __bytesArrayWrite(a, i, v, null, -1, -1); }");
        emitLine("static $DealRt.Bytes __bytesArrayWrite($DealRt.__BytesArray a, long i, $DealRt.Bytes v, java.lang.String oFile, int oLine, int oCol) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\", oFile, oLine, oCol); if (!(v instanceof $DealRt.Bytes)) throw new DealError(\"E8001\", \"expected bytes, got \" + $describe(v), oFile, oLine, oCol); if (i == (long) a.data.length) { $DealRt.Bytes[] nd = new $DealRt.Bytes[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("static $DealRt.Bytes __bytesOrNullArrayWrite($DealRt.__BytesOrNullArray a, long i, $DealRt.Bytes v) { return __bytesOrNullArrayWrite(a, i, v, null, -1, -1); }");
        emitLine("static $DealRt.Bytes __bytesOrNullArrayWrite($DealRt.__BytesOrNullArray a, long i, $DealRt.Bytes v, java.lang.String oFile, int oLine, int oCol) { if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\", oFile, oLine, oCol); if (v != null && !(v instanceof $DealRt.Bytes)) throw new DealError(\"E8001\", \"expected bytes, got \" + $describe(v), oFile, oLine, oCol); if (i == (long) a.data.length) { $DealRt.Bytes[] nd = new $DealRt.Bytes[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; } return v; }");
        emitLine("static $DealRt.Bytes __bytesOrNullArrayRead($DealRt.__BytesOrNullArray a, long i) { return __bytesOrNullArrayRead(a, i, null, -1, -1); }");
        emitLine("static $DealRt.Bytes __bytesOrNullArrayRead($DealRt.__BytesOrNullArray a, long i, java.lang.String oFile, int oLine, int oCol) { if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol); if (i >= (long) a.data.length) return null; return a.data[(int) i]; }");
        emitLine("// Class arrays (C[], (C | null)[]) map to the shared");
        emitLine("// $DealRt.__RefArray holding java.lang.Object[]; every class");
        emitLine("// declaration also emits a per-class subclass ($Array$<C>) so");
        emitLine("// instanceof proves the element type, plus per-class read/write");
        emitLine("// helpers with the nominal E8001 checks.");
        emitLine("// The untyped-boundary checks the nullable slice needs (check_nullable");
        emitLine("// semantics for ?int/?number/?boolean/?string, the per-wrapper array");
        emitLine("// gates for [int]/[number]/[string]/[boolean] and their [?T]");
        emitLine("// widening forms) all live in the single shared descriptor-driven");
        emitLine("// $check(descriptor, value) seam now (ISSUE-0110, emitted after the");
        emitLine("// module body). The acceptance semantics are byte-for-byte the ones");
        emitLine("// the retired per-kind helpers carried: a Long crosses into number |");
        emitLine("// null, an integral in-range Double crosses into int | null (NaN/");
        emitLine("// infinity/non-integral E8001, out-of-safe-range E8004 — check_int");
        emitLine("// parity), null passes check_nullable, a plain T[] wrapper passes a");
        emitLine("// [?T] gate with the boxed copy, and a wrong wrapper raises E8001");
        emitLine("// \"expected array, got ...\".");
        emitLine("// int(v: int | null) / number(v: number | null) conversion intrinsics:");
        emitLine("// null fails at runtime with E8001 (runtime.lua's int_convert/");
        emitLine("// number_convert \"cannot convert null to ...\" shapes).");
        emitLine(int32Mode
            ? "static int intFromNullable(java.lang.Integer v) { return intFromNullable(v, null, -1, -1); }"
            : "static long intFromNullable(java.lang.Long v) { return intFromNullable(v, null, -1, -1); }");
        emitLine(int32Mode
            ? "static int intFromNullable(java.lang.Integer v, java.lang.String oFile, int oLine, int oCol) { if (v == null) throw new DealError(\"E8001\", \"cannot convert null to int\", oFile, oLine, oCol, \"int\", \"null\", null, null); return v; }"
            : "static long intFromNullable(java.lang.Long v, java.lang.String oFile, int oLine, int oCol) { if (v == null) throw new DealError(\"E8001\", \"cannot convert null to int\", oFile, oLine, oCol, \"int\", \"null\", null, null); return v; }");
        emitLine("static double numberFromNullable(java.lang.Double v) { return numberFromNullable(v, null, -1, -1); }");
        emitLine("static double numberFromNullable(java.lang.Double v, java.lang.String oFile, int oLine, int oCol) { if (v == null) throw new DealError(\"E8001\", \"cannot convert null to number\", oFile, oLine, oCol, \"number\", \"null\", null, null); return v; }");
        emitLine();
        emitLine("// ---- DEAL stdlib support (ISSUE-0097, ISSUE-0106 v1.2 scalar strings): std/string, std/math, std/time ----");
        emitLine("// DEAL strings are sequences of Unicode scalar values (spec-v1.2 §String");
        emitLine("// escapes and Unicode), and std/string length/substring positions are");
        emitLine("// measured in Unicode scalar values. The JVM mapping (java.lang.String,");
        emitLine("// UTF-16 code units) therefore converts scalar positions to code-unit");
        emitLine("// offsets with codePointCount/offsetByCodePoints, so supplementary");
        emitLine("// characters count as ONE scalar value. Search/replace/trim operate on");
        emitLine("// whole strings, where scalar-value and code-unit semantics coincide.");
        emitLine(int32Mode
            ? "static int __strLength(java.lang.String s) { return s.codePointCount(0, s.length()); }"
            : "static long __strLength(java.lang.String s) { return (long) s.codePointCount(0, s.length()); }");
        emitLine("// v1.2 reference semantics (std/string.lua): positions are Unicode");
        emitLine("// scalar values; a negative start behaves as 0, a negative end yields");
        emitLine("// the empty string, an end beyond the string clamps to n, and");
        emitLine("// start >= end yields the empty string. The scalar bounds convert to");
        emitLine("// UTF-16 offsets via offsetByCodePoints.");
        emitLine("static java.lang.String __strSubstring(java.lang.String s, long start, long end) {");
        emitLine("    long n = (long) s.codePointCount(0, s.length());");
        emitLine("    int from = s.offsetByCodePoints(0, (int) java.lang.Math.min(java.lang.Math.max(start, 0L), n));");
        emitLine("    int to = s.offsetByCodePoints(0, (int) java.lang.Math.min(java.lang.Math.max(end, 0L), n));");
        emitLine("    if (to < from) return \"\";");
        emitLine("    return s.substring(from, to);");
        emitLine("}");
        emitLine("// Plain-text replace of every occurrence; an empty old returns s unchanged");
        emitLine("// (LuaJIT guards before gsub, which cannot match an empty pattern).");
        emitLine("static java.lang.String __strReplace(java.lang.String s, java.lang.String old, java.lang.String to) { return old.isEmpty() ? s : s.replace(old, to); }");
        emitLine("// Plain-text split: an empty s yields the empty array (whatever the");
        emitLine("// separator); an empty separator splits into individual Unicode scalar");
        emitLine("// values (spec-v1.2 §String lengths and positions are measured in");
        emitLine("// Unicode scalar values); otherwise every occurrence of sep delimits a");
        emitLine("// part, with the trailing remainder (even empty) appended.");
        emitLine("static $DealRt.__StringArray __strSplit(java.lang.String s, java.lang.String sep) {");
        emitLine("    java.util.ArrayList<java.lang.String> parts = new java.util.ArrayList<>();");
        emitLine("    if (s.isEmpty()) return new $DealRt.__StringArray(parts.toArray(new java.lang.String[0]));");
        emitLine("    if (sep.isEmpty()) {");
        emitLine("        int i = 0;");
        emitLine("        while (i < s.length()) {");
        emitLine("            int cp = s.codePointAt(i);");
        emitLine("            parts.add(new java.lang.String(java.lang.Character.toChars(cp)));");
        emitLine("            i += java.lang.Character.charCount(cp);");
        emitLine("        }");
        emitLine("        return new $DealRt.__StringArray(parts.toArray(new java.lang.String[0]));");
        emitLine("    }");
        emitLine("    int start = 0;");
        emitLine("    while (true) {");
        emitLine("        int found = s.indexOf(sep, start);");
        emitLine("        if (found < 0) { parts.add(s.substring(start)); break; }");
        emitLine("        parts.add(s.substring(start, found));");
        emitLine("        start = found + sep.length();");
        emitLine("    }");
        emitLine("    return new $DealRt.__StringArray(parts.toArray(new java.lang.String[0]));");
        emitLine("}");
        emitLine("// Lua pattern %s whitespace set: space, tab, newline, vertical tab, form feed,");
        emitLine("// carriage return (exactly the std/string.lua trim contract).");
        emitLine("static boolean __strIsTrimSpace(char c) { return c == ' ' || c == '\\t' || c == '\\n' || c == '\\u000b' || c == '\\f' || c == '\\r'; }");
        emitLine("static java.lang.String __strTrim(java.lang.String s) {");
        emitLine("    int st = 0;");
        emitLine("    int en = s.length();");
        emitLine("    while (st < en && __strIsTrimSpace(s.charAt(st))) st++;");
        emitLine("    while (en > st && __strIsTrimSpace(s.charAt(en - 1))) en--;");
        emitLine("    return s.substring(st, en);");
        emitLine("}");
        emitLine("// std/math.sqrt rejects negative inputs with E8001 (std/math.lua); NaN passes");
        emitLine("// through to NaN like LuaJIT's x < 0 guard and math.sqrt. floor/ceil/abs/min/max");
        emitLine("// map to java.lang.Math directly (same IEEE 754 semantics).");
        emitLine("static double __mathSqrt(double x) { return __mathSqrt(x, null, -1, -1); }");
        emitLine("static double __mathSqrt(double x, java.lang.String oFile, int oLine, int oCol) { if (x < 0.0) throw new DealError(\"E8001\", \"sqrt of negative number\", oFile, oLine, oCol); return java.lang.Math.sqrt(x); }");
        emitLine();
    }

    private void emitSharedScope() {
        emitLine("// Shared DEAL runtime value scope (ISSUE-0102 tables; ISSUE-0301");
        emitLine("// function/array/bytes carriers): one per compiled project,");
        emitLine("// declared by the entry module's artifact. Every module");
        emitLine("// references the SAME class, so table, function, array, and");
        emitLine("// bytes values cross module boundaries with shared identity —");
        emitLine("// exactly like LuaJIT's single value types. The $ in the name");
        emitLine("// is unreachable from classNameFor (sanitizeSegment maps $ to _).");
        emitLine("class $DealRt {");
        emitLine("    static final class Table {");
        emitLine("        private final java.util.LinkedHashMap<java.lang.String, java.lang.Object> entries = new java.util.LinkedHashMap<>();");
        emitLine("        // Array-mode support (the @jsonable slice): non-null when the");
        emitLine("        // table is array-shaped (a 1-based element sequence) — the");
        emitLine("        // slice maps nested JSON arrays to array-mode tables so");
        emitLine("        // string-keyed reads and re-serialization keep the JSON");
        emitLine("        // array shape (DEAL cannot spell integer keys).");
        emitLine("        private final java.util.ArrayList<java.lang.Object> array;");
        emitLine("        Table() { this.array = null; }");
        emitLine("        Table(java.util.ArrayList<java.lang.Object> array) { this.array = array; }");
        emitLine("        Table put(java.lang.String k, java.lang.Object v) { entries.put(k, v); return this; }");
        emitLine("        java.lang.Object get(java.lang.String k) { return entries.get(k); }");
        emitLine("        java.lang.Object remove(java.lang.String k) { return entries.remove(k); }");
        emitLine("        boolean has(java.lang.String k) { return entries.containsKey(k); }");
        emitLine("        java.lang.String[] keys() { return entries.keySet().toArray(new java.lang.String[0]); }");
        emitLine("        java.util.ArrayList<java.lang.Object> $array() { return array; }");
        emitLine("        java.util.LinkedHashMap<java.lang.String, java.lang.Object> $entries() { return entries; }");
        emitLine("    }");
        emitLine("    // The final runtime-owned bytes wrapper over byte[] (ISSUE-0301");
        emitLine("    // D6 carrier; ISSUE-0158 int32-bytes lane): reference identity for");
        emitLine("    // equality/assignment/aliasing, canonical descriptor 'bytes' in");
        emitLine("    // $check, non-jsonable. Allocation/indexing/mutation lower to the");
        emitLine("    // emitted bytesNew/bytesLength/bytesGet/bytesSet helpers.");
        emitLine("    static final class Bytes {");
        emitLine("        final byte[] data;");
        emitLine("        Bytes(byte[] data) { this.data = data; }");
        emitLine("    }");
        emitLine("    // The shared function-value carrier interface: every wrapper");
        emitLine("    // carries the complete canonical descriptor text so signature");
        emitLine("    // checks are byte comparisons across module boundaries.");
        emitLine("    interface FnValue { java.lang.String descriptor(); }");

        emitLine("    interface Fn0 { java.lang.Object invoke(); }");

        emitLine("    static final class DefaultPlanEntry {");
        emitLine("        final java.lang.String name;");
        emitLine("        final java.lang.String descriptor;");
        emitLine("        final boolean optional;");
        emitLine("        final Fn0 evaluator;");
        emitLine("        DefaultPlanEntry(java.lang.String name, java.lang.String descriptor, boolean optional, Fn0 evaluator) {");
        emitLine("            this.name = name; this.descriptor = descriptor;");
        emitLine("            this.optional = optional; this.evaluator = evaluator;");
        emitLine("        }");
        emitLine("    }");
        emitLine("    // ---- shared per-element-shape array carriers (ISSUE-0301 D3) ----");
        emitLine("    // The wrapper classes move here from the per-module scope:");
        emitLine("    // identity stable across appends, aliases observe every write,");
        emitLine("    // and the values cross module boundaries with shared identity.");
        emitLine(int32Mode
            ? "    static final class __IntArray { int[] data; __IntArray(int[] data) { this.data = data; } }"
            : "    static final class __IntArray { long[] data; __IntArray(long[] data) { this.data = data; } }");
        emitLine("    static final class __NumberArray { double[] data; __NumberArray(double[] data) { this.data = data; } }");
        emitLine("    static final class __StringArray { java.lang.String[] data; __StringArray(java.lang.String[] data) { this.data = data; } }");
        emitLine("    static final class __BooleanArray { boolean[] data; __BooleanArray(boolean[] data) { this.data = data; } }");
        emitLine(int32Mode
            ? "    static final class __IntOrNullArray { java.lang.Integer[] data; __IntOrNullArray(java.lang.Integer[] data) { this.data = data; } }"
            : "    static final class __IntOrNullArray { java.lang.Long[] data; __IntOrNullArray(java.lang.Long[] data) { this.data = data; } }");
        emitLine("    static final class __NumberOrNullArray { java.lang.Double[] data; __NumberOrNullArray(java.lang.Double[] data) { this.data = data; } }");
        emitLine("    static final class __StringOrNullArray { java.lang.String[] data; __StringOrNullArray(java.lang.String[] data) { this.data = data; } }");
        emitLine("    static final class __BooleanOrNullArray { java.lang.Boolean[] data; __BooleanOrNullArray(java.lang.Boolean[] data) { this.data = data; } }");

        emitLine("    static final class __BytesArray { Bytes[] data; __BytesArray(Bytes[] data) { this.data = data; } }");
        emitLine("    static final class __BytesOrNullArray { Bytes[] data; __BytesOrNullArray(Bytes[] data) { this.data = data; } }");
        emitLine("    // The Object-storage base for per-class array wrappers (each");
        emitLine("    // module's $Array$<C> extends it so instanceof proves the");
        emitLine("    // element type).");
        emitLine("    static class __RefArray { java.lang.Object[] data; __RefArray(java.lang.Object[] data) { this.data = data; } }");
        emitLine("    // One wrapper class per complex element shape (nested arrays,");
        emitLine("    // arrays of function values, arrays of bytes) — named by the");
        emitLine("    // same injective encoding as function shapes, so distinct");
        emitLine("    // element types map to distinct classes (ISSUE-0301 D3).");
        for (Type element : refArrayCheckElements) {

            emitLine("    static final class " + refArrayWrapperId(element)
                + " extends __RefArray { "
                + refArrayWrapperId(element)
                + "(java.lang.Object[] data) { super(data); } }");
        }

        for (Map.Entry<String, Map<String,
                List<HostModuleDeclarations.HostField>>> e
                : sharedHostClasses.entrySet()) {
            String specifier = e.getKey();
            for (Map.Entry<String, List<HostModuleDeclarations.HostField>>
                    c : e.getValue().entrySet()) {
                emitHostClassRecord(specifier, c.getKey(), c.getValue());
            }
        }
        // The per-signature function wrapper classes accumulate during
        // module emission and are spliced here, inside the shared scope.
        int dealRtInsertion = out.length();
        emitLine("}");
        if (sharedWrapperClasses.length() > 0) {
            out.insert(dealRtInsertion, sharedWrapperClasses.toString());
        }
    }

    private void emitHostClassRecord(String specifier, String className,
            List<HostModuleDeclarations.HostField> fields) {
        String simple = hostRecordSimpleName(specifier, className);
        CanonicalClassIdentity identity = new CanonicalClassIdentity(
            new CanonicalModuleIdentity.ExternalModule(specifier),
            className);
        String identityText = identityIndex.descriptorTextFor(identity);
        emitLine("    // Synthesized host-class record " + identityText
            + " (declared by the externals entry '" + specifier + "').");
        emitLine("    static final class " + simple + " {");
        emitLine("        final java.lang.String $identity;");
        List<String> storageTypes = new ArrayList<>();
        List<String> storageNames = new ArrayList<>();
        List<Boolean> optionalFlags = new ArrayList<>();
        for (HostModuleDeclarations.HostField hf : fields) {
            ClassField cf = hf.declaration();
            Type inner = hf.type() instanceof Type.Nullable nn
                ? nn.inner() : hf.type();
            String storage = hostRecordFieldJavaType(inner, cf.optional());
            if (storage == null) {
                throw new IllegalStateException(
                    "host class field '" + className + "." + cf.name()
                        + "' of type " + typeDescriptor(hf.type())
                        + " has no shared-scope storage type (internal "
                        + "invariant violation — validateHostExports "
                        + "gated the shape)");
            }
            storageTypes.add(storage);
            storageNames.add(javaName(cf.name()));
            optionalFlags.add(cf.optional());
            emitLine("        " + storage + " " + javaName(cf.name()) + ";");
            if (cf.optional()) {
                emitLine("        boolean " + javaName(cf.name())
                    + "$present;");
            }
        }
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < storageNames.size(); i++) {
            if (i > 0) params.append(", ");
            params.append(storageTypes.get(i)).append(' ')
                .append(storageNames.get(i));
            if (optionalFlags.get(i)) {
                params.append(", boolean ").append(storageNames.get(i))
                    .append("$present");
            }
        }
        emitLine("        " + simple + "(" + params + ") {");
        emitLine("            this.$identity = "
            + quoteJavaString(identityText) + ";");
        for (int i = 0; i < storageNames.size(); i++) {
            emitLine("            this." + storageNames.get(i) + " = "
                + storageNames.get(i) + ";");
            if (optionalFlags.get(i)) {
                emitLine("            this." + storageNames.get(i)
                    + "$present = " + storageNames.get(i) + "$present;");
            }
        }
        emitLine("        }");

        for (int i = 0; i < storageNames.size(); i++) {
            if (optionalFlags.get(i)) {
                emitLine("        " + storageTypes.get(i) + " $optSet$"
                    + storageNames.get(i) + "(" + storageTypes.get(i)
                    + " v) {");
                emitLine("            this." + storageNames.get(i)
                    + " = v;");
                emitLine("            this." + storageNames.get(i)
                    + "$present = true;");
                emitLine("            return v;");
                emitLine("        }");
            }
        }
        emitLine("    }");
        emitLine("    // Per-class array wrapper for " + className
            + "[] / nullable-element arrays (shared __RefArray storage).");
        emitLine("    static final class $HostArr$"
            + escapedIdentifier(specifier) + "$" + javaName(className)
            + " extends __RefArray {");
        emitLine("        $HostArr$" + escapedIdentifier(specifier) + "$"
            + javaName(className)
            + "(java.lang.Object[] data) { super(data); }");
        emitLine("    }");
    }

    private void emitSharedCheckSeam() {
        emitLine();
        emitLine("// ---- Shared descriptor-driven runtime-check seam (ISSUE-0110;");
        emitLine("// ISSUE-0301 D5: the canonical RuntimeTypeMatcher realization over");
        emitLine("// the shared $DealRt carriers) ----");
        emitLine("// One helper, one descriptor convention (spec RuntimeTypeDescriptor,");
        emitLine("// docs/spec-v1.2.md): every boundary the JVM type system cannot");
        emitLine("// prove routes through $check(descriptor, value). A ? prefix is");
        emitLine("// check_nullable: the DEAL null (Java null here) passes through.");
        emitLine("// ISSUE-0106 (v1.2 boundary string validation): the \"string\"");
        emitLine("// branch also scans for unpaired UTF-16 surrogate code units — the");
        emitLine("// JVM string representation contract (spec-v1.2 §JVM value mapping:");
        emitLine("// \"java.lang.String with no unpaired surrogate code units\"). A");
        emitLine("// string with a lone high or low surrogate raises E8001.");
        emitLine("static boolean __hasUnpairedSurrogate(java.lang.String s) {");
        emitLine("    for (int i = 0; i < s.length(); i++) {");
        emitLine("        char c = s.charAt(i);");
        emitLine("        if (java.lang.Character.isHighSurrogate(c)) {");
        emitLine("            if (i + 1 >= s.length() || !java.lang.Character.isLowSurrogate(s.charAt(i + 1))) return true;");
        emitLine("            i++;");
        emitLine("        } else if (java.lang.Character.isLowSurrogate(c)) {");
        emitLine("            return true;");
        emitLine("        }");
        emitLine("    }");
        emitLine("    return false;");
        emitLine("}");
        emitLine("// Canonical-grammar validation (the generated RuntimeTypeMatcher realization): the");
        emitLine("// strict grammar is checked BEFORE any branch, so parse-rejected spellings (?null,");
        emitLine("// ?[], ?, ?int[], T[], T|null, bare names, rest sigs, dotted class-name text) raise");
        emitLine("// E8001 even when the carrier is null — canonical parsing precedes the legacy '?'");
        emitLine("// shortcut. The validator mirrors the compiler's strict parser: primitive keywords,");
        emitLine("// [D]/?D (nested nullable and ?null rejected), exact sync/async function forms, and");
        emitLine("// class atoms whose final component is identifier-shaped with at least one '/' (dots");
        emitLine("// are legal in non-final components: @$external/host.cfg/ServerConfig parses,");
        emitLine("// @src.models.User does not).");
        emitLine("static boolean __canonical(java.lang.String d) {");
        emitLine("    int[] p = new int[]{0};");
        emitLine("    return __canonicalAt(p, d) && p[0] == d.length();");
        emitLine("}");
        emitLine("static boolean __canonicalAt(int[] p, java.lang.String d) {");
        emitLine("    if (p[0] >= d.length()) return false;");
        emitLine("    char c = d.charAt(p[0]);");
        emitLine("    if (c == '[') {");
        emitLine("        p[0]++;");
        emitLine("        if (!__canonicalAt(p, d)) return false;");
        emitLine("        if (p[0] >= d.length() || d.charAt(p[0]) != ']') return false;");
        emitLine("        p[0]++;");
        emitLine("        return true;");
        emitLine("    }");
        emitLine("    if (c == '?') {");
        emitLine("        p[0]++;");
        emitLine("        int inner = p[0];");
        emitLine("        if (!__canonicalAt(p, d)) return false;");
        emitLine("        if (inner < d.length() && d.charAt(inner) == '?') return false;");
        emitLine("        if (d.startsWith(\"null\", inner) && (inner + 4 == d.length() || d.charAt(inner + 4) == ']' || d.charAt(inner + 4) == ')' || d.charAt(inner + 4) == ',')) return false;");
        emitLine("        return true;");
        emitLine("    }");
        emitLine("    if (c == '(') return __canonicalFunction(p, d);");
        emitLine("    if (c == '@') {");
        emitLine("        p[0]++;");
        emitLine("        boolean sep = false;");
        emitLine("        boolean done = false;");
        emitLine("        while (!done) {");
        emitLine("            int start = p[0];");
        emitLine("            while (p[0] < d.length() && !done) {");
        emitLine("                char b = d.charAt(p[0]);");
        emitLine("                if (java.lang.Character.isHighSurrogate(b)) {");
        emitLine("                    if (p[0] + 1 >= d.length()) {");
        emitLine("                        return false; // a lone high surrogate is not a decoded Unicode scalar");
        emitLine("                    }");
        emitLine("                    if (!java.lang.Character.isLowSurrogate(d.charAt(p[0] + 1))) {");
        emitLine("                        return false;");
        emitLine("                    }");
        emitLine("                    p[0] += 2; // one legal astral-plane scalar (a surrogate pair)");
        emitLine("                } else if (b == ']' || b == ')' || b == ',' || b == '/' || !__componentChar(b)) {");
        emitLine("                    done = true;");
        emitLine("                } else if (b == '-' && p[0] + 1 < d.length() && d.charAt(p[0] + 1) == '>') {");
        emitLine("                    return false;");
        emitLine("                } else {");
        emitLine("                    p[0]++;");
        emitLine("                }");
        emitLine("            }");
        emitLine("            if (p[0] == start) return false;");
        emitLine("            java.lang.String comp = d.substring(start, p[0]);");
        emitLine("            if (comp.equals(\".\") || comp.equals(\"..\")) return false;");
        emitLine("            if (p[0] < d.length() && d.charAt(p[0]) == '/') { sep = true; p[0]++; done = false; }");
        emitLine("            else { return sep && __identifierShape(comp); }");
        emitLine("        }");
        emitLine("        return false;");
        emitLine("    }");
        emitLine("    if (d.startsWith(\"async\", p[0])) {");
        emitLine("        int end = p[0] + 5;");
        emitLine("        if (end < d.length() && __identifierPart(d.charAt(end))) return false;");
        emitLine("        if (end >= d.length() || d.charAt(end) != '(') return false;");
        emitLine("        p[0] = end;");
        emitLine("        return __canonicalFunction(p, d);");
        emitLine("    }");
        emitLine("    java.lang.String[] kws = {\"null\", \"boolean\", \"int\", \"number\", \"string\", \"bytes\", \"table\"};");
        emitLine("    for (java.lang.String kw : kws) {");
        emitLine("        if (d.startsWith(kw, p[0])) { p[0] += kw.length(); return true; }");
        emitLine("    }");
        emitLine("    return false;");
        emitLine("}");
        emitLine("static boolean __canonicalFunction(int[] p, java.lang.String d) {");
        emitLine("    p[0]++;");
        emitLine("    if (p[0] < d.length() && d.charAt(p[0]) != ')') {");
        emitLine("        boolean more = true;");
        emitLine("        while (more) {");
        emitLine("            if (!__canonicalAt(p, d)) return false;");
        emitLine("            if (p[0] >= d.length()) return false;");
        emitLine("            char b = d.charAt(p[0]);");
        emitLine("            if (b == ',') { p[0]++; }");
        emitLine("            else if (b == ')') { more = false; }");
        emitLine("            else { return false; }");
        emitLine("        }");
        emitLine("    }");
        emitLine("    if (p[0] >= d.length()) return false;");
        emitLine("    p[0]++;");
        emitLine("    if (p[0] + 1 >= d.length() || d.charAt(p[0]) != '-' || d.charAt(p[0] + 1) != '>') return false;");
        emitLine("    p[0] += 2;");
        emitLine("    return __canonicalAt(p, d);");
        emitLine("}");
        emitLine("static boolean __identifierPart(char c) {");
        emitLine("    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';");
        emitLine("}");
        emitLine("static boolean __identifierShape(java.lang.String comp) {");
        emitLine("    if (comp.isEmpty()) return false;");
        emitLine("    char f = comp.charAt(0);");
        emitLine("    if (!((f >= 'A' && f <= 'Z') || (f >= 'a' && f <= 'z') || f == '_')) return false;");
        emitLine("    for (int i = 1; i < comp.length(); i++) {");
        emitLine("        if (!__identifierPart(comp.charAt(i))) return false;");
        emitLine("    }");
        emitLine("    return true;");
        emitLine("}");
        emitLine("// The pinned component alphabet, mirroring the compiler-side");
        emitLine("// CanonicalRuntimeTypeDescriptor.forbiddenInComponent: U+0020 and the");
        emitLine("// rest of the pinned White_Space property reject; a valid surrogate");
        emitLine("// pair is consumed as one legal astral-plane scalar in the '@' branch");
        emitLine("// before this check, so only a LONE surrogate code unit fails here.");
        emitLine("static boolean __componentChar(char c) {");
        emitLine("    if (c <= 0x20 || c == 0x7F) return false;");
        emitLine("    switch (c) {");
        emitLine("        case '@', '[', ']', '?', '(', ')', ',': return false;");
        emitLine("    }");
        emitLine("    if (c == 0x85 || c == 0xA0 || c == 0x1680 || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000) return false;");
        emitLine("    if (c >= 0x2000 && c <= 0x200A) return false;");
        emitLine("    if (c >= 0xD800 && c <= 0xDFFF) return false;");
        emitLine("    return true;");
        emitLine("}");
        emitLine("// D1: the origin-threading overload carries the (file/line/column)");
        emitLine("// parameters into every raise this seam performs, including the");
        emitLine("// wrapped re-raises of its delegating helpers; the short overload");
        emitLine("// passes the explicit absent sentinel (null, -1, -1).");
        emitLine("static java.lang.Object $check(java.lang.String descriptor, java.lang.Object v) { return $check(descriptor, v, null, -1, -1); }");
        emitLine("static java.lang.Object $check(java.lang.String descriptor, java.lang.Object v, java.lang.String oFile, int oLine, int oCol) {");
        indent++;
        emitLine("if (!__canonical(descriptor)) throw new DealError(\"E8001\", \"internal: cannot parse type descriptor: \" + descriptor, oFile, oLine, oCol);");
        emitLine("if (descriptor.startsWith(\"?\")) {");
        indent++;
        emitLine("if (v == null) return null;");
        emitLine("descriptor = descriptor.substring(1);");
        indent--;
        emitLine("}");
        emitLine("// null row (the canonical matcher table): the DEAL null (Java null");
        emitLine("// here) passes; the seam previously lacked the row because every");
        emitLine("// statically provable null crossing bypasses $check — the async-export");
        emitLine("// host entry (ISSUE-0161) is the first dynamic null check site.");
        emitLine("if (descriptor.equals(\"null\")) { if (v == null) return null; throw new DealError(\"E8001\", \"expected null\", oFile, oLine, oCol, \"null\", $kindOf(v), null, null); }");
        emitLine("if (descriptor.equals(\"table\")) { if (v instanceof $DealRt.Table t) return t; throw new DealError(\"E8001\", \"expected table\", oFile, oLine, oCol, \"table\", $kindOf(v), null, null); }");
        emitLine("if (descriptor.equals(\"boolean\")) { if (v instanceof java.lang.Boolean b) return b; throw new DealError(\"E8001\", \"expected boolean\", oFile, oLine, oCol, \"boolean\", $kindOf(v), null, null); }");
        emitLine("if (descriptor.equals(\"string\")) { if (v instanceof java.lang.String s) { if (__hasUnpairedSurrogate(s)) throw new DealError(\"E8001\", \"expected string, got string with unpaired surrogate code units\", oFile, oLine, oCol, \"string\", \"string\", null, null); return s; } throw new DealError(\"E8001\", \"expected string\", oFile, oLine, oCol, \"string\", $kindOf(v), null, null); }");
        emitLine(int32Mode
            ? "if (descriptor.equals(\"int\")) { if (v instanceof java.lang.Integer i) return checkInt(i, oFile, oLine, oCol); if (v instanceof java.lang.Double d) { if (d.isNaN()) throw new DealError(\"E8001\", \"expected int, got NaN\", oFile, oLine, oCol, \"int\", \"NaN\", null, null); if (d.isInfinite()) throw new DealError(\"E8001\", \"expected int, got infinity\", oFile, oLine, oCol, \"int\", \"infinity\", null, null); if (d % 1.0 != 0.0) throw new DealError(\"E8001\", \"expected int, got non-integer number\", oFile, oLine, oCol, \"int\", \"number\", null, null); return checkInt((long) (double) d, oFile, oLine, oCol); } throw new DealError(\"E8001\", \"expected int\", oFile, oLine, oCol, \"int\", $kindOf(v), null, null); }"
            : "if (descriptor.equals(\"int\")) { if (v instanceof java.lang.Long l) return checkInt(l, oFile, oLine, oCol); if (v instanceof java.lang.Double d) { if (d.isNaN()) throw new DealError(\"E8001\", \"expected int, got NaN\", oFile, oLine, oCol, \"int\", \"NaN\", null, null); if (d.isInfinite()) throw new DealError(\"E8001\", \"expected int, got infinity\", oFile, oLine, oCol, \"int\", \"infinity\", null, null); if (d % 1.0 != 0.0) throw new DealError(\"E8001\", \"expected int, got non-integer number\", oFile, oLine, oCol, \"int\", \"number\", null, null); return checkInt((long) (double) d, oFile, oLine, oCol); } throw new DealError(\"E8001\", \"expected int\", oFile, oLine, oCol, \"int\", $kindOf(v), null, null); }");
        emitLine(int32Mode
            ? "if (descriptor.equals(\"number\")) { if (v instanceof java.lang.Integer i) return (double) i; if (v instanceof java.lang.Double d) return d; throw new DealError(\"E8001\", \"expected number\", oFile, oLine, oCol, \"number\", $kindOf(v), null, null); }"
            : "if (descriptor.equals(\"number\")) { if (v instanceof java.lang.Long l) return (double) l; if (v instanceof java.lang.Double d) return d; throw new DealError(\"E8001\", \"expected number\", oFile, oLine, oCol, \"number\", $kindOf(v), null, null); }");
        emitLine("// bytes row (the canonical matcher table): the shared $DealRt.Bytes");
        emitLine("// carrier is the JVM bytes representation (ISSUE-0301 D6 — the");
        emitLine("// final runtime-owned wrapper over byte[]); anything else raises");
        emitLine("// E8001. Allocation/indexing/mutation lower to the emitted");
        emitLine("// bytesNew/bytesLength/bytesGet/bytesSet helpers (ISSUE-0158).");
        emitLine("if (descriptor.equals(\"bytes\")) { if (v instanceof $DealRt.Bytes b) return b; throw new DealError(\"E8001\", \"expected bytes\", oFile, oLine, oCol, \"bytes\", $kindOf(v), null, null); }");
        // Class branches (plain class descriptors and per-class array
        // descriptors) are appended by emitClass before the generic [D]
        // dispatch: every generated branch keys on its own descriptor
        // atom, never a prefix of the array/function rows.
        for (String branch : classCheckBranches) {
            emitLine(branch);
        }
        emitLine("// [D] row (the canonical matcher table): $checkArray accepts the");
        emitLine("// typed per-element-shape wrapper (identity) and an array-mode");
        emitLine("// $DealRt.Table (the __jsonTableValue dynamic array");
        emitLine("// representation — elements checked recursively in index order,");
        emitLine("// E8003 at the first failing index); any other value raises");
        emitLine("// E8001 \"expected array\".");
        emitLine("if (descriptor.startsWith(\"[\") && descriptor.endsWith(\"]\")) { return $checkArray(descriptor, v, oFile, oLine, oCol); }");
        emitLine("// function row (the canonical matcher table): a shared");
        emitLine("// $DealRt.FnValue whose carried canonical descriptor equals the");
        emitLine("// expected text byte-for-byte passes; any descriptor delta raises");
        emitLine("// E8010 \"function signature mismatch: expected {D}, got {actual}\";");
        emitLine("// a non-wrapper raises E8001 \"expected function\".");
        emitLine("if (descriptor.startsWith(\"(\") || descriptor.startsWith(\"async(\")) {");
        indent++;
        emitLine("if (v instanceof $DealRt.FnValue f) {");
        indent++;
        emitLine("if (f.descriptor().equals(descriptor)) return v;");
        emitLine("throw new DealError(\"E8010\", \"function signature mismatch: expected \" + descriptor + \", got \" + f.descriptor(), oFile, oLine, oCol, descriptor, f.descriptor(), null, null);");
        indent--;
        emitLine("}");
        emitLine("throw new DealError(\"E8001\", \"expected function\", oFile, oLine, oCol, \"function\", $kindOf(v), null, null);");
        indent--;
        emitLine("}");
        emitLine("throw new DealError(\"E8001\", \"expected \" + descriptor, oFile, oLine, oCol, descriptor, $kindOf(v), null, null);");
        indent--;
        emitLine("}");
        emitLine("// The generated recursive [D] realization (ISSUE-0301 D5): one");
        emitLine("// branch per element shape — the fixed primitive/nullable rows,");
        emitLine("// the per-class rows appended by emitClass, and the generated");
        emitLine("// per-element-shape rows for nested/function/bytes arrays.");
        emitLine("static java.lang.Object $checkArray(java.lang.String descriptor, java.lang.Object v) { return $checkArray(descriptor, v, null, -1, -1); }");
        emitLine("static java.lang.Object $checkArray(java.lang.String descriptor, java.lang.Object v, java.lang.String oFile, int oLine, int oCol) {");
        indent++;
        emitLine("if (descriptor.equals(\"[int]\")) { if (v instanceof $DealRt.__IntArray a) return a; return $dynamicIntArray(v, \"[int]\", \"int\", oFile, oLine, oCol); }");
        emitLine("if (descriptor.equals(\"[number]\")) { if (v instanceof $DealRt.__NumberArray a) return a; return $dynamicNumberArray(v, \"[number]\", \"number\", oFile, oLine, oCol); }");
        emitLine("if (descriptor.equals(\"[string]\")) { if (v instanceof $DealRt.__StringArray a) return a; return $dynamicStringArray(v, \"[string]\", \"string\", oFile, oLine, oCol); }");
        emitLine("if (descriptor.equals(\"[boolean]\")) { if (v instanceof $DealRt.__BooleanArray a) return a; return $dynamicBooleanArray(v, \"[boolean]\", \"boolean\", oFile, oLine, oCol); }");

        emitLine("if (descriptor.equals(\"[bytes]\")) { if (v instanceof $DealRt.__BytesArray a) return a; return $dynamicBytesArray(v, \"[bytes]\", \"bytes\", oFile, oLine, oCol); }");
        emitLine(int32Mode
            ? "if (descriptor.equals(\"[?int]\")) { if (v instanceof $DealRt.__IntOrNullArray a) return a; if (v instanceof $DealRt.__IntArray a) { java.lang.Integer[] nd = new java.lang.Integer[a.data.length]; for (int i = 0; i < a.data.length; i++) nd[i] = java.lang.Integer.valueOf(a.data[i]); return new $DealRt.__IntOrNullArray(nd); } return $dynamicIntOrNullArray(v, \"[?int]\", \"?int\", oFile, oLine, oCol); }"
            : "if (descriptor.equals(\"[?int]\")) { if (v instanceof $DealRt.__IntOrNullArray a) return a; if (v instanceof $DealRt.__IntArray a) { java.lang.Long[] nd = new java.lang.Long[a.data.length]; for (int i = 0; i < a.data.length; i++) nd[i] = java.lang.Long.valueOf(a.data[i]); return new $DealRt.__IntOrNullArray(nd); } return $dynamicIntOrNullArray(v, \"[?int]\", \"?int\", oFile, oLine, oCol); }");
        emitLine("if (descriptor.equals(\"[?number]\")) { if (v instanceof $DealRt.__NumberOrNullArray a) return a; if (v instanceof $DealRt.__NumberArray a) { java.lang.Double[] nd = new java.lang.Double[a.data.length]; for (int i = 0; i < a.data.length; i++) nd[i] = java.lang.Double.valueOf(a.data[i]); return new $DealRt.__NumberOrNullArray(nd); } return $dynamicNumberOrNullArray(v, \"[?number]\", \"?number\", oFile, oLine, oCol); }");
        emitLine("if (descriptor.equals(\"[?string]\")) { if (v instanceof $DealRt.__StringOrNullArray a) return a; if (v instanceof $DealRt.__StringArray a) { java.lang.String[] nd = new java.lang.String[a.data.length]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); return new $DealRt.__StringOrNullArray(nd); } return $dynamicStringOrNullArray(v, \"[?string]\", \"?string\", oFile, oLine, oCol); }");
        emitLine("if (descriptor.equals(\"[?boolean]\")) { if (v instanceof $DealRt.__BooleanOrNullArray a) return a; if (v instanceof $DealRt.__BooleanArray a) { java.lang.Boolean[] nd = new java.lang.Boolean[a.data.length]; for (int i = 0; i < a.data.length; i++) nd[i] = java.lang.Boolean.valueOf(a.data[i]); return new $DealRt.__BooleanOrNullArray(nd); } return $dynamicBooleanOrNullArray(v, \"[?boolean]\", \"?boolean\", oFile, oLine, oCol); }");
        emitLine("if (descriptor.equals(\"[?bytes]\")) { if (v instanceof $DealRt.__BytesOrNullArray a) return a; if (v instanceof $DealRt.__BytesArray a) { $DealRt.Bytes[] nd = new $DealRt.Bytes[a.data.length]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); return new $DealRt.__BytesOrNullArray(nd); } return $dynamicBytesOrNullArray(v, \"[?bytes]\", \"?bytes\", oFile, oLine, oCol); }");
        for (String branch : refArrayCheckBranches) {
            emitLine(branch);
        }
        emitLine("throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, descriptor, $kindOf(v), null, null);");
        indent--;
        emitLine("}");
        // Dynamic [D] conversions: an array-mode $DealRt.Table crossed a
        // typed array boundary converts into fresh typed wrapper storage
        // after each element passes the element row (E8003 at the first
        // failing index — the inner failure is suppressed exactly like
        // LuaJIT's pcall-wrapped element checks).
        emitLine(int32Mode
            ? "static $DealRt.__IntArray $dynamicIntArray(java.lang.Object v) { return $dynamicIntArray(v, \"[int]\", \"int\", null, -1, -1); }"
            : "static $DealRt.__IntArray $dynamicIntArray(java.lang.Object v) { return $dynamicIntArray(v, \"[int]\", \"int\", null, -1, -1); }");
        emitLine(int32Mode
            ? "static $DealRt.__IntArray $dynamicIntArray(java.lang.Object v, java.lang.String arrayText, java.lang.String elemText, java.lang.String oFile, int oLine, int oCol) { if (v instanceof $DealRt.Table t && t.$array() != null) { java.util.ArrayList<java.lang.Object> a = t.$array(); int[] data = new int[a.size()]; for (int i = 0; i < a.size(); i++) { try { data[i] = ((java.lang.Integer) $check(\"int\", a.get(i), oFile, oLine, oCol)).intValue(); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol, elemText, $kindOf(a.get(i)), null, null); } } return new $DealRt.__IntArray(data); } throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, arrayText, $kindOf(v), null, null); }"
            : "static $DealRt.__IntArray $dynamicIntArray(java.lang.Object v, java.lang.String arrayText, java.lang.String elemText, java.lang.String oFile, int oLine, int oCol) { if (v instanceof $DealRt.Table t && t.$array() != null) { java.util.ArrayList<java.lang.Object> a = t.$array(); long[] data = new long[a.size()]; for (int i = 0; i < a.size(); i++) { try { data[i] = ((java.lang.Long) $check(\"int\", a.get(i), oFile, oLine, oCol)).longValue(); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol, elemText, $kindOf(a.get(i)), null, null); } } return new $DealRt.__IntArray(data); } throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, arrayText, $kindOf(v), null, null); }");
        emitLine("static $DealRt.__NumberArray $dynamicNumberArray(java.lang.Object v) { return $dynamicNumberArray(v, \"[number]\", \"number\", null, -1, -1); }");
        emitLine("static $DealRt.__NumberArray $dynamicNumberArray(java.lang.Object v, java.lang.String arrayText, java.lang.String elemText, java.lang.String oFile, int oLine, int oCol) { if (v instanceof $DealRt.Table t && t.$array() != null) { java.util.ArrayList<java.lang.Object> a = t.$array(); double[] data = new double[a.size()]; for (int i = 0; i < a.size(); i++) { try { data[i] = ((java.lang.Double) $check(\"number\", a.get(i), oFile, oLine, oCol)).doubleValue(); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol, elemText, $kindOf(a.get(i)), null, null); } } return new $DealRt.__NumberArray(data); } throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, arrayText, $kindOf(v), null, null); }");
        emitLine("static $DealRt.__StringArray $dynamicStringArray(java.lang.Object v) { return $dynamicStringArray(v, \"[string]\", \"string\", null, -1, -1); }");
        emitLine("static $DealRt.__StringArray $dynamicStringArray(java.lang.Object v, java.lang.String arrayText, java.lang.String elemText, java.lang.String oFile, int oLine, int oCol) { if (v instanceof $DealRt.Table t && t.$array() != null) { java.util.ArrayList<java.lang.Object> a = t.$array(); java.lang.String[] data = new java.lang.String[a.size()]; for (int i = 0; i < a.size(); i++) { try { data[i] = (java.lang.String) $check(\"string\", a.get(i), oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol, elemText, $kindOf(a.get(i)), null, null); } } return new $DealRt.__StringArray(data); } throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, arrayText, $kindOf(v), null, null); }");
        emitLine("static $DealRt.__BooleanArray $dynamicBooleanArray(java.lang.Object v) { return $dynamicBooleanArray(v, \"[boolean]\", \"boolean\", null, -1, -1); }");
        emitLine("static $DealRt.__BooleanArray $dynamicBooleanArray(java.lang.Object v, java.lang.String arrayText, java.lang.String elemText, java.lang.String oFile, int oLine, int oCol) { if (v instanceof $DealRt.Table t && t.$array() != null) { java.util.ArrayList<java.lang.Object> a = t.$array(); boolean[] data = new boolean[a.size()]; for (int i = 0; i < a.size(); i++) { try { data[i] = ((java.lang.Boolean) $check(\"boolean\", a.get(i), oFile, oLine, oCol)).booleanValue(); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol, elemText, $kindOf(a.get(i)), null, null); } } return new $DealRt.__BooleanArray(data); } throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, arrayText, $kindOf(v), null, null); }");
        emitLine("static $DealRt.__BytesArray $dynamicBytesArray(java.lang.Object v) { return $dynamicBytesArray(v, \"[bytes]\", \"bytes\", null, -1, -1); }");
        emitLine("static $DealRt.__BytesArray $dynamicBytesArray(java.lang.Object v, java.lang.String arrayText, java.lang.String elemText, java.lang.String oFile, int oLine, int oCol) { if (v instanceof $DealRt.Table t && t.$array() != null) { java.util.ArrayList<java.lang.Object> a = t.$array(); $DealRt.Bytes[] data = new $DealRt.Bytes[a.size()]; for (int i = 0; i < a.size(); i++) { try { data[i] = ($DealRt.Bytes) $check(\"bytes\", a.get(i), oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol, elemText, $kindOf(a.get(i)), null, null); } } return new $DealRt.__BytesArray(data); } throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, arrayText, $kindOf(v), null, null); }");
        emitLine("static $DealRt.__BytesOrNullArray $dynamicBytesOrNullArray(java.lang.Object v) { return $dynamicBytesOrNullArray(v, \"[?bytes]\", \"?bytes\", null, -1, -1); }");
        emitLine("static $DealRt.__BytesOrNullArray $dynamicBytesOrNullArray(java.lang.Object v, java.lang.String arrayText, java.lang.String elemText, java.lang.String oFile, int oLine, int oCol) { if (v instanceof $DealRt.Table t && t.$array() != null) { java.util.ArrayList<java.lang.Object> a = t.$array(); $DealRt.Bytes[] data = new $DealRt.Bytes[a.size()]; for (int i = 0; i < a.size(); i++) { try { data[i] = ($DealRt.Bytes) $check(\"?bytes\", a.get(i), oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol, elemText, $kindOf(a.get(i)), null, null); } } return new $DealRt.__BytesOrNullArray(data); } throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, arrayText, $kindOf(v), null, null); }");
        emitLine(int32Mode
            ? "static $DealRt.__IntOrNullArray $dynamicIntOrNullArray(java.lang.Object v) { return $dynamicIntOrNullArray(v, \"[?int]\", \"?int\", null, -1, -1); }"
            : "static $DealRt.__IntOrNullArray $dynamicIntOrNullArray(java.lang.Object v) { return $dynamicIntOrNullArray(v, \"[?int]\", \"?int\", null, -1, -1); }");
        emitLine(int32Mode
            ? "static $DealRt.__IntOrNullArray $dynamicIntOrNullArray(java.lang.Object v, java.lang.String arrayText, java.lang.String elemText, java.lang.String oFile, int oLine, int oCol) { if (v instanceof $DealRt.Table t && t.$array() != null) { java.util.ArrayList<java.lang.Object> a = t.$array(); java.lang.Integer[] data = new java.lang.Integer[a.size()]; for (int i = 0; i < a.size(); i++) { try { data[i] = (java.lang.Integer) $check(\"?int\", a.get(i), oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol, elemText, $kindOf(a.get(i)), null, null); } } return new $DealRt.__IntOrNullArray(data); } throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, arrayText, $kindOf(v), null, null); }"
            : "static $DealRt.__IntOrNullArray $dynamicIntOrNullArray(java.lang.Object v, java.lang.String arrayText, java.lang.String elemText, java.lang.String oFile, int oLine, int oCol) { if (v instanceof $DealRt.Table t && t.$array() != null) { java.util.ArrayList<java.lang.Object> a = t.$array(); java.lang.Long[] data = new java.lang.Long[a.size()]; for (int i = 0; i < a.size(); i++) { try { data[i] = (java.lang.Long) $check(\"?int\", a.get(i), oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol, elemText, $kindOf(a.get(i)), null, null); } } return new $DealRt.__IntOrNullArray(data); } throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, arrayText, $kindOf(v), null, null); }");
        emitLine("static $DealRt.__NumberOrNullArray $dynamicNumberOrNullArray(java.lang.Object v) { return $dynamicNumberOrNullArray(v, \"[?number]\", \"?number\", null, -1, -1); }");
        emitLine("static $DealRt.__NumberOrNullArray $dynamicNumberOrNullArray(java.lang.Object v, java.lang.String arrayText, java.lang.String elemText, java.lang.String oFile, int oLine, int oCol) { if (v instanceof $DealRt.Table t && t.$array() != null) { java.util.ArrayList<java.lang.Object> a = t.$array(); java.lang.Double[] data = new java.lang.Double[a.size()]; for (int i = 0; i < a.size(); i++) { try { data[i] = (java.lang.Double) $check(\"?number\", a.get(i), oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol, elemText, $kindOf(a.get(i)), null, null); } } return new $DealRt.__NumberOrNullArray(data); } throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, arrayText, $kindOf(v), null, null); }");
        emitLine("static $DealRt.__StringOrNullArray $dynamicStringOrNullArray(java.lang.Object v) { return $dynamicStringOrNullArray(v, \"[?string]\", \"?string\", null, -1, -1); }");
        emitLine("static $DealRt.__StringOrNullArray $dynamicStringOrNullArray(java.lang.Object v, java.lang.String arrayText, java.lang.String elemText, java.lang.String oFile, int oLine, int oCol) { if (v instanceof $DealRt.Table t && t.$array() != null) { java.util.ArrayList<java.lang.Object> a = t.$array(); java.lang.String[] data = new java.lang.String[a.size()]; for (int i = 0; i < a.size(); i++) { try { data[i] = (java.lang.String) $check(\"?string\", a.get(i), oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol, elemText, $kindOf(a.get(i)), null, null); } } return new $DealRt.__StringOrNullArray(data); } throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, arrayText, $kindOf(v), null, null); }");
        emitLine("static $DealRt.__BooleanOrNullArray $dynamicBooleanOrNullArray(java.lang.Object v) { return $dynamicBooleanOrNullArray(v, \"[?boolean]\", \"?boolean\", null, -1, -1); }");
        emitLine("static $DealRt.__BooleanOrNullArray $dynamicBooleanOrNullArray(java.lang.Object v, java.lang.String arrayText, java.lang.String elemText, java.lang.String oFile, int oLine, int oCol) { if (v instanceof $DealRt.Table t && t.$array() != null) { java.util.ArrayList<java.lang.Object> a = t.$array(); java.lang.Boolean[] data = new java.lang.Boolean[a.size()]; for (int i = 0; i < a.size(); i++) { try { data[i] = (java.lang.Boolean) $check(\"?boolean\", a.get(i), oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol, elemText, $kindOf(a.get(i)), null, null); } } return new $DealRt.__BooleanOrNullArray(data); } throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, arrayText, $kindOf(v), null, null); }");

        emitLine("static java.lang.Object $hostCheckArray(java.lang.String descriptor, java.lang.Object v) { return $hostCheckArray(descriptor, v, null, -1, -1); }");
        emitLine("static java.lang.Object $hostCheckArray(java.lang.String descriptor, java.lang.Object v, java.lang.String oFile, int oLine, int oCol) {");
        indent++;
        emitLine("java.lang.Object a = $check(descriptor, v, oFile, oLine, oCol);");
        emitLine(int32Mode
            ? "if (descriptor.equals(\"[int]\")) { $DealRt.__IntArray w = ($DealRt.__IntArray) a; for (int i = 0; i < w.data.length; i++) { try { $check(\"int\", java.lang.Integer.valueOf(w.data[i]), oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol); } } return a; }"
            : "if (descriptor.equals(\"[int]\")) { $DealRt.__IntArray w = ($DealRt.__IntArray) a; for (int i = 0; i < w.data.length; i++) { try { $check(\"int\", java.lang.Long.valueOf(w.data[i]), oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol); } } return a; }");
        emitLine("if (descriptor.equals(\"[number]\")) { $DealRt.__NumberArray w = ($DealRt.__NumberArray) a; for (int i = 0; i < w.data.length; i++) { try { $check(\"number\", java.lang.Double.valueOf(w.data[i]), oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol); } } return a; }");
        emitLine("if (descriptor.equals(\"[string]\")) { $DealRt.__StringArray w = ($DealRt.__StringArray) a; for (int i = 0; i < w.data.length; i++) { try { $check(\"string\", w.data[i], oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol); } } return a; }");
        emitLine("if (descriptor.equals(\"[boolean]\")) { $DealRt.__BooleanArray w = ($DealRt.__BooleanArray) a; for (int i = 0; i < w.data.length; i++) { try { $check(\"boolean\", java.lang.Boolean.valueOf(w.data[i]), oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol); } } return a; }");
        emitLine("if (descriptor.equals(\"[bytes]\")) { $DealRt.__BytesArray w = ($DealRt.__BytesArray) a; for (int i = 0; i < w.data.length; i++) { try { $check(\"bytes\", w.data[i], oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol); } } return a; }");
        emitLine("if (descriptor.equals(\"[?int]\")) { $DealRt.__IntOrNullArray w = ($DealRt.__IntOrNullArray) a; for (int i = 0; i < w.data.length; i++) { try { $check(\"?int\", w.data[i], oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol); } } return a; }");
        emitLine("if (descriptor.equals(\"[?number]\")) { $DealRt.__NumberOrNullArray w = ($DealRt.__NumberOrNullArray) a; for (int i = 0; i < w.data.length; i++) { try { $check(\"?number\", w.data[i], oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol); } } return a; }");
        emitLine("if (descriptor.equals(\"[?string]\")) { $DealRt.__StringOrNullArray w = ($DealRt.__StringOrNullArray) a; for (int i = 0; i < w.data.length; i++) { try { $check(\"?string\", w.data[i], oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol); } } return a; }");
        emitLine("if (descriptor.equals(\"[?boolean]\")) { $DealRt.__BooleanOrNullArray w = ($DealRt.__BooleanOrNullArray) a; for (int i = 0; i < w.data.length; i++) { try { $check(\"?boolean\", w.data[i], oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol); } } return a; }");
        emitLine("if (descriptor.equals(\"[?bytes]\")) { $DealRt.__BytesOrNullArray w = ($DealRt.__BytesOrNullArray) a; for (int i = 0; i < w.data.length; i++) { try { $check(\"?bytes\", w.data[i], oFile, oLine, oCol); } catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol); } } return a; }");
        for (String branch : hostRefArrayCheckBranches) {
            emitLine(branch);
        }
        emitLine("return a;");
        indent--;
        emitLine("}");
    }

    /**
     * Registers the wrapper class for a function signature and returns
     * its Java type reference (e.g. {@code $DealRt.Fn2_I_I_R_I} for
     * {@code (int,int)->int}). One abstract class per distinct signature
     * shape, declared inside the SHARED {@code $DealRt} runtime scope
     * (emitted once per compiled project by the selected entry module),
     * so wrapper values cross module boundaries with one javac-visible
     * type. The class carries the canonical runtime descriptor string
     * (produced by the one {@link #typeDescriptor} emitter) and an
     * {@code invoke} method with the JVM-mapped signature. The class
     * text is accumulated and spliced into the {@code $DealRt} body
     * right after the fixed carrier classes.
     */
    private String registerWrapperShape(Type.Func f) {
        // Every wrapper registers on the BACKEND-held class identities:
        // declaration/expression sites register from checker-held
        // function types (fs.funcType()/typeOf), whose local class
        // identity records differ from the backend-held ones in the
        // single-module harness adapters (the checker types with the
        // source filename, codegen runs with the module path) — the
        // wrapper descriptor must carry the backend identity text the
        // emitted runtime class carries, and every registration of the
        // same signature must produce the same shape id.
        f = normalizeLocalClassIdentities(f);
        String id = fnShapeId(f);

        if (!isRepresentableSignature(f)) return null;
        if (emittedWrapperShapes.add(id)) {
            StringBuilder body = new StringBuilder();
            body.append("// DEAL function-value wrapper for descriptor ")
                .append(quoteJavaString(fnDescriptor(f))).append("\n");
            body.append("static abstract class ").append(id)
                .append(" implements FnValue {\n");
            body.append("    final java.lang.String descriptor = ")
                .append(quoteJavaString(fnDescriptor(f))).append(";\n");
            body.append("    public java.lang.String descriptor() { return descriptor; }\n");
            body.append("    abstract ").append(silentReturnJavaType(f.returnType()))
                .append(" invoke(");
            for (int i = 0; i < f.paramTypes().size(); i++) {
                if (i > 0) body.append(", ");
                body.append(silentJavaLocalType(f.paramTypes().get(i)))
                    .append(" p").append(i);
            }
            body.append(");\n");
            // The DEAL-source-aware dispatch seam: the emitted call sites
            // dispatch through invokeAt with the DEAL call site's origin,
            // and the default body delegates to the plain host-facing
            // invoke (a wrapper that reports DEAL boundary raises — the
            // host export carrier — overrides invokeAt to thread the
            // origin into its wrapper call). The host-facing invoke ABI
            // is unchanged, so host implementations never move.
            body.append("    ").append(silentReturnJavaType(f.returnType()))
                .append(" invokeAt(java.lang.String file, int line, "
                    + "int column");
            for (int i = 0; i < f.paramTypes().size(); i++) {
                body.append(", ");
                body.append(silentJavaLocalType(f.paramTypes().get(i)))
                    .append(" p").append(i);
            }
            body.append(") { ");
            if ("void".equals(silentReturnJavaType(f.returnType()))) {
                body.append("invoke(")
                    .append(forwardedShapeArgs(f.paramTypes().size()))
                    .append(");");
            } else {
                body.append("return invoke(")
                    .append(forwardedShapeArgs(f.paramTypes().size()))
                    .append(");");
            }
            body.append(" }\n");
            body.append("}\n");
            // The class text is spliced into the $DealRt body at indent
            // 1; prefix every line with the class-body indent.
            for (String line : body.toString().split("\n", -1)) {
                if (line.isEmpty()) continue;
                sharedWrapperClasses.append("    ").append(line).append('\n');
            }
        }
        return "$DealRt." + id;
    }

    private boolean isRepresentableSignature(Type.Func f) {
        String ret = silentReturnJavaType(f.returnType());
        if (ret == null) return false;
        for (Type p : f.paramTypes()) {
            if (silentJavaLocalType(p) == null) return false;
        }
        return true;
    }

    /** Diagnostic-free mirror of {@link #javaLocalType}: identical
     * carriers for every representable type, {@code null} instead of an
     * E6000 for every unrepresentable one. Used ONLY to build the shared
     * wrapper classes (pre-registration of collected shapes must never
     * invent diagnostics) and by {@link #isRepresentableSignature}. */
    private String silentJavaLocalType(Type t) {
        return switch (t) {
            case Type.Int ignored -> int32Mode ? "int" : "long";
            case Type.Number ignored -> "double";
            case Type.Boolean ignored -> "boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Null ignored -> "java.lang.Void";
            case Type.Array a -> silentArrayWrapperName(a.element());
            case Type.Table ignored -> "$DealRt.Table";
            case Type.Class c -> silentClassJavaType(c);
            case Type.Nullable n -> silentNullableJavaType(n.inner());
            case Type.Func f -> registerWrapperShape(f);
            case Type.Bytes ignored -> "$DealRt.Bytes";
            default -> null;
        };
    }

    /** Diagnostic-free mirror of {@link #arrayWrapperName} with every
     * per-class wrapper fully qualified by its declaring module class
     * ({@code Main.$Array$C}, {@code Lib.$Array$C}) — the wrapper text
     * lives inside the shared {@code $DealRt} scope. */
    private String silentArrayWrapperName(Type element) {
        if (element instanceof Type.Class c) {
            return silentClassArrayWrapperName(c, false);
        }
        if (element instanceof Type.Nullable ne
                && ne.inner() instanceof Type.Class c) {
            return silentClassArrayWrapperName(c, true);
        }
        return arrayWrapperName(element);
    }

    private String silentClassArrayWrapperName(Type.Class c,
            boolean orNull) {
        String wrapper = orNull
            ? classOrNullArrayWrapperName(c.name())
            : classArrayWrapperName(c.name());

        if (isHostModuleClass(c)) {
            return hostClassArrayWrapperRef(c);
        }
        if (isLocalClassType(c) && moduleClasses.containsKey(c.name())) {
            return classNameFor(modulePath) + "." + wrapper;
        }
        // Same fallback as silentClassJavaType: the direct-import alias
        // map first, then the compilation-wide identity surface.
        String module = declaringModulePath(c);
        if (module == null) {
            return null;
        }
        return classNameFor(module) + "." + wrapper;
    }

    private String silentClassJavaType(Type.Class c) {
        if (isBuiltinErrorType(c)) {
            return "java.lang.RuntimeException";
        }

        if (isHostModuleClass(c)) {
            return hostRecordJavaRef(c);
        }
        if (isLocalClassType(c) && moduleClasses.containsKey(c.name())) {
            return classNameFor(modulePath) + "."
                + classNameForClass(c.name());
        }
        // An imported class, or a sibling-module class whose directory
        // identity this module shares, resolves through
        // declaringModulePath — the direct-import alias map first, then
        // the compilation-wide identity surface.
        String module = declaringModulePath(c);
        if (module == null) {
            return null;
        }
        return classNameFor(module) + "." + classNameForClass(c.name());
    }

    private boolean isHostModuleClass(Type.Class c) {
        CanonicalModuleIdentity mi = c.identity().moduleIdentity();
        if (mi instanceof CanonicalModuleIdentity.ExternalModule em) {
            String raw = em.rawImportSpecifier();
            if (hostModules.containsKey(raw)) {
                return true;
            }
            if (hostModules.containsKey(raw.replace('.', '/'))) {
                return true;
            }
            if (hostModules.containsKey(raw.replace('/', '.'))) {
                return true;
            }

            if (sharedHostClasses.containsKey(raw)) {
                return true;
            }
            if (sharedHostClasses.containsKey(raw.replace('.', '/'))) {
                return true;
            }
            if (sharedHostClasses.containsKey(raw.replace('/', '.'))) {
                return true;
            }
        }
        return false;
    }

    /** Diagnostic-free mirror of {@link #nullableJavaType}. */
    private String silentNullableJavaType(Type inner) {
        return switch (inner) {
            case Type.Int ignored ->
                int32Mode ? "java.lang.Integer" : "java.lang.Long";
            case Type.Number ignored -> "java.lang.Double";
            case Type.Boolean ignored -> "java.lang.Boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Class c -> silentClassJavaType(c);
            case Type.Array a -> silentArrayWrapperName(a.element());
            case Type.Func f -> registerWrapperShape(f);
            case Type.Bytes ignored -> "$DealRt.Bytes";
            default -> null;
        };
    }

    /** Diagnostic-free mirror of {@link #javaReturnType}. */
    private String silentReturnJavaType(Type t) {
        if (t instanceof Type.Null) return "void";
        return silentJavaLocalType(t);
    }

    /**
     * The deterministic injective wrapper class id for a function
     * signature ({@code Fn2_I_I_R_I} for {@code (int,int)->int},
     * {@code FnA1_I_R_I} for {@code async (int)->int}). The encoding
     * covers the complete canonical type grammar: primitives use the
     * pinned single-letter codes ({@code B}/{@code I}/{@code N}/
     * {@code S}/{@code V}/{@code Y}), and every other type — bytes
     * composites, arrays, nullables, classes (by canonical identity
     * text), and nested sync/async functions — uses a {@code $}-prefixed
     * escape of the canonical descriptor text produced by the one
     * {@link #typeDescriptor} emitter. Distinct canonical types map to
     * distinct ids by construction: the single-letter codes are
     * disjoint, complex segments carry injectively escaped descriptor
     * text, the {@code _} separators never occur inside a segment (the
     * escape maps {@code _} to {@code $u}), and the async marker is a
     * dedicated {@code A} position — so {@code async (int)->int} and
     * {@code (int)->int} never share a class. The name can never collide
     * with a {@link #javaName}-translated user identifier: {@code javaName}
     * output contains neither {@code _} nor {@code $}.
     */
    /**
     * The deterministic injective signature-shape id over the complete
     * canonical grammar, over the supplied descriptor service (the
     * static form: the backend instance delegates with the compilation's
     * service; the property tests supply their own).
     */
    public static String fnShapeId(Type.Func f) {
        StringBuilder sb = new StringBuilder("Fn");
        if (f.isAsync()) sb.append('A');
        sb.append(f.paramTypes().size());
        if (!f.paramTypes().isEmpty()) {
            sb.append('_');
            for (int i = 0; i < f.paramTypes().size(); i++) {
                if (i > 0) sb.append('_');
                sb.append(shapeSegment(f.paramTypes().get(i)));
            }
        }
        sb.append("_R_").append(shapeSegment(f.returnType()));
        return sb.toString();
    }

    /** One segment of {@link #fnShapeId}: the single-letter code for a
     * primitive, or {@code $} + the injectively escaped canonical
     * descriptor text for any other type. */
    private static String shapeSegment(Type t) {
        Character c = fnShapeLetter(t);
        if (c != null) return c.toString();
        return "$" + escapedIdentifier(typeDescriptor(t));
    }

    /** The one-letter shape code ({@code B}/{@code I}/{@code N}/
     * {@code S}/{@code V}/{@code Y} for boolean/int/number/string/null/
     * bytes). */
    private static Character fnShapeLetter(Type t) {
        return switch (t) {
            case Type.Boolean ignored -> 'B';
            case Type.Int ignored -> 'I';
            case Type.Number ignored -> 'N';
            case Type.String ignored -> 'S';
            case Type.Null ignored -> 'V';
            case Type.Bytes ignored -> 'Y';
            default -> null;
        };
    }

    /**
     * The injective identifier-safe escape of arbitrary text: ASCII
     * letters/digits stay raw, {@code $} becomes {@code $$}, {@code _}
     * becomes {@code $u}, the descriptor punctuation uses compact
     * two-character codes ({@code (}→{@code $l}, {@code )}→{@code $r},
     * {@code [}→{@code $B}, {@code ]}→{@code $E}, {@code ?}→{@code $Q},
     * {@code @}→{@code $a}, {@code -}→{@code $m}, {@code >}→{@code $g},
     * {@code ,}→{@code $c}, {@code .}→{@code $i}, {@code /}→{@code $s} —
     * keeping emitted class-file names far below the filesystem length
     * bound), and every other UTF-16 code unit becomes {@code $x} +
     * four lowercase hex digits. The code is prefix-free (a decoder
     * reading {@code $} consumes {@code $$}/{@code $u}/{@code $xhhhh}
     * or one two-character code greedily and unambiguously; {@code x}
     * never doubles as a two-character code), so distinct input texts
     * map to distinct output texts, and the output contains no raw
     * {@code _} — segment concatenations joined by {@code _} stay
     * injective.
     */
    public static String escapedIdentifier(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (c == '$') {
                sb.append("$$");
            } else if (c == '_') {
                sb.append("$u");
            } else {
                appendEscapedChar(sb, c);
            }
        }
        return sb.toString();
    }

    /** One escaped non-identifier code unit of {@link #escapedIdentifier}. */
    private static void appendEscapedChar(StringBuilder sb, char c) {
        switch (c) {
            case '(' -> sb.append("$l");
            case ')' -> sb.append("$r");
            case '[' -> sb.append("$B");
            case ']' -> sb.append("$E");
            case '?' -> sb.append("$Q");
            case '@' -> sb.append("$a");
            case '-' -> sb.append("$m");
            case '>' -> sb.append("$g");
            case ',' -> sb.append("$c");
            case '.' -> sb.append("$i");
            case '/' -> sb.append("$s");
            default -> sb.append(String.format("$x%04x", (int) c));
        }
    }

    /** The function-signature runtime descriptor — the ONE descriptor
     * producer for every wrapper descriptor, {@code $check} call-site
     * literal, and host-boundary descriptor: {@link #typeDescriptor(Type)}
     * (the compilation's canonical descriptor service). */
    private String fnDescriptor(Type.Func f) {
        return typeDescriptor(f);
    }

    // =========================================================================
    // Statements
    // =========================================================================

    private void emitStatement(StatementNode stmt) {
        // Reject value uses of a variable before its own declaration (and
        // forward references to later-declared module fields) — LuaJIT reads
        // nil for such uses and fails at runtime; a Java forward reference
        // would make javac reject an artifact the CLI reported as
        // successful. Only the statement's own value positions are checked;
        // nested statements are checked individually when emitted, so
        // sequential declarations inside a block stay clean.
        String undeclared = undeclaredVariableUse(stmt);
        if (undeclared != null) {
            unsupported("use of '" + undeclared + "' before its declaration "
                + "with no enclosing binding (Java rejects the forward "
                + "reference; LuaJIT reads the not-yet-declared global value "
                + "here, which is nil unless a prior write established it)",
                stmt.span());
            return;
        }

        switch (stmt) {
            case VariableDeclaration vd -> emitVariable(vd);
            case FunctionDeclaration fd -> emitFunction(fd, false);
            case ReturnStatement rs -> emitReturn(rs);
            case IfStatement is -> emitIf(is);
            case Block b -> emitBlock(b);
            case ExpressionStatement es -> emitExpressionStatement(es);
            case ImportDeclaration id -> emitImportTrigger(id);
            case ExportDeclaration ed -> emitExport(ed);
            case ClassDeclaration cd -> {
                if (moduleLevel) emitClass(cd);
                else unsupported("class declarations nested inside functions "
                    + "or blocks", cd.span());
            }
            case WhileStatement ws -> emitWhile(ws);
            case ForStatement fs -> emitFor(fs);
            case ForOfStatement fos -> emitForOf(fos);
            case BreakStatement bs -> emitBreak(bs);
            case ContinueStatement cs -> emitContinue(cs);
            case DeleteStatement ds -> emitDelete(ds);
            case TryStatement ts -> emitTry(ts);
            case ThrowStatement th -> emitThrow(th);
        }
    }

    private void emitExport(ExportDeclaration ed) {
        if (ed.declaration() instanceof FunctionDeclaration fd) {
            emitFunction(fd, true);
        } else if (ed.declaration() instanceof ClassDeclaration cd) {

            emitClass(cd);
        } else {
            unsupported("this export form", ed.span());
        }
    }

    private void emitImportTrigger(ImportDeclaration id) {

        String raw = hostAliases.get(id.alias());
        if (raw != null) {
            emitLine("static {");
            indent++;
            emitLine(hostLoadMethodName(id.alias()) + "("
                + originArgs(id.span()) + ");");
            indent--;
            emitLine("}");
            return;
        }
        String module = importAliases.get(id.alias());
        if (module == null || SUPPORTED_STDLIB_MODULES.contains(module)) {
            return; // stdlib builtins: no load-time trigger
        }
        String className = classNameFor(module);
        emitLine("static {");
        indent++;
        emitLine(className + ".__init$();");
        indent--;
        emitLine("}");
    }

    /**
     * Emits the host-module binding section once per generated class,
     * right after the runtime support: per-alias load methods (the
     * load-time presence checks the import triggers run), the per-export
     * {@code java.lang.reflect.Method} static fields, and the per-export
     * wrapper methods that DEAL member calls route through. Every emitted
     * name starts with {@code __host} or {@code $host}, which
     * {@link #javaName} can never produce (DEAL identifiers cannot
     * contain {@code $}, and every underscore escapes to {@code $u}), so
     * no user binding can collide with them.
     */
    private void emitHostBindings() {
        if (hostAliases.isEmpty()) return;
        emitLine("// ---- Host module bindings (ISSUE-0100 JVM host ABI slice; ISSUE-0303");
        emitLine("// array/function/class shapes) ----");
        emitLine("// A host module is a Java class named by the module path");
        emitLine("// (host/http -> HostHttp) whose static methods implement the");
        emitLine("// declared function exports (spec-v1.2 \u00a7JVM value mapping:");
        emitLine("// int -> int/long, number -> double, boolean -> boolean, string ->");
        emitLine("// java.lang.String, T | null -> the boxed reference, null -> Java");
        emitLine("// null; arrays arrive as the shared $DealRt wrapper classes,");
        emitLine("// function-typed parameters arrive as their typed $DealRt");
        emitLine("// wrapper, class-typed parameters/returns arrive as the");
        emitLine("// synthesized shared record). Return values arrive as");
        emitLine("// java.lang.Object across the untyped host boundary and are");
        emitLine("// runtime-checked against the declared return descriptor on every");
        emitLine("// call (E8010 mismatch; the spec forbids exposing Java null as");
        emitLine("// DEAL null without validation). Async exports must return a");
        emitLine("// java.util.concurrent.CompletableFuture (E8010 otherwise); the");
        emitLine("// await site joins it and checks the completion value (E8001).");
        emitLine("// ISSUE-0303 D2: every wrapper parameter is java.lang.Object and");
        emitLine("// the wrapper checks each argument against the declared");
        emitLine("// parameter descriptor at the call (E8010 'parameter {i} type");
        emitLine("// mismatch', never a masking read-site error; class-typed");
        emitLine("// parameters keep the nominal E8001).");
        for (Map.Entry<String, String> e : hostAliases.entrySet()) {
            String alias = e.getKey();
            String raw = e.getValue();
            Map<String, Type> exports = hostModules.get(raw);
            // The Method field per function export is written by the load
            // method's presence check before any wrapper can run (the
            // import's static block precedes every later module-level use,
            // and function bodies run after module load). Class exports
            // capture the host's <C>_defaults map instead (D4 — the
            // synthesized record construction depends on it; a missing
            // defaults field is a load-time E8011).
            for (Map.Entry<String, Type> ex : exports.entrySet()) {
                if (ex.getValue() instanceof Type.Func) {
                    emitLine("static java.lang.reflect.Method "
                        + hostMethodFieldName(alias, ex.getKey()) + ";");
                } else if (ex.getValue() instanceof Type.Class c) {
                    emitLine("static java.util.Map<java.lang.String,"
                        + " java.lang.Object> "
                        + hostDefaultsFieldName(alias, c.name()) + ";");
                    registerHostClassCheckBranch(c);
                }
            }
            emitHostLoadMethod(alias, raw, exports);
        }
        for (Map.Entry<String, String> e : hostAliases.entrySet()) {
            String alias = e.getKey();
            String raw = e.getValue();
            Map<String, Type> exports = hostModules.get(raw);
            for (Map.Entry<String, Type> ex : exports.entrySet()) {
                if (ex.getValue() instanceof Type.Func f) {
                    emitHostWrapperMethod(alias, raw, ex.getKey(), f);
                }
            }
        }

        for (Map.Entry<String, String> e : hostAliases.entrySet()) {
            String alias = e.getKey();
            Map<String, Type> exports = hostModules.get(e.getValue());
            for (Map.Entry<String, Type> ex : exports.entrySet()) {
                if (ex.getValue() instanceof Type.Func f) {
                    String shape = registerWrapperShape(f);
                    if (shape != null) {
                        emitHostFnValueField(alias, ex.getKey(), f, shape);
                    }
                }
            }
        }
    }

    /** Emitted name of the load-time presence-check method for a host
     * import alias ({@code __hostLoad$<alias>}). */
    private String hostLoadMethodName(String alias) {
        return "__hostLoad$" + javaName(alias);
    }

    /** Emitted name of the cached {@code java.lang.reflect.Method} field
     * for one declared host export ({@code $host$<alias>$<fn>$m}). */
    private String hostMethodFieldName(String alias, String exportName) {
        return "$host$" + javaName(alias) + "$" + javaName(exportName) + "$m";
    }

    /** Emitted name of the wrapper method for one declared host export
     * ({@code __host$<alias>$<fn>}); DEAL calls {@code alias.fn(args)}
     * route through it. */
    private String hostWrapperName(String alias, String exportName) {
        return "__host$" + javaName(alias) + "$" + javaName(exportName);
    }

    /** The forwarded parameter references {@code p0, p1, …} of a shape
     * wrapper's dispatch methods (the read-only invoke ABI). */
    private static String forwardedShapeArgs(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append("p").append(i);
        }
        return sb.toString();
    }

    /** The argument list of an emitted indirect dispatch: the DEAL
     * call-site origin triple followed by the arguments. */
    private static String invokeAtArgs(String origins, String forwarded) {
        return forwarded.isEmpty()
            ? origins
            : origins + ", " + forwarded;
    }

    /** The span of the await expression whose direct callee is currently
     * being emitted: the completion-check origin of a host async call
     * (the blocking lowering performs the completion check inside the
     * wrapper but reports the await site, exactly like the LuaJIT
     * reference). */
    private Span pendingAwaitCalleeSpan;

    /** The direct callee of the await expression whose span is in
     * {@link #pendingAwaitCalleeSpan}. */
    private ExpressionNode pendingAwaitCallee;

    private String hostFnValueFieldName(String alias, String exportName) {
        return "$host$" + javaName(alias) + "$" + javaName(exportName)
            + "$fn";
    }

    private void emitHostFnValueField(String alias, String exportName,
                                      Type.Func f, String shape) {
        StringBuilder body = new StringBuilder();
        body.append("static final ").append(shape).append(' ')
            .append(hostFnValueFieldName(alias, exportName))
            .append(" = new ").append(shape).append("() {\n");
        List<String> argNames = new ArrayList<>();
        String ret = silentReturnJavaType(f.returnType());
        // The plain host-facing invoke carries no DEAL call site: a host
        // invoking the callback directly gets an honestly span-less
        // boundary raise (never a fabricated origin).
        String absentOrigins =
            "(java.lang.String) null, -1, -1, (java.lang.String) null, -1, -1";
        body.append("    public ").append(ret).append(" invoke(");
        for (int i = 0; i < f.paramTypes().size(); i++) {
            if (i > 0) body.append(", ");
            body.append(silentJavaLocalType(f.paramTypes().get(i)))
                .append(" p").append(i);
            argNames.add("p" + i);
        }
        String forwarded = String.join(", ", argNames);
        String absentArgs = forwarded.isEmpty() ? absentOrigins
            : forwarded + ", " + absentOrigins;
        body.append(") { ");
        if ("void".equals(ret)) {
            body.append(hostWrapperName(alias, exportName)).append('(')
                .append(absentArgs).append("); }");
        } else {
            body.append("return ").append(hostWrapperName(alias, exportName))
                .append('(').append(absentArgs).append("); }");
        }
        // The DEAL-source-aware dispatch: an indirect call through the
        // value passes its call-site origin, and the wrapper reports
        // both the async operation shape check and the completion check
        // at that site (the LuaJIT function-value span triplet shape).
        body.append("\n    @Override\n    public ").append(ret)
            .append(" invokeAt(java.lang.String file, int line, "
                + "int column");
        for (int i = 0; i < f.paramTypes().size(); i++) {
            body.append(", ");
            body.append(silentJavaLocalType(f.paramTypes().get(i)))
                .append(" p").append(i);
        }
        String originArgs = forwarded.isEmpty() ? "file, line, column"
            : forwarded + ", file, line, column";
        body.append(") { ");
        if ("void".equals(ret)) {
            body.append(hostWrapperName(alias, exportName)).append('(')
                .append(originArgs).append(", file, line, column); }");
        } else {
            body.append("return ").append(hostWrapperName(alias, exportName))
                .append('(').append(originArgs)
                .append(", file, line, column); }");
        }
        body.append("\n};\n");
        emitLine(body.toString());
    }

    /** Emitted name of the load-time-captured {@code <C>_defaults} map
     * field for one declared host class export
     * ({@code $hostDefaults$<alias>$<Class>}). */
    private String hostDefaultsFieldName(String alias, String className) {
        return "$hostDefaults$" + javaName(alias) + "$" + javaName(className);
    }

    private void registerHostClassCheckBranch(Type.Class c) {
        String identity = classCheckDescriptor(c);
        if (!emittedHostClassBranches.add(identity)) return;
        String record = hostRecordJavaRef(c);
        String arr = hostClassArrayWrapperRef(c);
        classCheckBranches.add("if (descriptor.equals("
            + quoteJavaString(identity) + ")) {");
        classCheckBranches.add("    if (v instanceof " + record + " b) return b;");
        classCheckBranches.add("    java.lang.String actualIdentity = $identityOf(v);");
        classCheckBranches.add("    if (actualIdentity != null) throw new DealError(\"E8001\", \"expected instance of "
            + identity + ", got \" + actualIdentity, oFile, oLine, oCol, "
            + quoteJavaString(identity) + ", actualIdentity, null, null);");
        classCheckBranches.add("    throw new DealError(\"E8001\", \"expected class instance\", oFile, oLine, oCol, \"class\", $kindOf(v), null, null);");
        classCheckBranches.add("}");

        classCheckBranches.add("if (descriptor.equals("
            + quoteJavaString("[" + identity + "]") + ")) {");
        classCheckBranches.add("    if (v instanceof " + arr + " a) return a;");
        classCheckBranches.add("    if (v instanceof $DealRt.Table t && t.$array() != null) {");
        classCheckBranches.add("        java.util.ArrayList<java.lang.Object> a = t.$array();");
        classCheckBranches.add("        java.lang.Object[] data = new java.lang.Object[a.size()];");
        classCheckBranches.add("        for (int i = 0; i < a.size(); i++) {");
        classCheckBranches.add("            try { data[i] = $check("
            + quoteJavaString(identity) + ", a.get(i), oFile, oLine, oCol); }");
        classCheckBranches.add("            catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol, "
            + quoteJavaString(identity) + ", $kindOf(a.get(i)), null, null); }");
        classCheckBranches.add("        }");
        classCheckBranches.add("        return new " + arr + "(data);");
        classCheckBranches.add("    }");
        classCheckBranches.add("    throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, descriptor, $kindOf(v), null, null);");
        classCheckBranches.add("}");
        classCheckBranches.add("if (descriptor.equals("
            + quoteJavaString("[?" + identity + "]") + ")) {");
        classCheckBranches.add("    if (v instanceof " + arr + " a) return a;");
        classCheckBranches.add("    if (v instanceof $DealRt.Table t && t.$array() != null) {");
        classCheckBranches.add("        java.util.ArrayList<java.lang.Object> a = t.$array();");
        classCheckBranches.add("        java.lang.Object[] data = new java.lang.Object[a.size()];");
        classCheckBranches.add("        for (int i = 0; i < a.size(); i++) {");
        classCheckBranches.add("            try { data[i] = $check("
            + quoteJavaString("?" + identity) + ", a.get(i), oFile, oLine, oCol); }");
        classCheckBranches.add("            catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol, "
            + quoteJavaString("?" + identity) + ", $kindOf(a.get(i)), null, null); }");
        classCheckBranches.add("        }");
        classCheckBranches.add("        return new " + arr + "(data);");
        classCheckBranches.add("    }");
        classCheckBranches.add("    throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, descriptor, $kindOf(v), null, null);");
        classCheckBranches.add("}");

        hostRefArrayCheckBranches.add("if (descriptor.equals("
            + quoteJavaString("[" + identity + "]") + ")) {");
        hostRefArrayCheckBranches.add("    " + arr + " w = (" + arr + ") a;");
        hostRefArrayCheckBranches.add("    for (int i = 0; i < w.data.length; i++) {");
        hostRefArrayCheckBranches.add("        java.lang.Object e = w.data[i];");
        hostRefArrayCheckBranches.add("        if (e instanceof " + record + ") continue;");
        hostRefArrayCheckBranches.add("        java.lang.String id = $identityOf(e);");
        hostRefArrayCheckBranches.add("        if (id != null) throw new DealError(\"E8001\", \"expected instance of "
            + identity + ", got \" + id, oFile, oLine, oCol);");
        hostRefArrayCheckBranches.add("        throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol);");
        hostRefArrayCheckBranches.add("    }");
        hostRefArrayCheckBranches.add("    return a;");
        hostRefArrayCheckBranches.add("}");
        hostRefArrayCheckBranches.add("if (descriptor.equals("
            + quoteJavaString("[?" + identity + "]") + ")) {");
        hostRefArrayCheckBranches.add("    " + arr + " w = (" + arr + ") a;");
        hostRefArrayCheckBranches.add("    for (int i = 0; i < w.data.length; i++) {");
        hostRefArrayCheckBranches.add("        java.lang.Object e = w.data[i];");
        hostRefArrayCheckBranches.add("        if (e == null || e instanceof " + record + ") continue;");
        hostRefArrayCheckBranches.add("        java.lang.String id = $identityOf(e);");
        hostRefArrayCheckBranches.add("        if (id != null) throw new DealError(\"E8001\", \"expected instance of "
            + identity + ", got \" + id, oFile, oLine, oCol);");
        hostRefArrayCheckBranches.add("        throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol);");
        hostRefArrayCheckBranches.add("    }");
        hostRefArrayCheckBranches.add("    return a;");
        hostRefArrayCheckBranches.add("}");
    }

    private void emitHostLoadMethod(String alias, String raw,
                                    Map<String, Type> exports) {
        String clsName = classNameFor(raw);
        emitLine("// Load-time validation of host module '" + raw
            + "' (declared exports must exist; extras are ignored).");
        // D1: the load helper gains the origin (file/line/column)
        // parameters and propagates them into every raise it performs;
        // the short overload passes the explicit absent sentinel.
        emitLine("static void " + hostLoadMethodName(alias) + "() { "
            + hostLoadMethodName(alias) + "(null, -1, -1); }");
        emitLine("static void " + hostLoadMethodName(alias)
            + "(java.lang.String oFile, int oLine, int oCol) {");
        indent++;
        emitLine("java.lang.Class<?> __h;");
        emitLine("try { __h = java.lang.Class.forName(\"" + clsName + "\"); }"
            + " catch (java.lang.ClassNotFoundException e) {"
            + " throw new DealError(\"E8011\", \"host module '" + raw
            + "' not found (class " + clsName + ")\", oFile, oLine, oCol); }");
        for (Map.Entry<String, Type> ex : exports.entrySet()) {
            if (ex.getValue() instanceof Type.Func f) {
                StringBuilder pcs = new StringBuilder();
                for (int i = 0; i < f.paramTypes().size(); i++) {
                    if (i > 0) pcs.append(", ");
                    pcs.append(hostParamClassLiteral(f.paramTypes().get(i)));
                }
                emitLine(hostMethodFieldName(alias, ex.getKey())
                    + " = __hostMethod(__h, \"" + raw + "\", "
                    + quoteJavaString(ex.getKey()) + ", "
                    + quoteJavaString(typeDescriptor(f)) + ","
                    + " new java.lang.Class[]{ "
                    + pcs + " }, oFile, oLine, oCol);");
            } else if (ex.getValue() instanceof Type.Class c) {
                emitLine(hostDefaultsFieldName(alias, c.name())
                    + " = __hostDefaults(__h, \"" + raw + "\", "
                    + quoteJavaString(c.name()) + ", oFile, oLine, oCol);");
            }
        }
        indent--;
        emitLine("}");
    }

    private void emitHostWrapperMethod(String alias, String raw,
                                       String exportName, Type.Func f) {
        Type ret = f.returnType();
        String javaRet = hostReturnJavaType(ret);
        List<String> argNames = new ArrayList<>();
        for (int i = 0; i < f.paramTypes().size(); i++) {
            argNames.add("__a" + i);
        }
        String paramList = String.join(", ", argNames.stream()
            .map(n -> "java.lang.Object " + n).toList());
        // D1: the wrapper gains the origin (file/line/column) parameters
        // and propagates them unchanged into every raise it performs
        // (the parameter/return boundary re-raises and the async-shape
        // failures alike); the short overload passes the explicit absent
        // sentinel (null, -1, -1). The DEAL call site threads a second
        // triple — the await-site origin the blocking lowering reports
        // the async completion check at (the LuaJIT await-site check);
        // a sync host call passes the call-site triple for both.
        String delegateArgs = argNames.isEmpty() ? "" : String.join(", ", argNames) + ", ";
        String delegateReturn = "void".equals(javaRet) ? "" : "return ";
        emitLine("static " + javaRet + " " + hostWrapperName(alias, exportName)
            + "(" + paramList + ") { " + delegateReturn
            + hostWrapperName(alias, exportName)
            + "(" + delegateArgs + "null, -1, -1, null, -1, -1); }");
        emitLine("static " + javaRet + " " + hostWrapperName(alias, exportName)
            + "(" + (paramList.isEmpty() ? "" : paramList + ", ")
            + "java.lang.String oFile, int oLine, int oCol, "
            + "java.lang.String oAwaitFile, int oAwaitLine, int oAwaitCol) {");
        indent++;
        for (int i = 0; i < f.paramTypes().size(); i++) {
            emitLine(argNames.get(i) + " = __hostParamCheck(" + (i + 1) + ", "
                + quoteJavaString(typeDescriptor(f.paramTypes().get(i)))
                + ", " + argNames.get(i) + ", oFile, oLine, oCol);");
        }
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < argNames.size(); i++) {
            if (i > 0) args.append(", ");
            args.append(argNames.get(i));
        }
        emitLine("java.lang.Object __r = __hostInvoke("
            + hostMethodFieldName(alias, exportName)
            + ", new java.lang.Object[]{ " + args + " }, oFile, oLine, oCol);");
        String fn = raw + "." + exportName;
        if (f.isAsync()) {
            // Shape check at the call site (LuaJIT's E8010 "host async
            // function must return an async operation"), then the blocking
            // join; a failed operation propagates DEAL errors and wraps
            // other causes in E8010.
            emitLine("if (!(__r instanceof java.util.concurrent.CompletableFuture))"
                + " throw new DealError(\"E8010\", \"host async function"
                + " must return an async operation, got \" + __hostKind(__r),"
                + " oFile, oLine, oCol, \"async operation\","
                + " __hostKind(__r));");
            emitLine("java.lang.Object __v;");
            emitLine("try { __v = ((java.util.concurrent.CompletableFuture) __r).join(); }"
                + " catch (java.util.concurrent.CompletionException e) {"
                + " java.lang.Throwable __c = e.getCause();"
                + " if (__c instanceof RuntimeException rr) throw rr;"
                + " if (__c instanceof Error er) throw er;"
                + " throw new DealError(\"E8010\", \"host async function '"
                + fn + "' operation failed: \" + __c, oFile, oLine, oCol); }");
            emitLine(hostReturnStatement("__v", ret, fn, true,
                "oAwaitFile", "oAwaitLine", "oAwaitCol"));
        } else {
            emitLine(hostReturnStatement("__r", ret, fn, false,
                "oFile", "oLine", "oCol"));
        }
        indent--;
        emitLine("}");
    }

    /**
     * The return statement of a host wrapper: {@code null} returns run the
     * check and return nothing (a {@code void} method), value returns cast
     * the checked {@code __hostCheck} result to the boxed JVM mapping
     * (shared array wrappers, the typed {@code $DealRt} function wrapper,
     * and the synthesized host-class record join the primitive casts).
     * {@code completion} selects the error code — E8001 at the await site
     * for async completion values (LuaJIT's await-site check), E8010 for
     * sync returns (host-module-abi D3 case 2).
     */
    private String hostReturnStatement(String valueCode, Type ret, String fn,
                                       boolean completion, String fileVar,
                                       String lineVar, String colVar) {
        String descLiteral = quoteJavaString(typeDescriptor(ret));
        if (ret instanceof Type.Null) {
            return "__hostCheck(\"null\", " + valueCode + ", "
                + quoteJavaString(fn) + ", " + completion + ", " + fileVar
                + ", " + lineVar + ", " + colVar + ");";
        }
        String cast = hostReturnCast(ret);
        String check = "(" + cast + ") __hostCheck("
            + descLiteral + ", " + valueCode + ", "
            + quoteJavaString(fn) + ", " + completion + ", " + fileVar
            + ", " + lineVar + ", " + colVar + ")";
        if (completion || ret instanceof Type.Nullable) {
            return "return " + check + ";";
        }
        // The presence rule (the LuaJIT nresults < 1 shape): a Java null
        // result for a non-nullable declared return is the zero-result
        // shape and raises the pinned "got nothing" return error.
        return "if (" + valueCode + " == null) throw new DealError(\"E8010\","
            + " \"return value 1 type mismatch: expected \" + " + descLiteral
            + " + \", got nothing\", " + fileVar + ", " + lineVar + ", "
            + colVar + ", " + descLiteral + ", \"nothing\"); return "
            + check + ";";
    }

    /** The boxed Java cast of a checked host return value for the
     * declared return type (the DEAL-side JVM mapping). */
    private String hostReturnCast(Type ret) {
        Type inner = ret instanceof Type.Nullable n ? n.inner() : ret;
        if (inner instanceof Type.Array a) {
            return arrayWrapperName(a.element());
        }
        if (inner instanceof Type.Func f) {
            return registerWrapperShape(f);
        }
        if (inner instanceof Type.Class c) {
            return hostRecordJavaRef(c);
        }
        return switch (inner) {
            case Type.Int ignored ->
                int32Mode ? "java.lang.Integer" : "java.lang.Long";
            case Type.Number ignored -> "java.lang.Double";
            case Type.Boolean ignored -> "java.lang.Boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Bytes ignored -> "$DealRt.Bytes";
            case Type.Nullable n -> switch (n.inner()) {
                case Type.Int ignored ->
                    int32Mode ? "java.lang.Integer" : "java.lang.Long";
                case Type.Number ignored -> "java.lang.Double";
                case Type.Boolean ignored -> "java.lang.Boolean";
                case Type.String ignored -> "java.lang.String";
                case Type.Bytes ignored -> "$DealRt.Bytes";
                default -> "java.lang.Object";
            };
            default -> "java.lang.Object";
        };
    }

    private boolean validateHostExports(String raw, Map<String, Type> exports,
                                        Span span) {
        boolean ok = true;
        for (Map.Entry<String, Type> e : exports.entrySet()) {
            String name = e.getKey();
            Type t = e.getValue();
            if (t instanceof Type.Func f) {
                for (Type pt : f.paramTypes()) {
                    if (!hostShapeSupported(pt, false)) {
                        unsupported("host export '" + name + "' of module '"
                            + raw + "' declares unsupported parameter type '"
                            + typeName(pt) + "'", span);
                        ok = false;
                    }
                }
                Type ret = f.returnType();
                if (!hostShapeSupported(ret, false)) {
                    unsupported("host export '" + name + "' of module '"
                        + raw + "' declares unsupported return type '"
                        + typeName(ret) + "'", span);
                    ok = false;
                }
            } else if (t instanceof Type.Class c) {
                if (!isDeclaredHostClass(c)) {
                    unsupported("host export '" + name + "' of module '"
                        + raw + "' is not a declared host class", span);
                    ok = false;
                    continue;
                }
                List<HostModuleDeclarations.HostField> fields =
                    hostFieldsFor(c);
                if (fields == null) {
                    unsupported("host class export '" + name + "' of module '"
                        + raw + "' (its declaration is unavailable)", span);
                    ok = false;
                    continue;
                }
                for (HostModuleDeclarations.HostField hf : fields) {
                    if (!hostShapeSupported(hf.type(), true)) {
                        unsupported("host class '" + name + "' field '"
                            + hf.declaration().name() + "' of type "
                            + typeName(hf.type()), span);
                        ok = false;
                    }
                }
            } else {
                unsupported("host export '" + name + "' of module '" + raw
                    + "' (only function and declared class exports are in "
                    + "the JVM host ABI)", span);
                ok = false;
            }
        }
        return ok;
    }

    private boolean hostShapeSupported(Type t,
            boolean classArraysSupported) {
        if (t instanceof Type.Null) return true;
        if (t instanceof Type.Nullable n) {
            return hostShapeSupported(n.inner(), classArraysSupported);
        }
        if (t instanceof Type.Array a) {
            if (!classArraysSupported) {

                Type elem = a.element();
                Type elemInner = elem instanceof Type.Nullable en
                    ? en.inner() : elem;
                if (elemInner instanceof Type.Class c
                        && !isDeclaredHostClass(c)) return false;
            }
            return hostShapeSupported(a.element(), classArraysSupported);
        }
        if (t instanceof Type.Func f) {
            for (Type pt : f.paramTypes()) {
                if (!hostShapeSupported(pt, classArraysSupported)) {
                    return false;
                }
            }
            return hostShapeSupported(f.returnType(),
                classArraysSupported);
        }
        if (t instanceof Type.Class c) return isDeclaredHostClass(c);
        return switch (t) {
            case Type.Int ignored -> true;
            case Type.Number ignored -> true;
            case Type.Boolean ignored -> true;
            case Type.String ignored -> true;
            case Type.Bytes ignored -> true;
            case Type.Table ignored -> false;
            default -> false;
        };
    }

    private boolean isDeclaredHostClass(Type.Class c) {
        if (!isHostModuleClass(c)) return false;
        String specifier = hostSpecifierOf(c);
        Map<String, Type> exports = hostModules.get(specifier);
        return exports != null && exports.get(c.name()) instanceof Type.Class;
    }

    /** The raw import specifier of a host-class type (the externals
     * projection's raw specifier, verbatim — the manifest key as
     * written). */
    private String hostSpecifierOf(Type.Class c) {
        CanonicalModuleIdentity mi = c.identity().moduleIdentity();
        return mi instanceof CanonicalModuleIdentity.ExternalModule em
            ? em.rawImportSpecifier() : null;
    }

    /** The declared field records of a host class (declaration order),
     * or {@code null} when unavailable. */
    private List<HostModuleDeclarations.HostField> hostFieldsFor(Type.Class c) {
        String specifier = hostSpecifierOf(c);
        if (specifier == null) return null;
        Map<String, List<HostModuleDeclarations.HostField>> classes =
            hostClassDeclarations.get(specifier);
        if (classes == null) {
            // Dotted/slash converted spelling fallback (the retired
            // shapeReferencesHostModule three-way matching precedent).
            classes = hostClassDeclarations.get(specifier.replace('.', '/'));
            if (classes == null) {
                classes = hostClassDeclarations.get(
                    specifier.replace('/', '.'));
            }
        }
        return classes == null ? null : classes.get(c.name());
    }

    /** The declared {@link ClassField} of a host class field, or
     * {@code null} when unavailable. */
    private ClassField hostClassField(Type.Class cls, String fieldName) {
        List<HostModuleDeclarations.HostField> fields = hostFieldsFor(cls);
        if (fields == null) return null;
        for (HostModuleDeclarations.HostField hf : fields) {
            if (hf.declaration().name().equals(fieldName)) {
                return hf.declaration();
            }
        }
        return null;
    }

    /** The import alias of this module that imports the host module
     * declaring {@code c}, or {@code null} when none matches. */
    private String hostAliasForClass(Type.Class c) {
        String specifier = hostSpecifierOf(c);
        for (Map.Entry<String, String> e : hostAliases.entrySet()) {
            String raw = e.getValue();
            if (raw.equals(specifier)
                    || raw.equals(specifier == null ? null
                        : specifier.replace('.', '/'))
                    || raw.equals(specifier == null ? null
                        : specifier.replace('/', '.'))) {
                return e.getKey();
            }
        }
        return null;
    }

    private String hostParamJavaType(Type t) {
        return "java.lang.Object";
    }

    /** Java {@code Class} literal for a host function parameter of the
     * given declared DEAL type (the load-time
     * {@code getDeclaredMethod} signature check): the declared JVM
     * mapping — primitive carriers, the shared array wrappers, the
     * typed {@code $DealRt} function wrapper class, and the synthesized
     * host-class record. */
    private String hostParamClassLiteral(Type t) {
        Type inner = t instanceof Type.Nullable n ? n.inner() : t;
        if (inner instanceof Type.Array a) {
            String wrapper = arrayWrapperName(a.element());
            return wrapper == null ? "java.lang.Object.class"
                : wrapper + ".class";
        }
        if (inner instanceof Type.Func f) {
            String shape = registerWrapperShape(f);
            return shape == null ? "java.lang.Object.class"
                : shape + ".class";
        }
        if (inner instanceof Type.Class c) {
            String record = hostRecordJavaRef(c);
            return record == null ? "java.lang.Object.class"
                : record + ".class";
        }
        return switch (inner) {

            case Type.Int ignored -> t instanceof Type.Nullable
                ? (int32Mode ? "java.lang.Integer.class"
                    : "java.lang.Long.class")
                : (int32Mode ? "int.class" : "long.class");
            case Type.Number ignored -> t instanceof Type.Nullable
                ? "java.lang.Double.class" : "double.class";
            case Type.Boolean ignored -> t instanceof Type.Nullable
                ? "java.lang.Boolean.class" : "boolean.class";
            case Type.String ignored -> "java.lang.String.class";
            case Type.Bytes ignored -> "$DealRt.Bytes.class";
            default -> "java.lang.Object.class";
        };
    }

    /** Java return type of a host wrapper method ({@code null} declares a
     * {@code void} method, mirroring {@link #javaReturnType}); array
     * returns map to the shared array wrapper, function returns to the
     * typed {@code $DealRt} wrapper, class returns to the synthesized
     * record. */
    private String hostReturnJavaType(Type t) {
        if (t instanceof Type.Null) return "void";
        // A nullable return keeps the BOXED reference — the wrapper
        // never unboxes (Java null is the DEAL null; unboxing would
        // raise an NPE before the caller's nullable position ever sees
        // the value).
        if (t instanceof Type.Nullable n) {
            return hostReturnCast(n);
        }
        Type inner = t;
        if (inner instanceof Type.Array a) {
            return arrayWrapperName(a.element());
        }
        if (inner instanceof Type.Func f) {
            return registerWrapperShape(f);
        }
        if (inner instanceof Type.Class c) {
            return hostRecordJavaRef(c);
        }
        return switch (inner) {
            case Type.Int ignored -> int32Mode ? "int" : "long";
            case Type.Number ignored -> "double";
            case Type.Boolean ignored -> "boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Bytes ignored -> "$DealRt.Bytes";
            default -> "java.lang.Object";
        };
    }

    static String hostRecordSimpleName(String specifier,
                                       String className) {
        return "$Host$" + escapedIdentifier(
                specifier.replace('.', '/')) + "$"
            + javaName(className);
    }

    /** The fully qualified shared record reference for one declared
     * host class ({@code $DealRt.$Host$...}). */
    private String hostRecordJavaRef(String specifier, String className) {
        return "$DealRt." + hostRecordSimpleName(specifier, className);
    }

    /** The fully qualified shared record reference for a host-class
     * type, or {@code null} when the identity is not an externals
     * projection. */
    private String hostRecordJavaRef(Type.Class c) {
        String specifier = hostSpecifierOf(c);
        return specifier == null ? null
            : hostRecordJavaRef(specifier, c.name());
    }

    /** The shared per-class array wrapper reference for a host-class
     * element shape ({@code $DealRt.$HostArr$...} extending the shared
     * {@code __RefArray}), or {@code null} when the identity is not an
     * externals projection. */
    private String hostClassArrayWrapperRef(Type.Class c) {
        String specifier = hostSpecifierOf(c);
        if (specifier == null) return null;
        return "$DealRt.$HostArr$" + escapedIdentifier(specifier) + "$"
            + javaName(c.name());
    }

    /** The emitted Java name of the generated class for DEAL class
     * {@code name}. The {@code $C_} prefix is unreachable from
     * {@link #javaName} (every user {@code $} escapes to {@code $d}), so a
     * generated name can never collide with a translated user identifier or
     * with another generated name (javaName is injective and DEAL class
     * names are unique per module). */
    private String classNameForClass(String name) {
        return "$C_" + javaName(name);
    }

    /** The runtime identity text of a local class: the identity index's
     * projection of the module-identity layer's identity for the
     * backend-held module path (v1.2 identity carriage — never a
     * locally derived dotted spelling).  Every producer and consumer in
     * the module uses the backend-held identity; the conformance adapter
     * checks with the filename while codegen runs with {@code Main},
     * and the locality predicate aligns both sides. */
    private String classIdentity(String name) {
        return identityIndex.descriptorTextFor(localClassIdentity(name));
    }

    /**
     * The canonical class identity of a class declared in THIS module:
     * the module-identity layer's classification of the backend-held
     * module path plus the class name (descriptor-identity-propagation
     * D1).  A module without a public identity fails closed.
     */
    private CanonicalClassIdentity localClassIdentity(String name) {
        String mp = modulePath != null && !modulePath.isEmpty()
            ? modulePath : "";
        CanonicalModuleIdentity moduleIdentity = moduleIdentities.apply(mp);
        if (moduleIdentity == null) {
            throw new IllegalStateException(
                "no canonical public module identity for module path '" + mp
                    + "': a class there can never be represented "
                    + "(internal invariant violation)");
        }
        return new CanonicalClassIdentity(moduleIdentity, name);
    }

    /** The intrinsic builtin Error class type (E2's synthesis). */
    private static Type.Class errorClassType() {
        return Types.classType("Error", new CanonicalClassIdentity(
            CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Error"));
    }

    /** A Class type for a class declared in the given wiring path. */
    private Type.Class classTypeFor(String name, String wiringPath) {
        CanonicalModuleIdentity moduleIdentity =
            moduleIdentities.apply(wiringPath == null ? "" : wiringPath);
        if (moduleIdentity == null) {
            throw new IllegalStateException(
                "no canonical public module identity for module path '"
                    + wiringPath + "': a class there can never be "
                    + "represented (internal invariant violation)");
        }
        return Types.classType(name,
            new CanonicalClassIdentity(moduleIdentity, name));
    }

    /**
     * The private wiring module path of an IMPORTED class type: the
     * imported module whose classification equals the carried identity
     * and whose declarations contain the class name, resolved through
     * this module's direct imports first; when they cannot name the
     * declaring module (the shared-scope pre-registration of a shape
     * collected from another module), the compilation-wide
     * class-declaration identity surface supplies it.  Identity text is
     * never reconstructed from this path; the path only keys the
     * backend's private imported-declaration map.
     */
    private String declaringModulePath(Type.Class cls) {
        for (String module : importAliases.values()) {
            Map<String, ClassDeclaration> decls = importedClasses.get(module);
            if (decls == null || !decls.containsKey(cls.name())) {
                continue;
            }
            CanonicalModuleIdentity classified = moduleIdentities.apply(module);
            if (classified != null
                    && cls.identity().moduleIdentity().equals(classified)) {
                return module;
            }
        }

        return sharedClassDeclarations.get(cls.identity());
    }

    public static String typeDescriptor(Type t) {
        if (t == null) return "null";
        return STATIC_DESCRIPTORS.encode(t);
    }

    /**
     * The seam-aligned descriptor for a runtime-check site: identical to
     * {@link #typeDescriptor(Type)} for every non-class type, and the
     * identity-aligned {@link #classCheckDescriptor} spelling for classes
     * (so the call-site descriptor always equals the branch key the
     * generated class appended to the seam — see
     * {@link #classCheckDescriptor}).
     */
    private String runtimeTypeDescriptor(Type t) {
        if (t instanceof Type.Class cls) return classCheckDescriptor(cls);
        return typeDescriptor(t);
    }

    /**
     * The canonical descriptor string the runtime-check seam dispatches
     * on for class {@code cls}: the backend-held canonical identity
     * ({@link #classIdentity}) for a LOCAL class — the exact string the
     * generated class carries — and the canonical projection of the
     * checker-recorded {@code Type.Class} through the compilation's
     * descriptor service for an imported class (the declaring module's
     * emitted seam branches on its own {@code classIdentity}, which
     * equals that projection under the orchestrator). The locality split
     * exists because the direct single-module adapter checks with the
     * source filename while codegen runs with the backend-held module
     * path — the same alignment {@link #isLocalClassType} already
     * encodes.
     */
    private String classCheckDescriptor(Type.Class cls) {
        if (isLocalClassType(cls)) return classIdentity(cls.name());
        return identityIndex.descriptorTextFor(cls.identity());
    }

    private String elementCheckDescriptor(Type element) {
        return switch (element) {
            case Type.Int ignored -> "int";
            case Type.Number ignored -> "number";
            case Type.String ignored -> "string";
            case Type.Boolean ignored -> "boolean";
            case Type.Bytes ignored -> "bytes";
            case Type.Nullable ne -> switch (ne.inner()) {
                case Type.Int ignored -> "?int";
                case Type.Number ignored -> "?number";
                case Type.String ignored -> "?string";
                case Type.Boolean ignored -> "?boolean";
                case Type.Class c -> "?" + classCheckDescriptor(c);
                case Type.Bytes ignored -> "?bytes";
                default -> null;
            };
            case Type.Class c -> classCheckDescriptor(c);
            default -> null;
        };
    }

    private String importedClassModuleRef(Type.Class cls, Span span) {
        String module = declaringModulePath(cls);
        Map<String, ClassDeclaration> decls = module == null
            ? null : importedClasses.get(module);
        if (module == null || !importAliases.containsValue(module)
                || decls == null || !decls.containsKey(cls.name())) {
            unsupported("values of imported class type '" + cls.name()
                + "' (the declaring module is not an imported compiled "
                + "project module of this module, or its class "
                + "declaration is unavailable)", span);
            return null;
        }
        return classNameFor(module);
    }

    /** Synthetic-anchor variant of
     * {@link #importedClassModuleRef(Type.Class, Span)} (D6): same note
     * rule as {@link #jsonClassRefSynthetic(Type.Class)}. */
    private String importedClassModuleRefSynthetic(Type.Class cls) {
        String module = declaringModulePath(cls);
        Map<String, ClassDeclaration> decls = module == null
            ? null : importedClasses.get(module);
        if (module == null || !importAliases.containsValue(module)
                || decls == null || !decls.containsKey(cls.name())) {
            unsupportedSynthetic("values of imported class type '" + cls.name()
                + "' (the declaring module is not an imported compiled "
                + "project module of this module, or its class "
                + "declaration is unavailable)",
                "missing anchor: class declaration span for class '"
                    + cls.name() + "'");
            return null;
        }
        return classNameFor(module);
    }

    /** The emitted Java name of the per-class array wrapper for DEAL
     * class {@code name} ({@code $Array$<Name>}, extending the emitted
     * {@code __RefArray} so {@code instanceof} proves the element type).
     * The {@code $Array$} prefix is unreachable from {@link #javaName}
     * output for the same reason as {@code $C_} (every user {@code $}
     * escapes to {@code $d}). */

    private String importedArrayWrapperName(Type.Class c) {
        String module = declaringModulePath(c);
        Map<String, ClassDeclaration> decls = module == null
            ? null : importedClasses.get(module);
        if (module == null || !importAliases.containsValue(module)
                || decls == null || !decls.containsKey(c.name())) {
            return null;
        }
        return classNameFor(module) + "." + classArrayWrapperName(c.name());
    }

    private String classArrayHelper(Type.Class cls, String kind, Span span) {
        if (isLocalClassType(cls)) {
            if (localClassJavaType(cls, span) == null) return null;
            return switch (kind) {
                case "read" -> classArrayReadName(cls.name());
                case "readOrNull" -> classOrNullArrayReadName(cls.name());
                case "write" -> classArrayWriteName(cls.name());
                default -> classOrNullArrayWriteName(cls.name());
            };
        }
        String moduleRef = importedClassModuleRef(cls, span);
        if (moduleRef == null) return null;
        return moduleRef + "." + switch (kind) {
            case "read" -> classArrayReadName(cls.name());
            case "readOrNull" -> classOrNullArrayReadName(cls.name());
            case "write" -> classArrayWriteName(cls.name());
            default -> classOrNullArrayWriteName(cls.name());
        };
    }

    private String classArrayWrapperName(String name) {
        return "$Array$" + javaName(name);
    }

    /** The emitted Java name of the per-class NULLABLE-element array
     * wrapper for DEAL class {@code name}
     * ({@code $ArrayOrNull$<Name>}, extending {@code __RefArray} like
     * {@link #classArrayWrapperName}). Distinct from the plain
     * {@code C[]} wrapper so a null-containing {@code (C | null)[]}
     * can never pass a {@code C[]}-typed boundary gate (LuaJIT's
     * check_array rejects its null element with E8003; the JVM raises
     * the documented storage-analog E8001 wrong-wrapper failure). */
    private String classOrNullArrayWrapperName(String name) {
        return "$ArrayOrNull$" + javaName(name);
    }

    /** Per-class array read helper (C[] reads): negative index → E8002,
     * past the end → E8001 "expected instance of &lt;identity&gt;, got
     * null" (LuaJIT reads nil there and the read site's typed class
     * boundary fails), otherwise the nominal-checked element. */
    private String classArrayReadName(String name) {
        return "$classArrayRead$" + javaName(name);
    }

    /** Per-class nullable-array read helper ((C | null)[] reads): like
     * {@link #classArrayReadName} but past the end yields the DEAL null
     * (a valid {@code C | null} element — LuaJIT's nil, no boundary
     * failure at the read). */
    private String classOrNullArrayReadName(String name) {
        return "$classOrNullArrayRead$" + javaName(name);
    }

    /** Per-class array write helper (C[] writes): index bounds → E8002,
     * a non-{@code C} value → E8001 (LuaJIT's check_type class branch). */
    private String classArrayWriteName(String name) {
        return "$classArrayWrite$" + javaName(name);
    }

    /** Per-class nullable-array write helper ((C | null)[] writes): null
     * passes (check_nullable permits it), a non-{@code C} non-null value
     * → E8001. */
    private String classOrNullArrayWriteName(String name) {
        return "$classOrNullArrayWrite$" + javaName(name);
    }

    private boolean isLocalClassType(Type.Class cls) {
        // v1.2 identity carriage: locality is module-identity equality
        // against the backend-held module path or source path (the
        // single-module harnesses type with the source filename while
        // codegen runs with the module path — both classify locally).
        CanonicalModuleIdentity carried = cls.identity().moduleIdentity();
        if (carried.equals(CanonicalModuleIdentity.BuiltinModule.INSTANCE)) {
            return true; // the intrinsic builtin Error class
        }
        boolean local = false;
        String mp = modulePath != null && !modulePath.isEmpty()
            ? modulePath : "";
        CanonicalModuleIdentity mine = moduleIdentities.apply(mp);
        if (carried.equals(mine)) {
            local = true;
        }
        String sp = sourcePath != null && !sourcePath.isEmpty()
            ? sourcePath : "";
        if (!local && !sp.isEmpty() && !sp.equals(mp)) {
            CanonicalModuleIdentity src = moduleIdentities.apply(sp);
            if (carried.equals(src)) {
                local = true;
            }
        }
        if (!local) {
            return false;
        }
        // Two files in one directory share the module identity (the
        // relative components exclude the file stem): an import alias
        // whose imported declarations contain this class name AND whose
        // module classification equals the carried identity claims the
        // class as IMPORTED — exactly the declaring-module routing the
        // imported-class seams perform. A companion in a different
        // directory carries a distinct identity and never claims a
        // local class.
        for (String module : importAliases.values()) {
            Map<String, ClassDeclaration> decls = importedClasses.get(module);
            if (decls == null || !decls.containsKey(cls.name())) {
                continue;
            }
            CanonicalModuleIdentity classified = moduleIdentities.apply(module);
            if (classified != null && carried.equals(classified)) {
                return false;
            }
        }
        return true;
    }

    private void emitClass(ClassDeclaration cd) {
        boolean jsonable = cd.isJsonable();
        String gen = classNameForClass(cd.name());
        String identity = classIdentity(cd.name());
        // A default expression reading a module field declared AFTER the
        // class is E6000: LuaJIT evaluates the defaults table at the class
        // declaration (load time), where the later local does not exist yet
        // — the read binds to the global nil and fails with E8001 — while a
        // construction-time inline default would silently read the
        // initialized static field. Defaults reading already-declared
        // fields stay allowed (the inline per-construction evaluation the
        // spec's §Construction requires).
        for (ClassField cf : cd.fields()) {
            if (cf.defaultExpr().isPresent()) {
                String undeclared = undeclaredUseIn(cf.defaultExpr().get());
                if (undeclared != null) {
                    unsupported("class field default of '" + cd.name() + "."
                        + cf.name() + "' reading the module field '"
                        + undeclared + "' declared after the class "
                        + "(LuaJIT evaluates the defaults table at the class "
                        + "declaration and fails at load reading the global "
                        + "nil; Java would silently read the initialized "
                        + "static field)", cf.span());
                    return;
                }
            }
        }
        List<String> fieldTypes = new ArrayList<>();
        List<String> fieldNames = new ArrayList<>();
        List<Boolean> fieldOptionalFlags = new ArrayList<>();
        List<Type> fieldResolved = new ArrayList<>();
        for (ClassField cf : cd.fields()) {
            Type fieldType = resolveTypeNode(cf.type());
            if (fieldType == Type.Error.INSTANCE) return;
            if (jsonable) {

                if (!isJvmJsonableFieldType(fieldType)) {
                    unsupported("@jsonable class fields of type "
                        + typeName(fieldType) + " (the JVM slice supports "
                        + "null, boolean, int, number, string, table, "
                        + "array, class, and nullable fields)", cf.span());
                    return;
                }
                if (fieldType instanceof Type.Table) {
                    if (cf.optional()) {

                        unsupported("optional table fields of @jsonable class '"
                            + cd.name() + "' (the read of '" + cf.name()
                            + "' yields `table | null`, which stays out of "
                            + "the slice's typed positions)", cf.span());
                        return;
                    }

                    fieldTypes.add("$DealRt.Table");
                    fieldNames.add(javaName(cf.name()));
                    fieldResolved.add(fieldType);
                    fieldOptionalFlags.add(false);
                    continue;
                }
                if (cf.optional()) {
                    Type inner = fieldType instanceof Type.Nullable nn
                        ? nn.inner() : fieldType;
                    if (nullableJavaType(inner, cf.span()) == null) return;
                    fieldTypes.add("java.lang.Object");
                    fieldNames.add(javaName(cf.name()));
                    fieldResolved.add(fieldType);
                    fieldOptionalFlags.add(false);
                    continue;
                }
            } else if (cf.optional()) {

                Type inner = fieldType;
                if (fieldType instanceof Type.Nullable nn) {
                    inner = nn.inner();
                }
                String javaType = switch (inner) {
                    case Type.Int ignored ->
                        int32Mode ? "java.lang.Integer" : "java.lang.Long";
                    case Type.Number ignored -> "java.lang.Double";
                    case Type.Boolean ignored -> "java.lang.Boolean";
                    case Type.String ignored -> "java.lang.String";
                    case Type.Bytes ignored -> javaLocalType(inner, cf.span());
                    default -> javaLocalType(inner, cf.span());
                };
                if (javaType == null) return;
                fieldTypes.add(javaType);
                fieldNames.add(javaName(cf.name()));
                fieldOptionalFlags.add(true);
                fieldResolved.add(fieldType);
                continue;
            } else if (cf.nullable()) {

                if (!(fieldType instanceof Type.Nullable nn)) {
                    unsupported("nullable class fields of type "
                        + typeName(fieldType), cf.span());
                    return;
                }
                boolean innerOk = nn.inner() instanceof Type.Int
                    || nn.inner() instanceof Type.Number
                    || nn.inner() instanceof Type.Boolean
                    || nn.inner() instanceof Type.String
                    || nn.inner() instanceof Type.Bytes
                    || (nn.inner() instanceof Type.Class cls
                        && isLocalClassType(cls))

                    || (nn.inner() instanceof Type.Func f
                        && Types.containsBytes(f));
                if (!innerOk) {
                    unsupported("class fields of type " + typeName(fieldType)
                        + " (only primitive, bytes, local class, and "
                        + "bytes-bearing function nullable fields are "
                        + "supported)", cf.span());
                    return;
                }
            } else if (!(fieldType instanceof Type.Int)
                    && !(fieldType instanceof Type.Number)
                    && !(fieldType instanceof Type.Boolean)
                    && !(fieldType instanceof Type.String)
                    && !(fieldType instanceof Type.Bytes)
                    && !(fieldType instanceof Type.Table)
                    && !(fieldType instanceof Type.Array)
                    && !(fieldType instanceof Type.Class cls
                        && isLocalClassType(cls)
                        && !isBuiltinErrorType(cls))

                    && !(fieldType instanceof Type.Func f
                        && Types.containsBytes(f))) {
                unsupported("class fields of type " + typeName(fieldType)
                    + " (only primitive fields, nullable primitive/"
                    + "class fields, and local bytes/array/table/class "
                    + "and bytes-bearing function fields are "
                    + "supported)", cf.span());
                return;
            }
            String javaType = javaLocalType(fieldType, cf.span());
            if (javaType == null) return;
            fieldTypes.add(javaType);
            fieldNames.add(javaName(cf.name()));
            fieldOptionalFlags.add(false);
            fieldResolved.add(fieldType);
        }

        emitLine("// DEAL class " + cd.name() + " — identity " + identity);
        emitLine("static final class " + gen + " extends $Base {");
        indent++;
        for (int i = 0; i < fieldNames.size(); i++) {
            emitLine(fieldTypes.get(i) + " " + fieldNames.get(i) + ";");
            if (fieldOptionalFlags.get(i)) {

                emitLine("boolean " + fieldNames.get(i) + "$present;");
            }
        }
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < fieldNames.size(); i++) {
            if (i > 0) params.append(", ");
            params.append(fieldTypes.get(i)).append(' ')
                .append(fieldNames.get(i));
            if (fieldOptionalFlags.get(i)) {
                params.append(", boolean ").append(fieldNames.get(i))
                    .append("$present");
            }
        }
        emitLine(gen + "(" + params + ") {");
        indent++;
        emitLine("super(" + quoteJavaString(identity) + ");");
        for (int i = 0; i < fieldNames.size(); i++) {
            emitLine("this." + fieldNames.get(i) + " = " + fieldNames.get(i)
                + ";");
            if (fieldOptionalFlags.get(i)) {
                emitLine("this." + fieldNames.get(i) + "$present = "
                    + fieldNames.get(i) + "$present;");
            }
        }
        indent--;
        emitLine("}");
        if (jsonable) {
            emitJsonableFieldDescriptor(cd, fieldResolved);
            emitJsonableToJsonValue(cd, gen, fieldResolved);
            emitJsonableFromJsonValue(cd, gen, fieldTypes, fieldResolved);
        }
        indent--;
        emitLine("}");

        for (int i = 0; i < fieldNames.size(); i++) {
            if (fieldOptionalFlags.get(i)) {
                emitLine("static <V> V __optSet$" + gen + "$"
                    + fieldNames.get(i) + "(" + gen + " obj, V v) {");
                indent++;
                emitLine("obj." + fieldNames.get(i) + " = ("
                    + fieldTypes.get(i) + ") v;");
                emitLine("obj." + fieldNames.get(i) + "$present = true;");
                emitLine("return v;");
                indent--;
                emitLine("}");
            }
        }

        classCheckBranches.add("if (descriptor.equals("
            + quoteJavaString(identity) + ")) {");
        classCheckBranches.add("    if (v instanceof " + gen + " b) return b;");
        classCheckBranches.add("    java.lang.String actualIdentity = $identityOf(v);");
        classCheckBranches.add("    if (actualIdentity != null) throw new DealError(\"E8001\", \"expected instance of "
            + identity + ", got \" + actualIdentity, oFile, oLine, oCol, "
            + quoteJavaString(identity) + ", actualIdentity, null, null);");
        classCheckBranches.add("    throw new DealError(\"E8001\", \"expected class instance\", oFile, oLine, oCol, \"class\", $kindOf(v), null, null);");
        classCheckBranches.add("}");

        emitLine("static final class " + classArrayWrapperName(cd.name())
            + " extends $DealRt.__RefArray {");
        indent++;
        emitLine(classArrayWrapperName(cd.name())
            + "(java.lang.Object[] data) { super(data); }");
        indent--;
        emitLine("}");
        emitLine("static final class " + classOrNullArrayWrapperName(cd.name())
            + " extends $DealRt.__RefArray {");
        indent++;
        emitLine(classOrNullArrayWrapperName(cd.name())
            + "(java.lang.Object[] data) { super(data); }");
        indent--;
        emitLine("}");

        classCheckBranches.add("if (descriptor.equals("
            + quoteJavaString("[" + identity + "]") + ")) {");
        classCheckBranches.add("    if (v instanceof " + classArrayWrapperName(cd.name())
            + " a) return a;");

        classCheckBranches.add("    if (v instanceof $DealRt.Table t && t.$array() != null) {");
        classCheckBranches.add("        java.util.ArrayList<java.lang.Object> a = t.$array();");
        classCheckBranches.add("        java.lang.Object[] data = new java.lang.Object[a.size()];");
        classCheckBranches.add("        for (int i = 0; i < a.size(); i++) {");
        classCheckBranches.add("            try { data[i] = $check("
            + quoteJavaString(identity) + ", a.get(i), oFile, oLine, oCol); }");
        classCheckBranches.add("            catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol, "
            + quoteJavaString(identity) + ", $kindOf(a.get(i)), null, null); }");
        classCheckBranches.add("        }");
        classCheckBranches.add("        return new " + classArrayWrapperName(cd.name())
            + "(data);");
        classCheckBranches.add("    }");
        classCheckBranches.add("    throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, descriptor, $kindOf(v), null, null);");
        classCheckBranches.add("}");
        classCheckBranches.add("if (descriptor.equals("
            + quoteJavaString("[?" + identity + "]") + ")) {");
        classCheckBranches.add("    if (v instanceof " + classOrNullArrayWrapperName(cd.name())
            + " a) return a;");
        classCheckBranches.add("    if (v instanceof " + classArrayWrapperName(cd.name())
            + " a) return new " + classOrNullArrayWrapperName(cd.name())
            + "(a.data);");
        classCheckBranches.add("    if (v instanceof $DealRt.Table t && t.$array() != null) {");
        classCheckBranches.add("        java.util.ArrayList<java.lang.Object> a = t.$array();");
        classCheckBranches.add("        java.lang.Object[] data = new java.lang.Object[a.size()];");
        classCheckBranches.add("        for (int i = 0; i < a.size(); i++) {");
        classCheckBranches.add("            try { data[i] = $check("
            + quoteJavaString("?" + identity) + ", a.get(i), oFile, oLine, oCol); }");
        classCheckBranches.add("            catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol, "
            + quoteJavaString("?" + identity) + ", $kindOf(a.get(i)), null, null); }");
        classCheckBranches.add("        }");
        classCheckBranches.add("        return new " + classOrNullArrayWrapperName(cd.name())
            + "(data);");
        classCheckBranches.add("    }");
        classCheckBranches.add("    throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, descriptor, $kindOf(v), null, null);");
        classCheckBranches.add("}");
        emitLine("static " + gen + " " + classArrayReadName(cd.name())
            + "($DealRt.__RefArray a, long i) { return " + classArrayReadName(cd.name()) + "(a, i, null, -1, -1); }");
        emitLine("static " + gen + " " + classArrayReadName(cd.name())
            + "($DealRt.__RefArray a, long i, java.lang.String oFile, int oLine, int oCol) {");
        indent++;
        emitLine("if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol);");
        emitLine("if (i >= (long) a.data.length) throw new DealError(\"E8001\", "
            + "\"expected instance of " + identity + ", got null\", oFile, oLine, oCol);");
        emitLine("java.lang.Object v = a.data[(int) i];");
        emitLine("if (v instanceof " + gen + " c) return c;");
        emitLine("throw new DealError(\"E8001\", \"expected instance of "
            + identity + ", got \" + $describe(v), oFile, oLine, oCol);");
        indent--;
        emitLine("}");
        emitLine("static " + gen + " " + classOrNullArrayReadName(cd.name())
            + "($DealRt.__RefArray a, long i) { return " + classOrNullArrayReadName(cd.name()) + "(a, i, null, -1, -1); }");
        emitLine("static " + gen + " " + classOrNullArrayReadName(cd.name())
            + "($DealRt.__RefArray a, long i, java.lang.String oFile, int oLine, int oCol) {");
        indent++;
        emitLine("if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol);");
        emitLine("if (i >= (long) a.data.length) return null;");
        emitLine("java.lang.Object v = a.data[(int) i];");
        emitLine("if (v == null) return null;");
        emitLine("if (v instanceof " + gen + " c) return c;");
        emitLine("throw new DealError(\"E8001\", \"expected instance of "
            + identity + ", got \" + $describe(v), oFile, oLine, oCol);");
        indent--;
        emitLine("}");
        emitLine("static " + gen + " " + classArrayWriteName(cd.name())
            + "($DealRt.__RefArray a, long i, java.lang.Object v) { return " + classArrayWriteName(cd.name()) + "(a, i, v, null, -1, -1); }");
        emitLine("static " + gen + " " + classArrayWriteName(cd.name())
            + "($DealRt.__RefArray a, long i, java.lang.Object v, java.lang.String oFile, int oLine, int oCol) {");
        indent++;
        emitLine("if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\", oFile, oLine, oCol);");
        emitLine("if (!(v instanceof " + gen + " c)) throw new DealError(\"E8001\", \"expected instance of " + identity + ", got \" + $describe(v), oFile, oLine, oCol);");
        emitLine("if (i == (long) a.data.length) { java.lang.Object[] nd = new java.lang.Object[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = c; a.data = nd; } else { a.data[(int) i] = c; }");
        emitLine("return c;");
        indent--;
        emitLine("}");
        emitLine("static " + gen + " " + classOrNullArrayWriteName(cd.name())
            + "($DealRt.__RefArray a, long i, java.lang.Object v) { return " + classOrNullArrayWriteName(cd.name()) + "(a, i, v, null, -1, -1); }");
        emitLine("static " + gen + " " + classOrNullArrayWriteName(cd.name())
            + "($DealRt.__RefArray a, long i, java.lang.Object v, java.lang.String oFile, int oLine, int oCol) {");
        indent++;
        emitLine("if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\", oFile, oLine, oCol);");
        emitLine("if (v != null && !(v instanceof " + gen + " c)) throw new DealError(\"E8001\", \"expected instance of " + identity + ", got \" + $describe(v), oFile, oLine, oCol);");
        emitLine("if (v instanceof " + gen + " c) {");
        indent++;
        emitLine("if (i == (long) a.data.length) { java.lang.Object[] nd = new java.lang.Object[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = c; a.data = nd; } else { a.data[(int) i] = c; }");
        emitLine("return c;");
        indent--;
        emitLine("}");
        emitLine("if (i == (long) a.data.length) { java.lang.Object[] nd = new java.lang.Object[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = null; a.data = nd; } else { a.data[(int) i] = null; }");
        emitLine("return null;");
        indent--;
        emitLine("}");
        if (jsonable) {
            emitJsonablePublicHelpers(cd, gen);
        }

        CompilerClassDefaultPlan plan = publishedPlanFor(cd);
        if (plan != null) {
            emitDefaultPlanRealization(cd, plan);
        }
    }

    // =========================================================================
    // @jsonable class helpers (JSON serialization slice)
    // =========================================================================

    private boolean isJvmJsonableFieldType(Type t) {
        return switch (t) {
            case Type.Null ignored -> true;
            case Type.Boolean ignored -> true;
            case Type.Int ignored -> true;
            case Type.Number ignored -> true;
            case Type.String ignored -> true;
            case Type.Table ignored -> true;
            case Type.Class ignored -> true;
            case Type.Nullable n -> isJvmJsonableFieldType(n.inner());
            case Type.Array a -> isJvmJsonableFieldType(a.element());
            case Type.Bytes ignored -> false;
            default -> false; // function
        };
    }

    /** The jtype descriptor string of a resolved jsonable field type
     * (the same vocabulary as the Lua backend's {@code C_fields}
     * descriptor tables — {@code jsonable-v1.1}). */
    private String jsonFieldJType(Type t) {
        return switch (t) {
            case Type.Null ignored -> "null";
            case Type.Boolean ignored -> "boolean";
            case Type.Int ignored -> "int";
            case Type.Number ignored -> "number";
            case Type.String ignored -> "string";
            case Type.Nullable n -> jsonFieldJType(n.inner());
            case Type.Array ignored -> "array";
            case Type.Table ignored -> "table";
            case Type.Class ignored -> "class";
            case Type.Bytes ignored -> "unknown";
            default -> "unknown";
        };
    }

    /** The emitted generated-class reference of a resolved class type
     * ({@code $C_<C>} for a local class, {@code Lib.$C_<C>} for an
     * imported one) — the same reference {@link #javaLocalType} emits. */
    private String jsonClassRef(Type.Class c, Span span) {
        if (isLocalClassType(c)) {
            if (!moduleClasses.containsKey(c.name())) {
                unsupported("values of class type '" + c.name()
                    + "' (only local module-level classes are supported)",
                    span);
                return null;
            }
            return classNameForClass(c.name());
        }
        String importedModule = importedClassModuleRef(c, span);
        if (importedModule == null) return null;
        return importedModule + "." + classNameForClass(c.name());
    }

    /** Synthetic-anchor variant of
     * {@link #jsonClassRef(Type.Class, Span)} (D6): the jsonable
     * toJson/fromJson/boxed-type conversion sites hold only the converted
     * class, no source span, so the E6000 rejection records through the
     * explicit synthetic factory with a note naming the class. */
    private String jsonClassRefSynthetic(Type.Class c) {
        if (isLocalClassType(c)) {
            if (!moduleClasses.containsKey(c.name())) {
                unsupportedSynthetic("values of class type '" + c.name()
                    + "' (only local module-level classes are supported)",
                    "missing anchor: class declaration span for class '"
                        + c.name() + "'");
                return null;
            }
            return classNameForClass(c.name());
        }
        String importedModule = importedClassModuleRefSynthetic(c);
        if (importedModule == null) return null;
        return importedModule + "." + classNameForClass(c.name());
    }

    /** The Missing-sentinel reference for a class whose generated helpers
     * this module emits — always this module's {@code $MISSING}
     * (imported-class construction of optional fields stays E6000, so no
     * reachable site needs the declaring module's sentinel spelled
     * cross-module). */
    private String jsonableMissingRef(ClassDeclaration cd) {
        return "$MISSING";
    }

    private ClassDeclaration classDeclFor(Type.Class cls) {
        if (isLocalClassType(cls)) {
            return moduleClasses.get(cls.name());
        }
        String module = declaringModulePath(cls);
        Map<String, ClassDeclaration> decls = module == null
            ? null : importedClasses.get(module);
        return decls == null ? null : decls.get(cls.name());
    }

    /** The declared field of a class type — local module-level classes
     * plus imported compiled-module classes — or {@code null} when the
     * class or field is unknown (checker-gated unreachable). */
    private ClassField backendClassField(Type.Class cls, String fieldName) {
        ClassDeclaration cd;
        if (isLocalClassType(cls)) {
            cd = moduleClasses.get(cls.name());
        } else {
            String module = declaringModulePath(cls);
            Map<String, ClassDeclaration> decls = module == null
                ? null : importedClasses.get(module);
            cd = decls == null ? null : decls.get(cls.name());
        }
        if (cd == null) return null;
        for (ClassField cf : cd.fields()) {
            if (cf.name().equals(fieldName)) return cf;
        }
        return null;
    }

    /** Emits the per-class {@code $jsonFields} descriptor table
     * ({name, jtype, optional, nullable} rows) inside the generated nested
     * class. The descriptors drive {@code $fromJsonValue}'s extra-key
     * validation (a key is accepted only when a descriptor names it). */
    private void emitJsonableFieldDescriptor(ClassDeclaration cd,
                                             List<Type> types) {
        emitLine("// @jsonable field descriptors: {name, jtype, optional, nullable}.");
        emitLine("static final java.lang.String[][] $jsonFields = new java.lang.String[][] {");
        indent++;
        for (int i = 0; i < types.size(); i++) {
            ClassField cf = cd.fields().get(i);
            Type t = types.get(i);
            String comma = i < types.size() - 1 ? "," : "";
            emitLine("new java.lang.String[]{" + quoteJavaString(cf.name())
                + ", " + quoteJavaString(jsonFieldJType(t))
                + ", " + (cf.optional() ? "\"true\"" : "\"false\"")
                + ", " + (cf.nullable() ? "\"true\"" : "\"false\"")
                + "}" + comma);
        }
        indent--;
        emitLine("};");
    }

    /** Emits the per-class {@code $toJsonValue} field serializer inside
     * the generated nested class: a LinkedHashMap of declared-field JSON
     * values in declaration order (optional fields are out of slice, so
     * nothing is ever omitted), preserving the DEAL null for nullable
     * fields. */
    private void emitJsonableToJsonValue(ClassDeclaration cd, String gen,
                                         List<Type> types) {
        emitLine("// @jsonable toJson field serialization (declared-field order).");
        emitLine("// The caller's toJson call-expression origin (D3) threads");
        emitLine("// unchanged through the walk, so a rejection at any depth");
        emitLine("// reports the call-site span.");
        emitLine("static java.util.LinkedHashMap<java.lang.String, java.lang.Object> $toJsonValue("
            + gen + " v, java.lang.String oFile, int oLine, int oCol) {");
        indent++;
        // Fresh conversion-level suffixes past the field-index namespace
        // (arr{}/e{} names of the field serializers).
        jsonLocalCounter = types.size();
        emitLine("java.util.LinkedHashMap<java.lang.String, java.lang.Object> out = new java.util.LinkedHashMap<>();");
        for (int i = 0; i < types.size(); i++) {
            ClassField cf = cd.fields().get(i);
            emitToJsonField(cf, types.get(i), "v." + javaName(cf.name()), i);
        }
        emitLine("return out;");
        indent--;
        emitLine("}");
    }

    /** Emits the serialization lines of one declared field. An optional
     * field's {@code valueCode} is its Object storage reference: the
     * Missing sentinel skips the key entirely (absent state), the DEAL
     * null serializes as JSON null (present-null state), and any other
     * value serializes per the inner type. */
    private void emitToJsonField(ClassField cf, Type t, String valueCode,
                                 int idx) {
        String name = quoteJavaString(cf.name());
        boolean optional = cf.optional();
        if (optional) {
            emitLine("if (" + valueCode + " != $MISSING) {");
            indent++;
        }
        switch (t) {
            case Type.Int ignored ->
                emitLine(optional
                    ? "out.put(" + name + ", " + valueCode + ");"
                    : "out.put(" + name + ", "
                        + (int32Mode ? "java.lang.Integer" : "java.lang.Long")
                        + ".valueOf(" + valueCode + "));");
            case Type.Number ignored ->
                emitLine(optional
                    ? "out.put(" + name + ", " + valueCode + ");"
                    : "out.put(" + name + ", java.lang.Double.valueOf(" + valueCode + "));");
            case Type.Boolean ignored ->
                emitLine(optional
                    ? "out.put(" + name + ", " + valueCode + ");"
                    : "out.put(" + name + ", java.lang.Boolean.valueOf(" + valueCode + "));");
            case Type.String ignored ->
                emitLine("out.put(" + name + ", " + valueCode + ");");
            case Type.Null ignored ->
                emitLine("out.put(" + name + ", null);");
            case Type.Table ignored ->
                emitLine("out.put(" + name + ", __jsonShape(" + valueCode
                    + ", oFile, oLine, oCol));");
            case Type.Class c -> {
                String ref = jsonClassRef(c, cf.span());
                if (optional) {
                    emitLine("out.put(" + name + ", " + valueCode
                        + " == null ? null : " + ref + ".$toJsonValue(("
                        + ref + ") " + valueCode
                        + ", oFile, oLine, oCol));");
                } else {
                    emitLine("out.put(" + name + ", " + ref + ".$toJsonValue("
                        + valueCode + ", oFile, oLine, oCol));");
                }
            }
            case Type.Nullable nn -> {
                if (nn.inner() instanceof Type.Array a) {
                    emitToJsonArrayField(cf.name(),
                        optional ? castJsonValueCode(valueCode, a) : valueCode,
                        a.element(), idx, true);
                } else if (nn.inner() instanceof Type.Class c) {
                    String ref = jsonClassRef(c, cf.span());
                    emitLine("out.put(" + name + ", " + valueCode
                        + " == null ? null : " + ref + ".$toJsonValue(("
                        + ref + ") " + valueCode
                        + ", oFile, oLine, oCol));");
                } else {
                    // Boxed primitive / string / table reference: put
                    // directly (a table value encodes through
                    // __jsonAppend's $DealRt.Table branch).
                    emitLine("out.put(" + name + ", " + valueCode + ");");
                }
            }
            case Type.Array a ->
                emitToJsonArrayField(cf.name(),
                    optional ? castJsonValueCode(valueCode, a) : valueCode,
                    a.element(), idx, false);
            // bytes is not jsonable (the E4007 checker rule is a later
            // slice); mirror the default's unsupported-value handling.
            case Type.Bytes ignored ->
                emitLine("out.put(" + name + ", null);");
            default ->
                emitLine("out.put(" + name + ", null);");
        }
        if (optional) {
            indent--;
            emitLine("}");
        }
    }

    /** The array-field value code for an OPTIONAL field: the Object
     * storage slot cast to the emitted array wrapper reference (the
     * present value is never the DEAL null for the non-nullable array
     * form; the nullable-outer form null-checks before iterating). */
    private String castJsonValueCode(String valueCode, Type.Array a) {
        return "((" + arrayWrapperName(a.element()) + ") " + valueCode + ")";
    }

    /** Emits the array-field serialization lines: converts the emitted
     * wrapper's storage array into a JSON List of boxed/nested values.
     * {@code nullableOuter} handles a {@code T[] | null} field (the DEAL
     * null serializes as JSON null). */
    private void emitToJsonArrayField(String fieldName, String valueCode,
                                      Type elem, int idx,
                                      boolean nullableOuter) {
        String arrVar = "arr" + idx;
        String eVar = "e" + idx;
        String iterType = jsonArrayIterType(elem);
        emitLine("java.util.ArrayList<java.lang.Object> " + arrVar
            + (nullableOuter ? " = null;" : " = new java.util.ArrayList<>();"));
        if (nullableOuter) {
            emitLine("if (" + valueCode + " != null) {");
            indent++;
            emitLine(arrVar + " = new java.util.ArrayList<>();");
        }
        emitLine("for (" + iterType + " " + eVar + " : " + valueCode
            + ".data) {");
        indent++;
        emitToJsonArrayElement(elem, arrVar, eVar, idx);
        indent--;
        emitLine("}");
        if (nullableOuter) {
            indent--;
            emitLine("}");
        }
        emitLine("out.put(" + quoteJavaString(fieldName) + ", " + arrVar + ");");
    }

    /** Java iteration type of an array wrapper's {@code .data} storage. */
    private String jsonArrayIterType(Type elem) {
        return switch (elem) {
            case Type.Int ignored -> int32Mode ? "int" : "long";
            case Type.Number ignored -> "double";
            case Type.Boolean ignored -> "boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Nullable ne -> switch (ne.inner()) {
                case Type.Int ignored ->
                    int32Mode ? "java.lang.Integer" : "java.lang.Long";
                case Type.Number ignored -> "java.lang.Double";
                case Type.Boolean ignored -> "java.lang.Boolean";
                case Type.String ignored -> "java.lang.String";
                case Type.Bytes ignored -> "java.lang.Object";
                default -> "java.lang.Object";
            };
            case Type.Class ignored -> "java.lang.Object";
            case Type.Bytes ignored -> "java.lang.Object";
            default -> "java.lang.Object";
        };
    }

    /** Emits the one-element JSON conversion inside a toJson array loop. */
    private void emitToJsonArrayElement(Type elem, String arrVar,
                                        String eVar, int idx) {
        switch (elem) {
            case Type.Int ignored ->
                emitLine(arrVar + ".add("
                    + (int32Mode ? "java.lang.Integer" : "java.lang.Long")
                    + ".valueOf(" + eVar + "));");
            case Type.Number ignored ->
                emitLine(arrVar + ".add(java.lang.Double.valueOf(" + eVar + "));");
            case Type.Boolean ignored ->
                emitLine(arrVar + ".add(java.lang.Boolean.valueOf(" + eVar + "));");
            case Type.String ignored ->
                emitLine(arrVar + ".add(" + eVar + ");");
            case Type.Nullable ne -> {
                if (ne.inner() instanceof Type.Class c) {
                    String ref = jsonClassRefSynthetic(c);
                    emitLine(arrVar + ".add(" + eVar + " == null ? null : "
                        + ref + ".$toJsonValue((" + ref + ") " + eVar
                        + ", oFile, oLine, oCol));");
                } else {
                    emitLine(arrVar + ".add(" + eVar + ");");
                }
            }
            case Type.Class c -> {
                String ref = jsonClassRefSynthetic(c);
                emitLine(arrVar + ".add(" + ref + ".$toJsonValue((" + ref
                    + ") " + eVar + ", oFile, oLine, oCol));");
            }
            case Type.Array innerArr -> {

                int level = nextJsonLocalIdx();
                String innerRef = arrayWrapperName(innerArr.element());
                if (innerRef == null) {
                    unsupportedSynthetic("@jsonable toJson value "
                        + "conversion of nested array element type "
                        + typeName(innerArr.element()),
                        "missing anchor: nested array element type '"
                            + typeName(innerArr.element()) + "'");
                    emitLine(arrVar + ".add(null);");
                } else {
                    String iterRef = jsonArrayIterType(
                        innerArr.element());
                    String subArr = "arr" + level;
                    String subE = "e" + level;
                    emitLine("java.util.ArrayList<java.lang.Object> "
                        + subArr + " = new java.util.ArrayList<>();");
                    emitLine("for (" + iterRef + " " + subE + " : (("
                        + innerRef + ") " + eVar + ").data) {");
                indent++;
                    emitToJsonArrayElement(innerArr.element(), subArr,
                        subE, level);
                    indent--;
                    emitLine("}");
                    emitLine(arrVar + ".add(" + subArr + ");");
                }
            }
            case Type.Bytes ignored -> emitLine(arrVar + ".add(null);");
            default -> emitLine(arrVar + ".add(null);");
        }
    }

    /** Emits the per-class {@code $fromJsonValue} validator inside the
     * generated nested class: validates the parsed JSON object against the
     * {@code $jsonFields} descriptors, applies defaults (evaluated inline
     * per call — the same per-construction default freshness the
     * constructor path implements), and returns the DEAL null on ANY
     * validation failure. The table-value depth guard
     * ({@link #JSON_TABLE_DEPTH_LIMIT}) and the nested-class recursion
     * exhaustion propagate to the PUBLIC {@code C$fromJson} wrapper,
     * which converts both shapes to the DEAL null — the public
     * never-throw contract holds, the internal helper stays a plain
     * validator. */
    private void emitJsonableFromJsonValue(ClassDeclaration cd, String gen,
                                           List<String> fieldTypes,
                                           List<Type> types) {
        // Allocated conversion-level suffixes start PAST the field-index
        // namespace (fv{}/cv{}/tm{} names of the field overlays), so a
        // nested conversion can never reuse a sibling field's suffix.
        jsonLocalCounter = types.size();
        emitLine("// @jsonable fromJson validation (declared-field order; null on any");
        emitLine("// validation failure — the public C$fromJson wrapper converts");
        emitLine("// the depth-guard and stack-exhaustion shapes to the DEAL");
        emitLine("// null too, so the export never throws).");
        emitLine("// ISSUE-0302 phase order (parent D5): top-level input gate →");
        emitLine("// provided-field decode in class source order → omitted");
        emitLine("// required defaults → final validation → publish. A");
        emitLine("// provided-value failure returns the DEAL null with zero");
        emitLine("// default side effects.");
        emitLine("static " + gen + " $fromJsonValue(java.lang.Object raw) {");
        indent++;
        emitLine("// Top-level input gate (ISSUE-0302 D3): a document that");
        emitLine("// parses to a scalar, null, or a non-empty array returns the");
        emitLine("// DEAL null (never throws); an empty array [] collapses to");
        emitLine("// the defaulted instance exactly like {} (the documented");
        emitLine("// parse collapse — both roundtrip identically).");
        emitLine("if (raw instanceof java.util.List<?> l) {");
        indent++;
        emitLine("if (!l.isEmpty()) return null;");
        emitLine("raw = java.util.Map.of();");
        indent--;
        emitLine("}");
        emitLine("if (!(raw instanceof java.util.Map<?, ?> m)) return null;");
        emitLine("for (java.util.Map.Entry<?, ?> e : m.entrySet()) {");
        indent++;
        emitLine("java.lang.String key = java.lang.String.valueOf(e.getKey());");
        emitLine("boolean known = false;");
        emitLine("for (java.lang.String[] f : $jsonFields) { if (key.equals(f[0])) { known = true; break; } }");
        emitLine("if (!known) return null;");
        indent--;
        emitLine("}");
        // Phase 1: provided-field decode in class source order. Every
        // field local starts at a type-safe placeholder (the Missing
        // sentinel for an optional-no-default field, zeroValueFor for
        // every other slot) and a provided key overlays the validated
        // converted value. A conversion failure returns null
        // IMMEDIATELY — no default has evaluated yet, so a
        // provided-value failure carries zero default side effects.
        for (int i = 0; i < types.size(); i++) {
            ClassField cf = cd.fields().get(i);
            String placeholder = cf.optional()
                ? jsonableMissingRef(cd) : zeroValueFor(cf.type());
            emitLine(fieldTypes.get(i) + " f" + i + " = " + placeholder
                + ";");
            emitLine("boolean provided" + i + " = false;");
            emitLine("if (m.containsKey(" + quoteJavaString(cf.name())
                + ")) {");
            indent++;
            emitFromJsonOverlay(cf, types.get(i), "f" + i, i);
            emitLine("provided" + i + " = true;");
            indent--;
            emitLine("}");
        }

        for (int i = 0; i < types.size(); i++) {
            ClassField cf = cd.fields().get(i);
            emitLine("if (!provided" + i + ") {");
            indent++;
            String defaultCode;
            if (cf.optional()) {
                defaultCode = jsonableMissingRef(cd);
            } else if (cf.defaultExpr().isPresent()
                    && publishedPlanFor(cd) != null) {
                defaultCode = defaultEvaluatorName(cd.name(), cf.name())
                    + "()";
            } else if (cf.defaultExpr().isPresent()) {
                ExpressionNode def = cf.defaultExpr().get();
                if (typeOf(def) == Type.Error.INSTANCE) {
                    // The checker records every default subexpression's
                    // type; Type.Error here means the frontend reported
                    // errors — record an honest E6000, never emit an
                    // artifact javac would reject.
                    unsupported("default expression of field '" + cf.name()
                        + "' of class '" + cd.name()
                        + "' (unresolved default-expression type)",
                        def.span());
                    defaultCode = zeroValueFor(cf.type());
                } else {
                    defaultCode = emitExpressionFor(def,
                        classFieldDeclaredType(cd, cf));
                }
                Type declaredType = classFieldDeclaredType(cd, cf);
                if (declaredType != null
                        && needsBooleanBoundary(def, declaredType)) {
                    defaultCode = "booleanNotNull(" + defaultCode + ")";
                }
                String fieldJava = declaredType == null ? null
                    : javaLocalType(declaredType, cf.span());
                defaultCode = coerceNullValueCode(defaultCode, def,
                    fieldJava, def.span());

                defaultCode = adaptIntBoundary(def, defaultCode,
                    declaredType);
            } else {
                // Required field with NO declared default: the reference
                // defaults table (LuaBackend.defaultValueForTypeNode)
                // applies the per-type zeroes — 0 / 0.0 / false / "" for
                // the primitives (zeroValueFor), a FRESH empty $DealRt.Table for a
                // table field, and a FRESH empty wrapper for an array
                // field (the spec's per-construction freshness). A Java
                // null in those slots would let the DEAL null cross a
                // non-nullable table/array boundary and crash the first
                // read with a raw NPE (the reviewed defect). A required
                // CLASS-typed field's {} placeholder has no Java value at
                // the typed slot; its absent key is a fromJson validation
                // failure (the guard right after this loop — the null
                // placeholder below stays unreachable past it).
                Type ft = types.get(i);
                if (ft instanceof Type.Table) {
                    defaultCode = "new $DealRt.Table()";
                } else if (ft instanceof Type.Array a) {
                    defaultCode = "new " + arrayWrapperName(a.element())
                        + "(new " + jsonArrayStorageType(a.element()) + "[0])";
                } else {
                    defaultCode = zeroValueFor(cf.type());
                }
            }
            if (!preStatements.isEmpty()) flushPreStatements();
            emitLine("f" + i + " = " + defaultCode + ";");
            indent--;
            emitLine("}");
        }
        flushPreStatements(); // defensive: empty at a statement boundary
        // Phase 3: final validation. Required class-typed fields with NO
        // declared default: the reference defaults table holds a raw {}
        // placeholder (a plain Lua table, never a class instance) which
        // the typed Java field slot cannot represent — Java null there
        // would silently cross the non-nullable class boundary (the
        // reviewed defect: a raw NPE or a silent null read where
        // LuaJIT's check_type raises E8001 at the typed read). A
        // provided key overlays a validated nested instance in phase 1,
        // so only the ABSENT key fails: a fromJson validation failure
        // (the DEAL null, the spec's never-throw contract). The guard
        // runs after the omitted defaults so default-expression side
        // effects keep their phase-2 order.
        for (int i = 0; i < types.size(); i++) {
            ClassField cf = cd.fields().get(i);
            if (!cf.optional() && cf.defaultExpr().isEmpty()
                    && types.get(i) instanceof Type.Class) {
                emitLine("if (!provided" + i + ") return null;");
            }
        }
        // Phase 4: publish the validated instance.
        StringBuilder args = new StringBuilder("new " + gen + "(");
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) args.append(", ");
            args.append("f").append(i);
        }
        emitLine("return " + args + ");");
        indent--;
        emitLine("}");
    }

    /** Emits the present-key overlay lines of one field: validates the raw
     * JSON value and assigns the converted value (or returns null). */
    private void emitFromJsonOverlay(ClassField cf, Type t,
                                     String targetVar, int idx) {
        emitLine("if (m.containsKey(" + quoteJavaString(cf.name()) + ")) {");
        indent++;
        emitLine("java.lang.Object fv" + idx + " = m.get("
            + quoteJavaString(cf.name()) + ");");
        if (t instanceof Type.Null) {
            emitLine("if (fv" + idx + " != null) return null;");
        } else if (t instanceof Type.Nullable nn) {
            emitLine("if (fv" + idx + " != null) {");
            indent++;
            emitJsonConvert(nn.inner(), "fv" + idx, targetVar, idx, true);
            indent--;
            emitLine("} else {");
            indent++;
            emitLine(targetVar + " = null;");
            indent--;
            emitLine("}");
        } else {
            emitJsonConvert(t, "fv" + idx, targetVar, idx, false);
        }
        indent--;
        emitLine("}");
    }

    /** Allocates the next fresh conversion-local suffix for the current
     * {@code $fromJsonValue}/{@code $toJsonValue} emission (see {@link
     * #jsonLocalCounter}). */
    private int nextJsonLocalIdx() {
        return jsonLocalCounter++;
    }

    /** Emits the validation + assignment lines converting a raw JSON value
     * ({@code rawVar}) into {@code targetVar} (a declared field local or an
     * array slot). {@code boxed} selects the boxed reference assignment for
     * nullable fields (primitives stay boxed there). Every conversion
     * returns null from {@code $fromJsonValue} on a type mismatch. */
    private void emitJsonConvert(Type t, String rawVar, String targetVar,
                                 int idx, boolean boxed) {
        switch (t) {
            case Type.Int ignored -> {
                emitLine((int32Mode ? "java.lang.Integer" : "java.lang.Long")
                    + " cv" + idx + " = __jsonInt(" + rawVar + ");");
                emitLine("if (cv" + idx + " == null) return null;");
                emitLine(targetVar + " = " + (boxed ? "cv" + idx
                    : "cv" + idx + (int32Mode ? ".intValue()" : ".longValue()"))
                    + ";");
            }
            case Type.Number ignored -> {
                emitLine("java.lang.Double cv" + idx + " = __jsonNumber(" + rawVar + ");");
                emitLine("if (cv" + idx + " == null) return null;");
                emitLine(targetVar + " = " + (boxed ? "cv" + idx
                    : "cv" + idx + ".doubleValue()") + ";");
            }
            case Type.Boolean ignored -> {
                emitLine("java.lang.Boolean cv" + idx + " = __jsonBoolean(" + rawVar + ");");
                emitLine("if (cv" + idx + " == null) return null;");
                emitLine(targetVar + " = " + (boxed ? "cv" + idx
                    : "cv" + idx + ".booleanValue()") + ";");
            }
            case Type.String ignored -> {
                emitLine("java.lang.String cv" + idx + " = __jsonString(" + rawVar + ");");
                emitLine("if (cv" + idx + " == null) return null;");
                emitLine(targetVar + " = cv" + idx + ";");
            }
            case Type.Table ignored -> {
                // The spec's table-field contract: fromJson accepts ONLY
                // a JSON object and maps it to a DEAL table holding
                // untyped JSON-shaped data (nested objects as string-keyed
                // $DealRt.Table tables, nested arrays as array-mode $DealRt.Table tables,
                // leaves as-is). Any other JSON value is a validation
                // failure.
                emitLine("if (!(" + rawVar + " instanceof java.util.Map<?, ?> tm" + idx + ")) return null;");
                emitLine(targetVar + " = ($DealRt.Table) __jsonTableValue(" + rawVar + ", 0);");
            }
            case Type.Class c -> {
                String ref = jsonClassRefSynthetic(c);
                emitLine(ref + " cv" + idx + " = " + ref
                    + ".$fromJsonValue(" + rawVar + ");");
                emitLine("if (cv" + idx + " == null) return null;");
                emitLine(targetVar + " = cv" + idx + ";");
            }
            case Type.Array a -> {

                int level = nextJsonLocalIdx();
                String storage = jsonArrayStorageType(a.element());
                emitLine("if (!(" + rawVar + " instanceof java.util.List<?> l" + level + ")) return null;");
                emitLine(storage + "[] a" + level + " = new " + storage
                    + "[l" + level + ".size()];");
                emitLine("int i" + level + " = 0;");
                emitLine("for (java.lang.Object e" + level + " : l" + level + ") {");
                indent++;
                emitJsonArrayElementConvert(a.element(), level);
                indent--;
                emitLine("}");
                emitLine(targetVar + " = new " + arrayWrapperName(a.element())
                    + "(a" + level + ");");
            }
            case Type.Bytes ignored -> {
                // bytes is not jsonable (the E4007 checker rule is a
                // later slice); fail through the jsonable conversion's
                // existing unsupported-shape handling.
                unsupportedSynthetic("@jsonable value conversion of type "
                    + typeName(t),
                    "missing anchor: source span for @jsonable value "
                        + "conversion of type '" + typeName(t) + "'");
                emitLine(targetVar + " = null;");
            }
            default -> {
                unsupportedSynthetic("@jsonable value conversion of type "
                    + typeName(t),
                    "missing anchor: source span for @jsonable value "
                        + "conversion of type '" + typeName(t) + "'");
                emitLine(targetVar + " = null;");
            }
        }
    }

    /** Java storage type of a jsonable array's element array. */
    private String jsonArrayStorageType(Type elem) {
        return switch (elem) {
            case Type.Int ignored -> int32Mode ? "int" : "long";
            case Type.Number ignored -> "double";
            case Type.Boolean ignored -> "boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Nullable ne -> switch (ne.inner()) {
                case Type.Int ignored ->
                    int32Mode ? "java.lang.Integer" : "java.lang.Long";
                case Type.Number ignored -> "java.lang.Double";
                case Type.Boolean ignored -> "java.lang.Boolean";
                case Type.String ignored -> "java.lang.String";
                case Type.Bytes ignored -> "java.lang.Object";
                default -> "java.lang.Object";
            };
            case Type.Class ignored -> "java.lang.Object";
            case Type.Bytes ignored -> "java.lang.Object";
            default -> "java.lang.Object";
        };
    }

    /** Emits the per-element conversion of a jsonable array (storage
     * {@code a<idx>}, index {@code i<idx>}, element {@code e<idx>}). */
    private void emitJsonArrayElementConvert(Type elem, int idx) {
        if (elem instanceof Type.Nullable nn) {
            emitLine(jsonBoxedType(nn.inner(), idx) + " el" + idx + " = null;");
            emitLine("if (e" + idx + " != null) {");
            indent++;
            emitJsonConvert(nn.inner(), "e" + idx, "el" + idx, idx, true);
            indent--;
            emitLine("}");
            emitLine("a" + idx + "[i" + idx + "++] = el" + idx + ";");
            return;
        }
        emitJsonConvert(elem, "e" + idx, "a" + idx + "[i" + idx + "++]",
            idx, false);
    }

    /** Java boxed type of a nullable-element array element local. */
    private String jsonBoxedType(Type inner, int idx) {
        return switch (inner) {
            case Type.Int ignored ->
                int32Mode ? "java.lang.Integer" : "java.lang.Long";
            case Type.Number ignored -> "java.lang.Double";
            case Type.Boolean ignored -> "java.lang.Boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Class c -> {
                String ref = jsonClassRefSynthetic(c);
                yield ref == null ? "java.lang.Object" : ref;
            }
            case Type.Bytes ignored -> "java.lang.Object";
            default -> "java.lang.Object";
        };
    }

    /** Emits the module-level public {@code C$fromJson}/{@code C$toJson}
     * exports (spec §JSON serialization). The Java names are the
     * {@link #javaName} translations of the DEAL names — exactly what
     * {@link #emitCall} and {@link #emitMemberAccessCall} emit for
     * {@code C$fromJson(...)} / {@code Lib.C$fromJson(...)} call sites —
     * so same-module and cross-module calls bind to these methods. */
    private void emitJsonablePublicHelpers(ClassDeclaration cd, String gen) {
        String fromJson = javaName(cd.name() + "$fromJson");
        String toJson = javaName(cd.name() + "$toJson");
        emitLine("// @jsonable generated exports (spec §JSON serialization):");
        emitLine("// " + cd.name() + "$fromJson(s) — parse + validate; the DEAL null on");
        emitLine("// any parse/validation failure, never a throw. The wrapper");
        emitLine("// converts the two exhaustion shapes to the DEAL null:");
        emitLine("// the table-value depth guard's RuntimeException (past "
            + JSON_TABLE_DEPTH_LIMIT + " nesting levels) and the");
        emitLine("// StackOverflowError of the nested-class $fromJsonValue");
        emitLine("// recursion over deeply nested JSON objects.");
        emitLine("public static " + gen + " " + fromJson
            + "(java.lang.String s) {");
        indent++;
        emitLine("try { return " + gen + ".$fromJsonValue(__jsonParse(s)); }");
        emitLine("catch (java.lang.RuntimeException e) { return null; }");
        emitLine("catch (java.lang.StackOverflowError e) { return null; }");
        indent--;
        emitLine("}");
        emitLine("// " + cd.name() + "$toJson(v) — serialize to a JSON string.");
        emitLine("// The caller passes its call-expression origin (D3); the walk");
        emitLine("// threads it unchanged into the shape check and the stringifier.");
        emitLine("public static java.lang.String " + toJson + "(" + gen
            + " v, java.lang.String oFile, int oLine, int oCol) {");
        indent++;
        emitLine("return __jsonStringify(" + gen
            + ".$toJsonValue(v, oFile, oLine, oCol), oFile, oLine, oCol);");
        indent--;
        emitLine("}");
    }

    /**
     * Emits the @jsonable JSON runtime support: a minimal strict JSON
     * parser ({@code __jsonParse}: LinkedHashMaps for objects, ArrayLists
     * for arrays, Double/Boolean/String leaves, the DEAL null for JSON
     * null and parse failure alike), a JSON stringifier ({@code
     * __jsonStringify}, E8001 for non-finite numbers — std/json.lua's
     * NaN/Infinity rejection), and the per-type value validators ({@code
     * __jsonInt} etc. — null on a type mismatch, the fromJson
     * validation-failure contract). Emitted only when the module declares
     * at least one @jsonable class; every name carries the {@code __}
     * prefix, unreachable from {@link #javaName}.
     */
    private void emitJsonRuntimeSupport() {
        emitLine("// ---- @jsonable JSON runtime support ----");
        emitLine("// The per-module Missing sentinel: an optional class field whose");
        emitLine("// storage holds this reference is ABSENT (the spec's Missing");
        emitLine("// sentinel representation). Distinct from the DEAL null, so");
        emitLine("// optional-nullable fields keep their three states.");
        emitLine("static final java.lang.Object $MISSING = new java.lang.Object();");
        emitLine("// Minimal strict JSON parser: LinkedHashMap for objects, ArrayList");
        emitLine("// for arrays, Double for numbers, Boolean, String, and null for");
        emitLine("// JSON null. Parse failure returns null (the DEAL null of the");
        emitLine("// C$fromJson contract — never a throw).");
        emitLine("// The last decoder error message (ISSUE-0302): __jsonParse");
        emitLine("// collapses every parse failure to the DEAL null for the");
        emitLine("// C$fromJson never-throw contract; the std/json json.parse");
        emitLine("// boundary re-raises the stored message as E8001, mirroring");
        emitLine("// std/json.lua's parse_error inside the pcall-equivalent.");
        emitLine("static java.lang.String $jsonLastError = null;");
        emitLine("static java.lang.Object __jsonParse(java.lang.String s) {");
        indent++;
        emitLine("try { __JsonParser p = new __JsonParser(s); java.lang.Object v = p.parseValue(); p.skipWs(); return p.atEnd() ? v : null; }");
        emitLine("catch (java.lang.RuntimeException e) { $jsonLastError = e.getMessage(); return null; }");
        emitLine("// Deeply nested JSON (hostile ~10 KB payloads) overflows the");
        emitLine("// recursive parser's stack: the StackOverflowError converts to");
        emitLine("// the DEAL null exactly like LuaJIT's pcall(__json_parse, s)");
        emitLine("// converts its stack exhaustion — C$fromJson never throws.");
        emitLine("catch (java.lang.StackOverflowError e) { return null; }");
        indent--;
        emitLine("}");
        emitLine("static final class __JsonParser {");
        indent++;
        emitLine("final java.lang.String s;");
        emitLine("int i = 0;");
        emitLine("__JsonParser(java.lang.String s) { this.s = s; }");
        emitLine("boolean atEnd() { return i >= s.length(); }");
        emitLine("char peek() { if (i >= s.length()) throw new java.lang.RuntimeException(\"unexpected end of JSON input\"); return s.charAt(i); }");
        emitLine("void expect(char c) { if (peek() != c) throw new java.lang.RuntimeException(\"unexpected character in JSON input\"); i++; }");
        emitLine("void skipWs() { while (i < s.length()) { char c = s.charAt(i); if (c != ' ' && c != '\\t' && c != '\\n' && c != '\\r') return; i++; } }");
        emitLine("java.lang.Object parseValue() {");
        indent++;
        emitLine("skipWs();");
        emitLine("char c = peek();");
        emitLine("if (c == '{') return parseObject();");
        emitLine("if (c == '[') return parseArray();");
        emitLine("if (c == '\"') return parseString();");
        emitLine("if (c == 't') { expect('t'); expect('r'); expect('u'); expect('e'); return java.lang.Boolean.TRUE; }");
        emitLine("if (c == 'f') { expect('f'); expect('a'); expect('l'); expect('s'); expect('e'); return java.lang.Boolean.FALSE; }");
        emitLine("if (c == 'n') { expect('n'); expect('u'); expect('l'); expect('l'); return null; }");
        emitLine("if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();");
        emitLine("throw new java.lang.RuntimeException(\"unexpected character in JSON input\");");
        indent--;
        emitLine("}");
        emitLine("java.util.LinkedHashMap<java.lang.String, java.lang.Object> parseObject() {");
        indent++;
        emitLine("expect('{');");
        emitLine("java.util.LinkedHashMap<java.lang.String, java.lang.Object> m = new java.util.LinkedHashMap<>();");
        emitLine("skipWs();");
        emitLine("if (peek() == '}') { i++; return m; }");
        emitLine("while (true) {");
        indent++;
        emitLine("skipWs();");
        emitLine("java.lang.String k = parseString();");
        emitLine("skipWs();");
        emitLine("expect(':');");
        emitLine("m.put(k, parseValue());");
        emitLine("skipWs();");
        emitLine("char c = peek();");
        emitLine("if (c == ',') { i++; continue; }");
        emitLine("if (c == '}') { i++; return m; }");
        emitLine("throw new java.lang.RuntimeException(\"unexpected character in JSON object\");");
        indent--;
        emitLine("}");
        indent--;
        emitLine("}");
        emitLine("java.util.ArrayList<java.lang.Object> parseArray() {");
        indent++;
        emitLine("expect('[');");
        emitLine("java.util.ArrayList<java.lang.Object> list = new java.util.ArrayList<>();");
        emitLine("skipWs();");
        emitLine("if (peek() == ']') { i++; return list; }");
        emitLine("while (true) {");
        indent++;
        emitLine("list.add(parseValue());");
        emitLine("skipWs();");
        emitLine("char c = peek();");
        emitLine("if (c == ',') { i++; continue; }");
        emitLine("if (c == ']') { i++; return list; }");
        emitLine("throw new java.lang.RuntimeException(\"unexpected character in JSON array\");");
        indent--;
        emitLine("}");
        indent--;
        emitLine("}");
        emitLine("java.lang.String parseString() {");
        indent++;
        emitLine("expect('\"');");
        emitLine("java.lang.StringBuilder sb = new java.lang.StringBuilder();");
        emitLine("while (true) {");
        indent++;
        emitLine("char c = peek();");
        emitLine("if (c == '\"') { i++; java.lang.String out = sb.toString();"
            + " if (__hasUnpairedSurrogate(out)) throw new java.lang.RuntimeException(\"unpaired UTF-16 surrogate code unit in JSON string\");"
            + " return out; }");
        emitLine("// spec-v1.2 \u00a7Lexical elements: JSON strings entering the"
            + " DEAL string boundary must carry no unpaired UTF-16"
            + " surrogate code units \u2014 a lone 0xD800 / 0xDC00 escape"
            + " (or a raw lone surrogate) is a parse failure (the DEAL"
            + " null), exactly like LuaJIT's std/json.lua decoder error"
            + " inside pcall(__json_parse, s).");
        emitLine("// RFC 8259 section 7: a raw U+0000-U+001F character"
            + " must be escaped — std/json.lua parse_string rejects"
            + " it with \"raw control character in string (must be"
            + " escaped)\", so a document carrying one is a parse"
            + " failure (the DEAL null via __jsonParse, E8001 via"
            + " json.parse).");
        emitLine("if (c < 0x20) throw new java.lang.RuntimeException(\"raw control character in string (must be escaped)\");");
        emitLine("if (c != '\\\\') { sb.append(c); i++; continue; }");
        emitLine("i++;");
        emitLine("char e = peek();");
        emitLine("switch (e) {");
        indent++;
        emitLine("case '\"': sb.append('\"'); i++; break;");
        emitLine("case '\\\\': sb.append('\\\\'); i++; break;");
        emitLine("case '/': sb.append('/'); i++; break;");
        emitLine("case 'b': sb.append('\\b'); i++; break;");
        emitLine("case 'f': sb.append('\\f'); i++; break;");
        emitLine("case 'n': sb.append('\\n'); i++; break;");
        emitLine("case 'r': sb.append('\\r'); i++; break;");
        emitLine("case 't': sb.append('\\t'); i++; break;");
        emitLine("case 'u': {");
        indent++;
        emitLine("i++;");
        emitLine("if (i + 4 > s.length()) throw new java.lang.RuntimeException(\"bad \\\\u escape\");");
        emitLine("int code = 0;");
        emitLine("for (int j = 0; j < 4; j++) { int d = java.lang.Character.digit(s.charAt(i + j), 16); if (d < 0) throw new java.lang.RuntimeException(\"bad \\\\u escape\"); code = code * 16 + d; }");
        emitLine("i += 4;");
        emitLine("sb.append((char) code);");
        emitLine("break;");
        indent--;
        emitLine("}");
        emitLine("default: throw new java.lang.RuntimeException(\"bad escape in JSON string\");");
        indent--;
        emitLine("}");
        indent--;
        emitLine("}");
        indent--;
        emitLine("}");
        emitLine("java.lang.Double parseNumber() {");
        indent++;
        emitLine("int start = i;");
        emitLine("if (peek() == '-') i++;");
        emitLine("// spec-v1.2 \u00a7JSON serialization: the strict RFC 8259 number");
        emitLine("// grammar, exactly the checks of fs/std/json.lua's parse_number.");
        emitLine("// Invalid spellings are parse failures (the DEAL null via the");
        emitLine("// __jsonParse catch), never a permissive Double.parseDouble");
        emitLine("// acceptance: a leading zero (01, -01, 00), a decimal point");
        emitLine("// without a fraction digit (1.), an exponent without digits");
        emitLine("// (1.e2, 1e), a sign without digits (1e+), and a missing");
        emitLine("// integer part (-.5) all reject exactly like the LuaJIT");
        emitLine("// reference's parse_error inside pcall(__json_parse, s).");
        emitLine("if (i < s.length() && s.charAt(i) == '0') { i++; }");
        emitLine("else {");
        indent++;
        emitLine("if (i >= s.length() || s.charAt(i) < '1' || s.charAt(i) > '9') throw new java.lang.RuntimeException(\"bad JSON number\");");
        emitLine("i++;");
        emitLine("while (i < s.length()) { char c = s.charAt(i); if (c >= '0' && c <= '9') i++; else break; }");
        indent--;
        emitLine("}");
        emitLine("if (i < s.length() && s.charAt(i) == '.') {");
        indent++;
        emitLine("i++;");
        emitLine("if (i >= s.length() || s.charAt(i) < '0' || s.charAt(i) > '9') throw new java.lang.RuntimeException(\"bad JSON number\");");
        emitLine("while (i < s.length()) { char c = s.charAt(i); if (c >= '0' && c <= '9') i++; else break; }");
        indent--;
        emitLine("}");
        emitLine("if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {");
        indent++;
        emitLine("i++;");
        emitLine("if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;");
        emitLine("if (i >= s.length() || s.charAt(i) < '0' || s.charAt(i) > '9') throw new java.lang.RuntimeException(\"bad JSON number\");");
        emitLine("while (i < s.length()) { char c = s.charAt(i); if (c >= '0' && c <= '9') i++; else break; }");
        indent--;
        emitLine("}");
        emitLine("java.lang.String num = s.substring(start, i);");
        emitLine("try { return java.lang.Double.valueOf(java.lang.Double.parseDouble(num)); } catch (java.lang.NumberFormatException e) { throw new java.lang.RuntimeException(\"bad JSON number\"); }");
        indent--;
        emitLine("}");
        indent--;
        emitLine("}");
        emitLine("// Untyped JSON-shaped table data conversion (spec §JSON");
        emitLine("// serialization): a parsed JSON object becomes a string-keyed $DealRt.Table,");
        emitLine("// a parsed JSON array becomes an array-mode $DealRt.Table (DEAL tables are");
        emitLine("// string-keyed, so the array shape is preserved in the wrapper),");
        emitLine("// nested structures recurse, and leaves pass through. Integral");
        emitLine("// parsed numbers become Long so re-serialization prints \"1\"");
        emitLine("// exactly like LuaJIT's %.17g (\"1\"), never \"1.0\".");
        emitLine("// The bounded depth guard keeps hostile deep nesting from");
        emitLine("// exhausting the JVM stack: past " + JSON_TABLE_DEPTH_LIMIT);
        emitLine("// levels the conversion throws, and the public C$fromJson");
        emitLine("// wrapper converts the throw to the DEAL null (the spec's");
        emitLine("// fromJson parse/validation-failure contract) — a deterministic");
        emitLine("// guard instead of relying on a StackOverflowError at the");
        emitLine("// recursion limit.");
        emitLine("static java.lang.Object __jsonTableValue(java.lang.Object raw, int depth) {");
        indent++;
        emitLine("if (depth > " + JSON_TABLE_DEPTH_LIMIT + ") throw new java.lang.RuntimeException(\"JSON nesting too deep\");");
        emitLine("if (raw instanceof java.util.Map<?, ?> m) {");
        indent++;
        emitLine("$DealRt.Table t = new $DealRt.Table();");
        emitLine("for (java.util.Map.Entry<?, ?> e : m.entrySet()) { t.put(java.lang.String.valueOf(e.getKey()), __jsonTableValue(e.getValue(), depth + 1)); }");
        emitLine("return t;");
        indent--;
        emitLine("}");
        emitLine("if (raw instanceof java.util.List<?> l) {");
        indent++;
        emitLine("java.util.ArrayList<java.lang.Object> arr = new java.util.ArrayList<>();");
        emitLine("for (java.lang.Object e : l) { arr.add(__jsonTableValue(e, depth + 1)); }");
        emitLine("return new $DealRt.Table(arr);");
        indent--;
        emitLine("}");
        emitLine("if (raw instanceof java.lang.Double d) {");
        indent++;
        emitLine("if (d.doubleValue() == java.lang.Math.floor(d.doubleValue()) && !java.lang.Double.isInfinite(d.doubleValue())");
        indent++;
        emitLine(int32Mode
            ? "        && d.doubleValue() >= -2147483648.0 && d.doubleValue() <= 2147483647.0) { return java.lang.Integer.valueOf((int) d.longValue()); }"
            : "        && d.doubleValue() >= -9007199254740991.0 && d.doubleValue() <= 9007199254740991.0) { return java.lang.Long.valueOf(d.longValue()); }");
        indent--;
        emitLine("return d;");
        indent--;
        emitLine("}");
        emitLine("return raw;");
        indent--;
        emitLine("}");
        emitLine("// std/json boundary (ISSUE-0302): json.parse lowers here. A");
        emitLine("// malformed document (bad escapes, lone surrogates, raw");
        emitLine("// control characters, invalid numbers, trailing bytes) is the");
        emitLine("// __jsonParse null and re-raises as E8001 with the decoder's");
        emitLine("// stored message — std/json.lua's parse_error inside the");
        emitLine("// pcall-equivalent. Hostile deep nesting past the bounded");
        emitLine("// table-value depth guard raises E8001 too, never a raw");
        emitLine("// stack exhaustion.");
        emitLine("static $DealRt.Table $jsonParse(java.lang.String s) { return $jsonParse(s, null, -1, -1); }");
        emitLine("static $DealRt.Table $jsonParse(java.lang.String s, java.lang.String oFile, int oLine, int oCol) {");
        indent++;
        emitLine("java.lang.Object v = __jsonParse(s);");
        emitLine("if (v == null) throw new DealError(\"E8001\", \"JSON parse error\" + ($jsonLastError == null ? \"\" : \": \" + $jsonLastError), oFile, oLine, oCol);");
        emitLine("try { return ($DealRt.Table) __jsonTableValue(v, 0); }");
        emitLine("catch (java.lang.RuntimeException e) { throw new DealError(\"E8001\", \"JSON nesting too deep\", oFile, oLine, oCol); }");
        indent--;
        emitLine("}");
        emitLine("// JSON stringify (std/json.lua contract): null, Boolean, String, Long");
        emitLine("// (int fields), Number (number fields), List (array fields), Map");
        emitLine("// (nested class fields), $DealRt.Table table values (string-keyed objects and");
        emitLine("// array-mode tables), and the emitted primitive-array wrappers");
        emitLine("// (DEAL arrays stored in tables are JSON-shaped array values).");
        emitLine("// NaN/Infinity raise E8001 exactly like std/json.lua's encode_value");
        emitLine("// rejection; a cycle or any other value raises E8001 (the spec's");
        emitLine("// finite-acyclic JSON-shape validation).");
        emitLine("static java.lang.String __jsonStringify(java.lang.Object v) { return __jsonStringify(v, null, -1, -1); }");
        emitLine("static java.lang.String __jsonStringify(java.lang.Object v, java.lang.String oFile, int oLine, int oCol) {");
        indent++;
        emitLine("java.lang.StringBuilder sb = new java.lang.StringBuilder();");
        emitLine("__jsonAppend(sb, v, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), oFile, oLine, oCol);");
        emitLine("return sb.toString();");
        indent--;
        emitLine("}");
        emitLine("static void __jsonAppend(java.lang.StringBuilder sb, java.lang.Object v, java.util.Set<java.lang.Object> stack) { __jsonAppend(sb, v, stack, null, -1, -1); }");
        emitLine("static void __jsonAppend(java.lang.StringBuilder sb, java.lang.Object v, java.util.Set<java.lang.Object> stack, java.lang.String oFile, int oLine, int oCol) {");
        indent++;
        emitLine("if (v == null) { sb.append(\"null\"); return; }");
        emitLine("if (v instanceof java.lang.Boolean b) { sb.append(b.booleanValue() ? \"true\" : \"false\"); return; }");
        emitLine("if (v instanceof java.lang.String s) { sb.append(__jsonQuote(s, oFile, oLine, oCol)); return; }");
        emitLine(int32Mode
            ? "if (v instanceof java.lang.Integer i) { sb.append(i.toString()); return; }"
            : "if (v instanceof java.lang.Long l) { sb.append(l.toString()); return; }");
        emitLine("if (v instanceof java.lang.Number n) {");
        indent++;
        emitLine("double d = n.doubleValue();");
        emitLine("if (java.lang.Double.isNaN(d)) throw new DealError(\"E8001\", \"cannot encode NaN as JSON\", oFile, oLine, oCol);");
        emitLine("if (java.lang.Double.isInfinite(d)) throw new DealError(\"E8001\", \"cannot encode Infinity as JSON\", oFile, oLine, oCol);");
        emitLine("sb.append(java.lang.Double.toString(d));");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof java.util.List<?> list) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (java.lang.Object e : list) { if (!first) sb.append(','); first = false; __jsonAppend(sb, e, stack, oFile, oLine, oCol); }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof java.util.Map<?, ?> map) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('{');");
        emitLine("boolean first = true;");
        emitLine("for (java.util.Map.Entry<?, ?> e : map.entrySet()) { if (!first) sb.append(','); first = false; sb.append(__jsonQuote(java.lang.String.valueOf(e.getKey()), oFile, oLine, oCol)); sb.append(':'); __jsonAppend(sb, e.getValue(), stack, oFile, oLine, oCol); }");
        emitLine("sb.append('}');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.Table t) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("java.util.ArrayList<java.lang.Object> arr = t.$array();");
        emitLine("if (arr != null) {");
        indent++;
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (java.lang.Object e : arr) { if (!first) sb.append(','); first = false; __jsonAppend(sb, e, stack, oFile, oLine, oCol); }");
        emitLine("sb.append(']');");
        indent--;
        emitLine("} else {");
        indent++;
        emitLine("sb.append('{');");
        emitLine("boolean first = true;");
        emitLine("for (java.util.Map.Entry<java.lang.String, java.lang.Object> e : t.$entries().entrySet()) { if (!first) sb.append(','); first = false; sb.append(__jsonQuote(e.getKey(), oFile, oLine, oCol)); sb.append(':'); __jsonAppend(sb, e.getValue(), stack, oFile, oLine, oCol); }");
        emitLine("sb.append('}');");
        indent--;
        emitLine("}");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__IntArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine(int32Mode
            ? "for (int e : a.data) { if (!first) sb.append(','); first = false; sb.append(java.lang.Integer.toString(e)); }"
            : "for (long e : a.data) { if (!first) sb.append(','); first = false; sb.append(java.lang.Long.toString(e)); }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__NumberArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (double e : a.data) { if (!first) sb.append(','); first = false; if (java.lang.Double.isNaN(e)) throw new DealError(\"E8001\", \"cannot encode NaN as JSON\", oFile, oLine, oCol); if (java.lang.Double.isInfinite(e)) throw new DealError(\"E8001\", \"cannot encode Infinity as JSON\", oFile, oLine, oCol); sb.append(java.lang.Double.toString(e)); }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__StringArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (java.lang.String e : a.data) { if (!first) sb.append(','); first = false; sb.append(__jsonQuote(e, oFile, oLine, oCol)); }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__BooleanArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (boolean e : a.data) { if (!first) sb.append(','); first = false; sb.append(e ? \"true\" : \"false\"); }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__IntOrNullArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine(int32Mode
            ? "for (java.lang.Integer e : a.data) { if (!first) sb.append(','); first = false; if (e == null) { sb.append(\"null\"); } else { sb.append(e.toString()); } }"
            : "for (java.lang.Long e : a.data) { if (!first) sb.append(','); first = false; if (e == null) { sb.append(\"null\"); } else { sb.append(e.toString()); } }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__NumberOrNullArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (java.lang.Double e : a.data) { if (!first) sb.append(','); first = false; if (e == null) { sb.append(\"null\"); } else { if (java.lang.Double.isNaN(e)) throw new DealError(\"E8001\", \"cannot encode NaN as JSON\", oFile, oLine, oCol); if (java.lang.Double.isInfinite(e)) throw new DealError(\"E8001\", \"cannot encode Infinity as JSON\", oFile, oLine, oCol); sb.append(java.lang.Double.toString(e)); } }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__StringOrNullArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (java.lang.String e : a.data) { if (!first) sb.append(','); first = false; if (e == null) { sb.append(\"null\"); } else { sb.append(__jsonQuote(e, oFile, oLine, oCol)); } }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__BooleanOrNullArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (java.lang.Boolean e : a.data) { if (!first) sb.append(','); first = false; if (e == null) { sb.append(\"null\"); } else { sb.append(e.booleanValue() ? \"true\" : \"false\"); } }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("// ISSUE-0551: bytes-element array carriers serialize their");
        emitLine("// elements recursively like the __RefArray family, so a bytes");
        emitLine("// element anywhere inside a table-held bytes[] /");
        emitLine("// (bytes | null)[] reaches the pinned std/json bytes");
        emitLine("// rejection arm below (\"unsupported type for JSON encoding:");
        emitLine("// — LuaJIT walks the array and rejects the element, so a bare");
        emitLine("// carrier name (__BytesArray) must never leak into the message).");
        emitLine("if (v instanceof $DealRt.__BytesArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for ($DealRt.Bytes e : a.data) { if (!first) sb.append(','); first = false; __jsonAppend(sb, e, stack, oFile, oLine, oCol); }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__BytesOrNullArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for ($DealRt.Bytes e : a.data) { if (!first) sb.append(','); first = false; if (e == null) { sb.append(\"null\"); } else { __jsonAppend(sb, e, stack, oFile, oLine, oCol); } }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("// ISSUE-0302 recursive array serialization: every shared");
        emitLine("// __RefArray subclass (nested-array per-element-shape");
        emitLine("// carriers and per-class array wrappers) serializes its");
        emitLine("// elements recursively through __jsonAppend, so a table");
        emitLine("// field holding [[1,2],[3,4]] roundtrips — the inner");
        emitLine("// wrappers hit their own branches at any depth, and a");
        emitLine("// class element fails JSON-shape validation exactly like");
        emitLine("// any other non-JSON-shaped value.");
        emitLine("if (v instanceof $DealRt.__RefArray a) {");
        indent++;
        emitLine("if (!stack.add(v)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("sb.append('[');");
        emitLine("boolean first = true;");
        emitLine("for (java.lang.Object e : a.data) { if (!first) sb.append(','); first = false; __jsonAppend(sb, e, stack, oFile, oLine, oCol); }");
        emitLine("sb.append(']');");
        emitLine("stack.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("// std/json.lua encode_value parity (ISSUE-0302): a value");
        emitLine("// outside the JSON-shaped set — a function, a bytes");
        emitLine("// carrier, an unknown object — raises the unsupported-");
        emitLine("// type E8001 with the Lua type name and the closed D4");
        emitLine("// expected/actual projection, threaded unchanged through");
        emitLine("// the recursive walk (a rejection at any nesting depth");
        emitLine("// reports the call expression's origin).");
        emitLine("throw new DealError(\"E8001\", \"unsupported type for JSON encoding: \" + __jsonKind(v), oFile, oLine, oCol, \"string, number, boolean, or table\", __jsonKind(v), null, null);");
        indent--;
        emitLine("}");
        emitLine("// The closed runtime-kind projection of the unsupported-type");
        emitLine("// rejection (D4): the DEAL kind name the message tail renders");
        emitLine("// — never a fabricated field.");
        emitLine("static java.lang.String __jsonKind(java.lang.Object v) {");
        indent++;
        emitLine("if (v instanceof $DealRt.FnValue) return \"function\";");
        emitLine("if (v instanceof $DealRt.Bytes) return \"bytes\";");
        emitLine("return v.getClass().getSimpleName();");
        indent--;
        emitLine("}");
        emitLine("// ---- @jsonable table-field shape validation (ISSUE-0160 D6) ----");
        emitLine("// deal/runtime.lua _json_table_shape mirror: the @jsonable");
        emitLine("// C$toJson walker validates every table FIELD value BEFORE the");
        emitLine("// stringifier runs (LuaJIT's _json_to_value jtype \"table\"");
        emitLine("// arm), so a table-held value outside the finite acyclic");
        emitLine("// JSON-shaped set — a bytes carrier, a function, a class");
        emitLine("// instance, a NaN/Infinity leaf — raises E8001 \"value is");
        emitLine("// not JSON-shaped\", a re-entered table/array raises");
        emitLine("// \"cyclic value cannot be encoded as JSON\", and nesting");
        emitLine("// past " + JSON_TABLE_DEPTH_LIMIT + " levels raises the depth guard —");
        emitLine("// byte-identical to the LuaJIT @jsonable reference BEFORE");
        emitLine("// std/json's encode_value ever runs. The std/json stringify");
        emitLine("// surface keeps its own encode_value-parity arm above");
        emitLine("// (\"unsupported type for JSON encoding: bytes\" — std/json.lua's");
        emitLine("// explicit bytes arm), so the two LuaJIT surfaces stay");
        emitLine("// byte-distinct exactly like the reference runtime.");
        emitLine("static $DealRt.Table __jsonShape($DealRt.Table v) { return __jsonShape(v, null, -1, -1); }");
        emitLine("static $DealRt.Table __jsonShape($DealRt.Table v, java.lang.String oFile, int oLine, int oCol) {");
        indent++;
        emitLine("java.util.Set<java.lang.Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());");
        emitLine("__jsonShapeWalk(v, seen, 0, oFile, oLine, oCol);");
        emitLine("return v;");
        indent--;
        emitLine("}");
        emitLine("static void __jsonShapeWalk(java.lang.Object v, java.util.Set<java.lang.Object> seen, int depth) { __jsonShapeWalk(v, seen, depth, null, -1, -1); }");
        emitLine("static void __jsonShapeWalk(java.lang.Object v, java.util.Set<java.lang.Object> seen, int depth, java.lang.String oFile, int oLine, int oCol) {");
        indent++;
        emitLine("if (depth > " + JSON_TABLE_DEPTH_LIMIT + ") throw new DealError(\"E8001\", \"maximum JSON nesting depth (512) exceeded\", oFile, oLine, oCol);");
        emitLine("if (v == null) return;");
        emitLine("if (v instanceof java.lang.Boolean) return;");
        emitLine("if (v instanceof java.lang.String) return;");
        emitLine("if (v instanceof java.lang.Integer) return;");
        emitLine("if (v instanceof java.lang.Long) return;");
        emitLine("if (v instanceof java.lang.Number n) {");
        indent++;
        emitLine("double d = n.doubleValue();");
        emitLine("if (java.lang.Double.isNaN(d) || java.lang.Double.isInfinite(d)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.Table t) {");
        indent++;
        emitLine("if (!seen.add(v)) throw new DealError(\"E8001\", \"cyclic value cannot be encoded as JSON\", oFile, oLine, oCol);");
        emitLine("java.util.ArrayList<java.lang.Object> arr = t.$array();");
        emitLine("if (arr != null) {");
        indent++;
        emitLine("for (java.lang.Object e : arr) __jsonShapeWalk(e, seen, depth + 1, oFile, oLine, oCol);");
        indent--;
        emitLine("} else {");
        indent++;
        emitLine("for (java.lang.Object e : t.$entries().values()) __jsonShapeWalk(e, seen, depth + 1, oFile, oLine, oCol);");
        indent--;
        emitLine("}");
        emitLine("seen.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__RefArray a) {");
        indent++;
        emitLine("if (!seen.add(v)) throw new DealError(\"E8001\", \"cyclic value cannot be encoded as JSON\", oFile, oLine, oCol);");
        emitLine("for (java.lang.Object e : a.data) __jsonShapeWalk(e, seen, depth + 1, oFile, oLine, oCol);");
        emitLine("seen.remove(v);");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__IntArray) return;");
        emitLine("if (v instanceof $DealRt.__StringArray) return;");
        emitLine("if (v instanceof $DealRt.__BooleanArray) return;");
        emitLine("if (v instanceof $DealRt.__IntOrNullArray) return;");
        emitLine("if (v instanceof $DealRt.__StringOrNullArray) return;");
        emitLine("if (v instanceof $DealRt.__BooleanOrNullArray) return;");
        emitLine("if (v instanceof $DealRt.__NumberArray a) {");
        indent++;
        emitLine("for (double e : a.data) { if (java.lang.Double.isNaN(e) || java.lang.Double.isInfinite(e)) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol); }");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof $DealRt.__NumberOrNullArray a) {");
        indent++;
        emitLine("for (java.lang.Double e : a.data) { if (e != null && (java.lang.Double.isNaN(e) || java.lang.Double.isInfinite(e))) throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol); }");
        emitLine("return;");
        indent--;
        emitLine("}");
        emitLine("if (v instanceof java.util.List<?> l) { for (java.lang.Object e : l) __jsonShapeWalk(e, seen, depth + 1, oFile, oLine, oCol); return; }");
        emitLine("if (v instanceof java.util.Map<?, ?> m) { for (java.lang.Object e : m.values()) __jsonShapeWalk(e, seen, depth + 1, oFile, oLine, oCol); return; }");
        emitLine("throw new DealError(\"E8001\", \"value is not JSON-shaped\", oFile, oLine, oCol);");
        indent--;
        emitLine("}");
        emitLine("static java.lang.String __jsonQuote(java.lang.String s) { return __jsonQuote(s, null, -1, -1); }");
        emitLine("static java.lang.String __jsonQuote(java.lang.String s, java.lang.String oFile, int oLine, int oCol) {");
        indent++;
        emitLine("// ISSUE-0302 stringify-side unpaired-surrogate scan");
        emitLine("// (std/json.lua escape parity): a string that is not");
        emitLine("// scalar-valid UTF-8 raises E8001 BEFORE any character");
        emitLine("// is emitted, so no partial output ever escapes.");
        emitLine("if (__hasUnpairedSurrogate(s)) throw new DealError(\"E8001\", \"cannot encode invalid UTF-8 as JSON\", oFile, oLine, oCol);");
        emitLine("java.lang.StringBuilder sb = new java.lang.StringBuilder(\"\\\"\");");
        emitLine("for (int i = 0; i < s.length(); i++) {");
        indent++;
        emitLine("char c = s.charAt(i);");
        emitLine("switch (c) {");
        indent++;
        emitLine("case '\"': sb.append(\"\\\\\\\"\"); break;");
        emitLine("case '\\\\': sb.append(\"\\\\\\\\\"); break;");
        emitLine("case '\\b': sb.append(\"\\\\b\"); break;");
        emitLine("case '\\f': sb.append(\"\\\\f\"); break;");
        emitLine("case '\\n': sb.append(\"\\\\n\"); break;");
        emitLine("case '\\r': sb.append(\"\\\\r\"); break;");
        emitLine("case '\\t': sb.append(\"\\\\t\"); break;");
        emitLine("default: if (c < 0x20) sb.append(java.lang.String.format(java.util.Locale.ROOT, \"\\\\u%04x\", (int) c)); else sb.append(c);");
        indent--;
        emitLine("}");
        indent--;
        emitLine("}");
        emitLine("return sb.append('\"').toString();");
        indent--;
        emitLine("}");
        emitLine("// fromJson field-value validators: null on a type mismatch (the");
        emitLine("// fromJson validation-failure contract — never a throw).");
        emitLine(int32Mode
            ? "static java.lang.Integer __jsonInt(java.lang.Object v) { if (!(v instanceof java.lang.Number)) return null; double d = ((java.lang.Number) v).doubleValue(); if (d != java.lang.Math.floor(d) || d > 2147483647.0 || d < -2147483648.0) return null; return java.lang.Integer.valueOf((int) d); }"
            : "static java.lang.Long __jsonInt(java.lang.Object v) { if (!(v instanceof java.lang.Number)) return null; double d = ((java.lang.Number) v).doubleValue(); if (d != java.lang.Math.floor(d) || d > 9007199254740991.0 || d < -9007199254740991.0) return null; return java.lang.Long.valueOf((long) d); }");
        emitLine("static java.lang.Double __jsonNumber(java.lang.Object v) { return (v instanceof java.lang.Number) ? java.lang.Double.valueOf(((java.lang.Number) v).doubleValue()) : null; }");
        emitLine("static java.lang.String __jsonString(java.lang.Object v) { return (v instanceof java.lang.String) ? (java.lang.String) v : null; }");
        emitLine("static java.lang.Boolean __jsonBoolean(java.lang.Object v) { return (v instanceof java.lang.Boolean) ? (java.lang.Boolean) v : null; }");
    }

    private void emitVariable(VariableDeclaration vd) {
        Type declaredType = vd.typeAnnotation().isPresent()
            ? resolveTypeNode(vd.typeAnnotation().get())
            : typeOf(vd.initializer());
        String javaType = javaLocalType(declaredType, vd.span());
        if (javaType == null) return;

        BoundaryOrigin declaredOrigin = vd.typeAnnotation().isPresent()
            ? originOf(vd.typeAnnotation().get(), artifactFileOf(modulePath))
            : null;
        String initializer = withBoundaryOrigin(declaredOrigin,
            () -> emitTargeted(vd.initializer(), declaredType, false));
        if (needsBooleanBoundary(vd.initializer(), declaredType)) {
            initializer = "booleanNotNull(" + initializer + ")";
        }
        initializer = coerceNullValueCode(initializer, vd.initializer(),
            javaType, vd.span());
        initializer = adaptIntBoundary(vd.initializer(), initializer,
            declaredType);
        String visibility = moduleLevel ? "static " : "";
        String javaVar = declareLocal(vd.name(), declaredType);

        boolean cell = !moduleLevel && isCapturedMapped(javaVar);
        if (cell) {
            javaType = javaType + "[]";
        }
        if (moduleLevel) {
            if (preStatementsDeclareTemps) {
                // The initializer references a temporary declared by the
                // hoisted pre-statements (a guarded && / || lowering); a
                // class-body initializer cannot reference a local of a
                // separate static block. Declare the field uninitialized
                // and assign it inside the same static block, in source
                // order.
                emitLine(visibility + javaType + " " + javaVar + ";");
                emitLine("static {");
                indent++;
                flushPreStatements();
                emitLine(javaVar + " = " + initializer + ";");
                indent--;
                emitLine("}");
                return;
            }
            // Hoisted side effects of a field initializer (e.g.
            // `let z: null = console.log("x")`) cannot stand bare in the
            // class body; wrap them in a static initializer emitted before
            // the field declaration, preserving source order.
            if (!preStatements.isEmpty()) {
                emitLine("static {");
                indent++;
                flushPreStatements();
                indent--;
                emitLine("}");
            }
        } else {
            // Emit the hoisted side effects before the declaration line so
            // they still see the pre-declaration scope (a shadowed
            // initializer's RHS binds to the enclosing variable).
            flushPreStatements();
        }
        if (cell) {
            emitLine(visibility + "final " + javaType + " " + javaVar
                + "$c = { " + initializer + " };");
        } else {
            emitLine(visibility + javaType + " " + javaVar + " = "
                + initializer + ";");
        }
    }

    private boolean isCapturedMapped(String mapped) {
        for (Set<String> frame : capturedMappedStack) {
            if (frame.contains(mapped)) return true;
        }
        return false;
    }

    /**
     * Collects every name a {@code let}/{@code for (let)}/{@code for-of}
     * variable or block-level function DECLARES anywhere inside
     * {@code stmts} (recursively through blocks, if/while/for/try bodies
     * — not through function bodies, which own their declarations).
     */
    private void collectLocalDeclarations(List<StatementNode> stmts,
            Set<String> out) {
        for (StatementNode stmt : stmts) {
            switch (stmt) {
                case VariableDeclaration vd -> out.add(vd.name());
                case Block b -> collectLocalDeclarations(b.statements(), out);
                case IfStatement is -> {
                    collectLocalDeclarations(is.thenBlock().statements(), out);
                    if (is.elseBranch().isPresent()) {
                        switch (is.elseBranch().get()) {
                            case Either.Left<IfStatement, Block> left ->
                                collectLocalDeclarations(
                                    List.of(left.value()), out);
                            case Either.Right<IfStatement, Block> right ->
                                collectLocalDeclarations(
                                    right.value().statements(), out);
                        }
                    }
                }
                case WhileStatement ws ->
                    collectLocalDeclarations(ws.body().statements(), out);
                case ForStatement fs -> {
                    if (fs.init().isPresent()
                            && fs.init().get() instanceof ForInit.VarDecl vd) {
                        out.add(vd.decl().name());
                    }
                    collectLocalDeclarations(fs.body().statements(), out);
                }
                case ForOfStatement fos -> {
                    out.add(fos.varName());
                    collectLocalDeclarations(fos.body().statements(), out);
                }
                case TryStatement ts -> {
                    // The catch variable DECLARES a binding (the checker
                    // scopes it to the catch block): nested functions
                    // inside the catch block that read or write it must
                    // mark it captured so emitTry cell-ifies it exactly
                    // like a local or parameter.
                    out.add(ts.catchVar());
                    collectLocalDeclarations(ts.tryBlock().statements(), out);
                    collectLocalDeclarations(ts.catchBlock().statements(), out);
                }
                case FunctionDeclaration fd -> out.add(fd.name());
                default -> { }
            }
        }
    }

    /**
     * Walks {@code stmts} for nested functions (function expressions and
     * block-level function declarations, at ANY depth — through
     * expressions and nested bodies) and adds to {@code captured} every
     * identifier they reference that {@code visibleDeclared} names. The
     * analysis is a conservative superset: a shadowed same-named binding
     * may be cell-ified too, which changes no observable behavior.
     */
    private void walkNestedFunctions(List<StatementNode> stmts,
            Set<String> visibleDeclared, Set<String> captured) {
        for (StatementNode stmt : stmts) {
            switch (stmt) {
                case VariableDeclaration vd ->
                    walkExprNestedFunctions(vd.initializer(), visibleDeclared,
                        captured);
                case ExpressionStatement es ->
                    walkExprNestedFunctions(es.expr(), visibleDeclared,
                        captured);
                case ReturnStatement rs -> {
                    if (rs.expr().isPresent()) {
                        walkExprNestedFunctions(rs.expr().get(),
                            visibleDeclared, captured);
                    }
                }
                case ThrowStatement ts ->
                    walkExprNestedFunctions(ts.expr(), visibleDeclared,
                        captured);
                case DeleteStatement ds ->
                    walkExprNestedFunctions(ds.target(), visibleDeclared,
                        captured);
                case Block b ->
                    walkNestedFunctions(b.statements(), visibleDeclared,
                        captured);
                case IfStatement is -> {
                    walkExprNestedFunctions(is.condition(), visibleDeclared,
                        captured);
                    walkNestedFunctions(is.thenBlock().statements(),
                        visibleDeclared, captured);
                    if (is.elseBranch().isPresent()) {
                        switch (is.elseBranch().get()) {
                            case Either.Left<IfStatement, Block> left -> {
                                walkExprNestedFunctions(left.value().condition(),
                                    visibleDeclared, captured);
                                walkNestedFunctions(
                                    left.value().thenBlock().statements(),
                                    visibleDeclared, captured);
                                if (left.value().elseBranch().isPresent()) {
                                    walkNestedFunctions(
                                        List.of(new IfStatement(
                                            left.value().span(),
                                            left.value().condition(),
                                            left.value().thenBlock(),
                                            left.value().elseBranch())),
                                        visibleDeclared, captured);
                                }
                            }
                            case Either.Right<IfStatement, Block> right ->
                                walkNestedFunctions(
                                    right.value().statements(),
                                    visibleDeclared, captured);
                        }
                    }
                }
                case WhileStatement ws -> {
                    walkExprNestedFunctions(ws.condition(), visibleDeclared,
                        captured);
                    walkNestedFunctions(ws.body().statements(),
                        visibleDeclared, captured);
                }
                case ForStatement fs -> {
                    if (fs.init().isPresent()
                            && fs.init().get() instanceof ForInit.AssignExpr ae) {
                        walkExprNestedFunctions(ae.expr(), visibleDeclared,
                            captured);
                    }
                    if (fs.condition().isPresent()) {
                        walkExprNestedFunctions(fs.condition().get(),
                            visibleDeclared, captured);
                    }
                    if (fs.update().isPresent()) {
                        walkExprNestedFunctions(fs.update().get(),
                            visibleDeclared, captured);
                    }
                    walkNestedFunctions(fs.body().statements(),
                        visibleDeclared, captured);
                }
                case ForOfStatement fos -> {
                    walkExprNestedFunctions(fos.iterable(), visibleDeclared,
                        captured);
                    walkNestedFunctions(fos.body().statements(),
                        visibleDeclared, captured);
                }
                case TryStatement ts -> {
                    walkNestedFunctions(ts.tryBlock().statements(),
                        visibleDeclared, captured);
                    walkNestedFunctions(ts.catchBlock().statements(),
                        visibleDeclared, captured);
                }
                case FunctionDeclaration fd ->
                    processNestedFunction(fd.params(), fd.body(),
                        visibleDeclared, captured);
                default -> { }
            }
        }
    }

    /** Expression-side of the nested-function walk: recurses through every
     * subexpression and hands each function expression to
     * {@link #processNestedFunction}. */
    private void walkExprNestedFunctions(ExpressionNode e,
            Set<String> visibleDeclared, Set<String> captured) {
        if (e instanceof FunctionExpr fe) {
            processNestedFunction(fe.params(), fe.body(), visibleDeclared,
                captured);
            return;
        }
        if (e instanceof BinaryExpr bin) {
            walkExprNestedFunctions(bin.left(), visibleDeclared, captured);
            walkExprNestedFunctions(bin.right(), visibleDeclared, captured);
        } else if (e instanceof UnaryExpr u) {
            walkExprNestedFunctions(u.expr(), visibleDeclared, captured);
        } else if (e instanceof CallExpr call) {
            walkExprNestedFunctions(call.callee(), visibleDeclared, captured);
            for (ExpressionNode arg : call.args()) {
                walkExprNestedFunctions(arg, visibleDeclared, captured);
            }
        } else if (e instanceof MemberAccessExpr mae) {
            walkExprNestedFunctions(mae.object(), visibleDeclared, captured);
        } else if (e instanceof IndexExpr idx) {
            walkExprNestedFunctions(idx.array(), visibleDeclared, captured);
            walkExprNestedFunctions(idx.index(), visibleDeclared, captured);
        } else if (e instanceof ArrayLiteralExpr al) {
            for (ExpressionNode el : al.elements()) {
                walkExprNestedFunctions(el, visibleDeclared, captured);
            }
        } else if (e instanceof ObjectLiteralExpr ol) {
            for (Property prop : ol.properties()) {
                walkExprNestedFunctions(prop.value(), visibleDeclared,
                    captured);
            }
        } else if (e instanceof HasExpr he) {
            walkExprNestedFunctions(he.object(), visibleDeclared, captured);
        } else if (e instanceof TemplateLiteralExpr tl) {
            for (ExpressionNode part : tl.parts()) {
                walkExprNestedFunctions(part, visibleDeclared, captured);
            }
        } else if (e instanceof AwaitExpression aw) {
            walkExprNestedFunctions(aw.callee(), visibleDeclared, captured);
        } else if (e instanceof AssignmentExpr ae) {
            walkExprNestedFunctions(ae.target(), visibleDeclared, captured);
            walkExprNestedFunctions(ae.value(), visibleDeclared, captured);
        }
    }

    /** One nested function: its free identifiers referencing
     * {@code visibleDeclared} bindings become captures, and its body is
     * walked recursively for deeper nesting. */
    private void processNestedFunction(List<Parameter> params, Block body,
            Set<String> visibleDeclared, Set<String> captured) {
        Set<String> own = new LinkedHashSet<>();
        collectLocalDeclarations(body.statements(), own);
        for (Parameter p : params) own.add(p.name());
        Set<String> free = new LinkedHashSet<>();
        collectIdentifierNames(body, free);
        for (String n : free) {
            if (visibleDeclared.contains(n) && !own.contains(n)) {
                captured.add(n);
            }
        }
        Set<String> deeper = new LinkedHashSet<>(visibleDeclared);
        deeper.addAll(own);
        walkNestedFunctions(body.statements(), deeper, captured);
    }

    /** Collects every identifier name appearing in the block's
     * expressions (not descending into nested function bodies — those are
     * separate nested functions). */
    private void collectIdentifierNames(Block b, Set<String> out) {
        for (StatementNode stmt : b.statements()) {
            collectStmtIdentifierNames(stmt, out);
        }
    }

    private void collectStmtIdentifierNames(StatementNode stmt,
            Set<String> out) {
        switch (stmt) {
            case VariableDeclaration vd ->
                collectExprIdentifierNames(vd.initializer(), out);
            case ExpressionStatement es ->
                collectExprIdentifierNames(es.expr(), out);
            case ReturnStatement rs -> {
                if (rs.expr().isPresent()) {
                    collectExprIdentifierNames(rs.expr().get(), out);
                }
            }
            case ThrowStatement ts ->
                collectExprIdentifierNames(ts.expr(), out);
            case DeleteStatement ds ->
                collectExprIdentifierNames(ds.target(), out);
            case Block b -> collectIdentifierNames(b, out);
            case IfStatement is -> {
                collectExprIdentifierNames(is.condition(), out);
                collectIdentifierNames(is.thenBlock(), out);
                if (is.elseBranch().isPresent()) {
                    switch (is.elseBranch().get()) {
                        case Either.Left<IfStatement, Block> left -> {
                            // Re-descend the whole else-if chain so every
                            // link's condition/body/else contributes.
                            collectStmtIdentifierNames(left.value(), out);
                        }
                        case Either.Right<IfStatement, Block> right ->
                            collectIdentifierNames(right.value(), out);
                    }
                }
            }
            case WhileStatement ws -> {
                collectExprIdentifierNames(ws.condition(), out);
                collectIdentifierNames(ws.body(), out);
            }
            case ForStatement fs -> {
                if (fs.init().isPresent()
                        && fs.init().get() instanceof ForInit.AssignExpr ae) {
                    collectExprIdentifierNames(ae.expr(), out);
                }
                if (fs.condition().isPresent()) {
                    collectExprIdentifierNames(fs.condition().get(), out);
                }
                if (fs.update().isPresent()) {
                    collectExprIdentifierNames(fs.update().get(), out);
                }
                collectIdentifierNames(fs.body(), out);
            }
            case ForOfStatement fos -> {
                collectExprIdentifierNames(fos.iterable(), out);
                collectIdentifierNames(fos.body(), out);
            }
            case TryStatement ts -> {
                collectIdentifierNames(ts.tryBlock(), out);
                collectIdentifierNames(ts.catchBlock(), out);
            }
            default -> { }
        }
    }

    private void collectExprIdentifierNames(ExpressionNode e, Set<String> out) {
        if (e instanceof FunctionExpr fe) {
            return; // a nested function owns its identifiers
        }
        if (e instanceof IdentifierExpr id) {
            out.add(id.name());
        } else if (e instanceof BinaryExpr bin) {
            collectExprIdentifierNames(bin.left(), out);
            collectExprIdentifierNames(bin.right(), out);
        } else if (e instanceof UnaryExpr u) {
            collectExprIdentifierNames(u.expr(), out);
        } else if (e instanceof CallExpr call) {
            collectExprIdentifierNames(call.callee(), out);
            for (ExpressionNode arg : call.args()) {
                collectExprIdentifierNames(arg, out);
            }
        } else if (e instanceof MemberAccessExpr mae) {
            collectExprIdentifierNames(mae.object(), out);
        } else if (e instanceof IndexExpr idx) {
            collectExprIdentifierNames(idx.array(), out);
            collectExprIdentifierNames(idx.index(), out);
        } else if (e instanceof ArrayLiteralExpr al) {
            for (ExpressionNode el : al.elements()) {
                collectExprIdentifierNames(el, out);
            }
        } else if (e instanceof ObjectLiteralExpr ol) {
            for (Property prop : ol.properties()) {
                collectExprIdentifierNames(prop.value(), out);
            }
        } else if (e instanceof HasExpr he) {
            collectExprIdentifierNames(he.object(), out);
        } else if (e instanceof TemplateLiteralExpr tl) {
            for (ExpressionNode part : tl.parts()) {
                collectExprIdentifierNames(part, out);
            }
        } else if (e instanceof AwaitExpression aw) {
            collectExprIdentifierNames(aw.callee(), out);
        } else if (e instanceof AssignmentExpr ae) {
            collectExprIdentifierNames(ae.target(), out);
            collectExprIdentifierNames(ae.value(), out);
        }
    }

    private void emitFunction(FunctionDeclaration fd, boolean exported) {

        if (!moduleLevel) {

            emitBlockFunction(fd);
            return;
        }
        Type returnType = resolveTypeNode(fd.returnType());
        String javaReturn = javaReturnType(returnType, fd.returnType().span());
        if (javaReturn == null) return;

        List<String> paramTypes = new ArrayList<>();
        boolean ok = true;
        for (Parameter p : fd.params()) {
            Type pt = resolveTypeNode(p.type());
            String jt = javaLocalType(pt, p.type().span());
            if (jt == null) { ok = false; break; }
            paramTypes.add(jt);
        }
        if (!ok) return;

        // A DEAL function whose name and mapped signature collide with an
        // emitted runtime helper would produce a duplicate Java method —
        // every emitted overload of a helper is guarded (the short form
        // and the origin-threading form alike).
        String javaFn = javaName(fd.name());
        List<List<String>> helperSignatures = runtimeHelperSignatures().get(javaFn);
        if (helperSignatures != null && helperSignatures.contains(paramTypes)) {
            unsupported("function '" + fd.name() + "' whose signature "
                + "collides with the emitted runtime helper '" + javaFn + "'",
                fd.span());
            return;
        }

        // A pre-declaration function accessing a later-declared module
        // field is rejected (write-dominance analysis):
        //  - a WRITE binds to LuaJIT's GLOBAL of the same name (the
        //    module-local does not exist when the function value is
        //    created), leaving the module-local untouched so later readers
        //    observe the initializer value, while Java would write the
        //    static field and pollute every later reader — the
        //    write-then-read shape (`x = 5; return x;`) is included: the
        //    function's own read observes the global write under LuaJIT,
        //    but the polluted Java field remains observable by later
        //    readers;
        //  - a READ without a dominating write inside the function reads
        //    the global nil at call time and fails (E8001) while Java
        //    would silently read the initialized static field (writes via
        //    called functions and taken-only branches do not establish
        //    dominance — conservative).
        String forwardWriteViolation = forwardWriteViolations.get(fd.name());
        if (forwardWriteViolation != null) {
            unsupported("function '" + fd.name() + "' writing the module "
                + "field '" + forwardWriteViolation + "' declared after the "
                + "function (LuaJIT binds the pre-declaration write to the "
                + "GLOBAL of the same name — the module-local does not exist "
                + "when the function value is created — leaving the "
                + "module-local untouched so later readers observe the "
                + "initializer value; Java would write the static field and "
                + "pollute every later reader; the write-then-read shape is "
                + "included)", fd.span());
            return;
        }
        String forwardReadViolation = forwardReadViolations.get(fd.name());
        if (forwardReadViolation != null) {
            unsupported("function '" + fd.name() + "' reading the module "
                + "field '" + forwardReadViolation + "' declared after the "
                + "function without a dominating write inside the function "
                + "(LuaJIT reads the global nil at call time and fails with "
                + "E8001; Java would silently read the initialized static "
                + "field — a write via a called function does not establish "
                + "dominance, conservatively)", fd.span());
            return;
        }

        Symbol fnSym = symbols.resolve(fd.name());
        if (fnSym instanceof Symbol.FunctionSymbol fs
                && fs.funcType() != null) {
            Type.Func funcType = fs.funcType();
            String shape = registerWrapperShape(funcType);
            emitLine("static final " + shape + " " + javaFn + "$fn = new "
                + shape + "() {");
            indent++;
            emitLine("@Override");
            StringBuilder inv = new StringBuilder(javaReturn)
                .append(" invoke(");
            for (int i = 0; i < funcType.paramTypes().size(); i++) {
                if (i > 0) inv.append(", ");
                inv.append(javaLocalType(funcType.paramTypes().get(i),
                    fd.span())).append(" p").append(i);
            }
            inv.append(") { ");
            if (returnType instanceof Type.Null) {
                inv.append(qualifiedStatic(javaFn)).append("(");
            } else {
                inv.append("return ").append(qualifiedStatic(javaFn)).append("(");
            }
            for (int i = 0; i < funcType.paramTypes().size(); i++) {
                if (i > 0) inv.append(", ");
                inv.append("p").append(i);
            }
            inv.append("); }");
            emitLine(inv.toString());
            indent--;
            emitLine("};");
        }

        Set<String> topDeclared = new LinkedHashSet<>();
        collectLocalDeclarations(fd.body().statements(), topDeclared);
        for (Parameter p : fd.params()) topDeclared.add(p.name());
        Set<String> topCaptured = new LinkedHashSet<>();
        walkNestedFunctions(fd.body().statements(), topDeclared, topCaptured);
        capturedNamesStack.push(topCaptured);
        capturedMappedStack.push(new LinkedHashSet<>());

        // Declare the parameters in a fresh scope BEFORE building the
        // signature: the signature must use each parameter's DECLARED
        // (possibly disambiguated) Java name, not the raw {@link #javaName}
        // translation. A parameter that shadows a module field (or another
        // visible binding) gets a {@code $n} suffix from
        // {@link #declareLocal}; emitting the raw name in the signature
        // while the body reads the suffixed name produced an artifact javac
        // rejected after the CLI reported success ({@code static long
        // f(long x) { return intAdd(x$1, 1L); }} — cannot find symbol
        // x$1).
        Map<String, String> paramScope = new LinkedHashMap<>();
        Map<String, Type> paramTypeScope = new LinkedHashMap<>();
        localScopes.push(paramScope);
        localTypeScopes.push(paramTypeScope);
        functionBindingNames.push(new LinkedHashSet<>());
        List<String> paramNames = new ArrayList<>();
        for (Parameter p : fd.params()) {
            Type pt = resolveTypeNode(p.type());
            paramNames.add(declareLocal(p.name(), pt));
        }

        StringBuilder sig = new StringBuilder();
        if (exported) sig.append("public ");
        sig.append("static ").append(javaReturn).append(' ')
            .append(javaFn).append('(');
        for (int i = 0; i < fd.params().size(); i++) {
            if (i > 0) sig.append(", ");
            sig.append(paramTypes.get(i)).append(' ')
                .append(paramNames.get(i));
        }
        sig.append(") {");
        emitLine(sig.toString());
        indent++;
        // Captured parameters lower to cells: declare the cell right
        // after the signature so every body read/write routes through
        // <name>$c[0] (localJavaName).
        for (int i = 0; i < fd.params().size(); i++) {
            if (isCapturedMapped(paramNames.get(i))) {
                emitLine("final " + paramTypes.get(i) + "[] "
                    + paramNames.get(i) + "$c = { " + paramNames.get(i)
                    + " };");
            }
        }
        boolean savedModuleLevel = moduleLevel;
        int savedModuleIndex = currentModuleStatementIndex;
        Type savedReturnType = currentReturnType;
        currentReturnType = returnType;
        currentModuleStatementIndex = -1;
        moduleLevel = false;
        List<StatementNode> savedBody = currentFunctionBody;
        List<String> savedParams = currentFunctionParams;
        currentFunctionBody = fd.body().statements();
        List<String> dealParams = new ArrayList<>();
        for (Parameter p : fd.params()) {
            dealParams.add(p.name());
        }
        currentFunctionParams = dealParams;
        for (StatementNode stmt : fd.body().statements()) {
            emitStatement(stmt);
            if (!statementCompletesNormally(stmt)) {
                // Dead code after a non-completing statement: LuaJIT never
                // executes it and javac rejects it as unreachable
                // (JLS §14.21) — skip the rest of the body.
                flushPreStatements(); // defensive: empty at a statement boundary
                break;
            }
        }
        currentFunctionBody = savedBody;
        currentFunctionParams = savedParams;
        currentReturnType = savedReturnType;
        currentModuleStatementIndex = savedModuleIndex;
        moduleLevel = savedModuleLevel;
        currentReturnType = savedReturnType;
        localScopes.pop();
        localTypeScopes.pop();
        functionBindingNames.pop();
        capturedNamesStack.pop();
        capturedMappedStack.pop();

        indent--;
        emitLine("}");
    }

    private void emitBlockFunction(FunctionDeclaration fd) {
        if (capturedMappedStack.isEmpty()) {
            // A module-level static block cannot hold a block-level
            // function whose binding is a method-local cell — E6000,
            // never a broken artifact. (Function-local blocks always
            // have the enclosing function's capture frame.)
            unsupported("block-level functions at module level", fd.span());
            return;
        }
        Type returnType = resolveTypeNode(fd.returnType());
        String javaReturn = javaReturnType(returnType, fd.returnType().span());
        if (javaReturn == null) return;
        List<Type> paramTypes = new ArrayList<>();
        boolean ok = true;
        for (Parameter p : fd.params()) {
            Type pt = resolveTypeNode(p.type());
            if (javaLocalType(pt, p.type().span()) == null) {
                ok = false;
                break;
            }
            paramTypes.add(pt);
        }
        if (!ok) return;

        Type.Func funcType = new Type.Func(paramTypes, returnType,
            fd.isAsync());
        String shape = registerWrapperShape(funcType);
        if (shape == null) {
            unsupported("function values whose signature contains "
                + "unsupported carriers (table | null positions and "
                + "host-class carriers — deferred to the ISSUE-0110 "
                + "descriptor join)", fd.span());
            return;
        }
        String mapped = declareLocal(fd.name(), funcType);
        // The block-function binding is ALWAYS a cell (self-recursion
        // needs it even when nothing else captures the name).
        capturedMappedStack.peek().add(mapped);
        String cell = mapped + "$c";
        emitLine("final " + shape + "[] " + cell + " = new " + shape
            + "[1];");
        emitLine(cell + "[0] = " + emitInlineFunctionExpr(shape, javaReturn,
            fd.params(), fd.body(), returnType, fd.span()) + ";");
    }

    private String emitFunctionExpr(FunctionExpr fe) {

        Type t = typeOf(fe);
        if (!(t instanceof Type.Func ft)) {
            unsupported("function expression without a function type",
                fe.span());
            return "null";
        }
        String shape = registerWrapperShape(ft);
        if (shape == null) {
            unsupported("function values whose signature contains "
                + "unsupported carriers (table | null positions and "
                + "host-class carriers — deferred to the ISSUE-0110 "
                + "descriptor join)", fe.span());
            return "null";
        }
        Type returnType = ft.returnType();
        String javaReturn = javaReturnType(returnType, fe.span());
        if (javaReturn == null) return "null";
        return emitInlineFunctionExpr(shape, javaReturn, fe.params(),
            fe.body(), returnType, fe.span());
    }

    /**
     * Emits an anonymous wrapper subclass as a multi-line inline
     * expression: the {@code invoke} signature uses each parameter's
     * DECLARED (disambiguated) Java name, captured parameters lower to
     * cells, and the body statements emit into a temporary buffer at a
     * consistent indentation (hoisted pre-statements flush INSIDE the
     * body, where their temporaries belong).
     */
    private String emitInlineFunctionExpr(String shape, String javaReturn,
            List<Parameter> params, Block body, Type returnType,
            Span span) {
        String pad = "    ";
        int base = indent;
        List<String> paramTypes = new ArrayList<>();
        for (Parameter p : params) {
            String jt = javaLocalType(resolveTypeNode(p.type()), p.span());
            if (jt == null) return "null";
            paramTypes.add(jt);
        }
        // The body's own capture frame + parameter scope exist BEFORE the
        // parameter declarations, so declareLocal registers captured
        // parameters into the right frame.
        Set<String> own = new LinkedHashSet<>();
        collectLocalDeclarations(body.statements(), own);
        for (Parameter p : params) own.add(p.name());
        Set<String> capturedHere = new LinkedHashSet<>();
        walkNestedFunctions(body.statements(), own, capturedHere);
        capturedNamesStack.push(capturedHere);
        capturedMappedStack.push(new LinkedHashSet<>());
        localScopes.push(new LinkedHashMap<>());
        localTypeScopes.push(new LinkedHashMap<>());
        functionBindingNames.push(new LinkedHashSet<>());
        List<String> paramNames = new ArrayList<>();
        for (Parameter p : params) {
            paramNames.add(declareLocal(p.name(), resolveTypeNode(p.type())));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("new ").append(shape).append("() {\n");
        sb.append(pad.repeat(base + 1)).append("@Override\n");
        sb.append(pad.repeat(base + 1)).append(javaReturn)
            .append(" invoke(");
        for (int i = 0; i < paramNames.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(paramTypes.get(i)).append(' ')
                .append(paramNames.get(i));
        }
        sb.append(") {\n");

        StringBuilder savedOut = out;
        int savedIndent = indent;
        Type savedReturnType = currentReturnType;
        List<StatementNode> savedBody = currentFunctionBody;
        List<String> savedParams = currentFunctionParams;
        int savedModuleIndex = currentModuleStatementIndex;
        out = sb;
        indent = base + 2;
        currentReturnType = returnType;
        currentModuleStatementIndex = -1;
        currentFunctionBody = body.statements();
        List<String> dealParams = new ArrayList<>();
        for (Parameter p : params) dealParams.add(p.name());
        currentFunctionParams = dealParams;
        for (int i = 0; i < paramNames.size(); i++) {
            if (isCapturedMapped(paramNames.get(i))) {
                emitLine("final " + paramTypes.get(i) + "[] "
                    + paramNames.get(i) + "$c = { " + paramNames.get(i)
                    + " };");
            }
        }
        for (StatementNode stmt : body.statements()) {
            emitStatement(stmt);
            if (!statementCompletesNormally(stmt)) {
                // Dead code after a non-completing statement (JLS
                // §14.21): skip the rest, exactly like emitFunction.
                flushPreStatements();
                break;
            }
        }
        currentFunctionBody = savedBody;
        currentFunctionParams = savedParams;
        currentReturnType = savedReturnType;
        currentModuleStatementIndex = savedModuleIndex;
        out = savedOut;
        indent = savedIndent;
        localScopes.pop();
        localTypeScopes.pop();
        functionBindingNames.pop();
        capturedNamesStack.pop();
        capturedMappedStack.pop();

        sb.append(pad.repeat(base + 1)).append("}\n");
        sb.append(pad.repeat(base)).append("}");
        return sb.toString();
    }

    private void emitReturn(ReturnStatement rs) {
        if (rs.expr().isEmpty()) {
            emitLine("return;");
            return;
        }
        ExpressionNode e = rs.expr().get();
        Type t = typeOf(e);
        boolean retNullable = currentReturnType instanceof Type.Nullable;
        if (t instanceof Type.Null) {
            if (isBareNullLiteral(e) || e instanceof IdentifierExpr) {
                // The null literal and a null-typed variable read have no
                // observable side effects — and a bare identifier is not a
                // valid Java expression statement. In a nullable-returning
                // function the DEAL null is the Java null reference (a
                // bare `return;` would make javac reject a non-void
                // method).
                emitLine(retNullable ? "return null;" : "return;");
                return;
            }
            // Any other null-typed return expression is a side-effecting
            // call or assignment (console.log/console.error, a
            // null-returning function) — LuaJIT evaluates it before
            // returning. Evaluate it first, then return; discarding it
            // would silently drop its output. Assignments must be emitted
            // without parentheses: a parenthesized assignment is not a
            // valid Java expression statement (JLS §14.8). Calls are
            // hoisted into pre-statements by emitExpression.
            if (e instanceof AssignmentExpr ae) {
                String core = emitAssignmentCore(ae);
                flushPreStatements();
                emitLine(core + ";");
            } else {
                emitExpression(e);
                flushPreStatements();
            }
            emitLine(retNullable ? "return null;" : "return;");
            return;
        }
        // D1/D3: a return is a declared boundary — the returned
        // expression's own start is the authoritative origin of every
        // boundary raise it crosses (the caller's return-expression
        // class).
        String value = withBoundaryOrigin(
            originOfExpression(e, artifactFileOf(modulePath)),
            () -> emitTargeted(e, currentReturnType, true));
        value = adaptIntBoundary(e, value, currentReturnType);
        if (needsBooleanBoundary(e, currentReturnType)) {
            // The boundary keys on the DECLARED return type: a
            // nil-capable boolean result crossing into a
            // `boolean | null` return is the DEAL null (LuaJIT's
            // check_nullable stores it, no failure), while a `boolean`
            // return fails with E8001 exactly where LuaJIT's
            // check_boolean fails.
            value = "booleanNotNull(" + value + ")";
        }
        flushPreStatements();
        emitLine("return " + value + ";");
    }

    /**
     * True when {@code stmt} can complete normally — i.e. execution can
     * fall through to the next statement. Mirrors JLS §14.21's
     * reachability rule (and Lua's actual execution): a {@code return}
     * cannot complete normally; an {@code if}/{@code else} whose branches
     * all cannot complete normally cannot complete normally (an
     * {@code if} without {@code else} always can); a block cannot
     * complete normally when its last statement cannot. Statements after
     * a non-completing statement are dead code — LuaJIT never executes
     * them and javac rejects them as unreachable — so emitters skip them
     * instead of producing an artifact the CLI would report as success.
     */
    private static boolean statementCompletesNormally(StatementNode stmt) {
        return switch (stmt) {
            case ReturnStatement rs -> false;

            case ThrowStatement ts -> false;
            // break/continue transfer control and cannot complete
            // normally either (JLS §14.21).
            case BreakStatement bs -> false;
            case ContinueStatement cs -> false;
            case Block b -> {
                List<StatementNode> body = b.statements();
                yield body.isEmpty()
                    || statementCompletesNormally(body.get(body.size() - 1));
            }
            case IfStatement is -> {
                if (statementCompletesNormally(is.thenBlock())) {
                    yield true;
                }
                if (is.elseBranch().isEmpty()) {
                    yield true;
                }
                yield switch (is.elseBranch().get()) {
                    case Either.Left<IfStatement, Block> left ->
                        statementCompletesNormally(left.value());
                    case Either.Right<IfStatement, Block> right ->
                        statementCompletesNormally(right.value());
                };
            }
            // The emitted loop routes its condition through the loopCond
            // helper, so javac never sees a constant-expression condition:
            // per JLS §14.21 the statement can complete normally (and
            // statements after it stay reachable) unless the condition is
            // constant-true — which the helper wrapper makes impossible.
            case WhileStatement ws -> true;
            // JLS §14.21: a try statement can complete normally iff the
            // try block can complete normally OR the catch block can.
            case TryStatement ts ->
                statementCompletesNormally(ts.tryBlock())
                    || statementCompletesNormally(ts.catchBlock());
            // Every other statement kind the skeleton emits completes
            // normally; unsupported kinds are rejected with E6000 when
            // emission reaches them.
            default -> true;
        };
    }

    private void emitIf(IfStatement is) {
        String condition = emitExpression(is.condition());
        if (needsBooleanBoundary(is.condition(), Type.Boolean.INSTANCE)) {
            condition = "booleanNotNull(" + condition + ")";
        }
        flushPreStatements();
        emitLine("if (" + condition + ") {");
        indent++;
        emitScopedBlock(is.thenBlock());
        indent--;
        if (is.elseBranch().isPresent()) {
            switch (is.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left -> {
                    // Java requires the head block to be closed before "else".
                    String elseCondition = emitExpression(left.value().condition());
                    if (needsBooleanBoundary(left.value().condition(),
                            Type.Boolean.INSTANCE)) {
                        elseCondition = "booleanNotNull(" + elseCondition + ")";
                    }
                    if (preStatements.isEmpty()) {
                        emitLine("} else if (" + elseCondition + ") {");
                        indent++;
                        emitScopedBlock(left.value().thenBlock());
                        indent--;
                        emitIfContinuation(left.value());
                    } else {
                        // A condition with hoisted side effects cannot place
                        // statements between `}` and `else`; nest the chain
                        // in a plain else block instead.
                        emitLine("} else {");
                        indent++;
                        flushPreStatements();
                        emitLine("if (" + elseCondition + ") {");
                        indent++;
                        emitScopedBlock(left.value().thenBlock());
                        indent--;
                        emitIfContinuation(left.value());
                        indent--;
                        emitLine("}");
                    }
                }
                case Either.Right<IfStatement, Block> right -> {
                    emitLine("} else {");
                    indent++;
                    emitScopedBlock(right.value());
                    indent--;
                    emitLine("}");
                }
            }
        } else {
            emitLine("}");
        }
    }

    /** Emits the tail of an else-if chain whose head was already opened. */
    private void emitIfContinuation(IfStatement is) {
        if (is.elseBranch().isPresent()) {
            switch (is.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left -> {
                    String condition = emitExpression(left.value().condition());
                    if (needsBooleanBoundary(left.value().condition(),
                            Type.Boolean.INSTANCE)) {
                        condition = "booleanNotNull(" + condition + ")";
                    }
                    if (preStatements.isEmpty()) {
                        emitLine("} else if (" + condition + ") {");
                        indent++;
                        emitScopedBlock(left.value().thenBlock());
                        indent--;
                        emitIfContinuation(left.value());
                    } else {
                        // Hoisted condition side effects: nest the chain
                        // (no statements may sit between `}` and `else`).
                        emitLine("} else {");
                        indent++;
                        flushPreStatements();
                        emitLine("if (" + condition + ") {");
                        indent++;
                        emitScopedBlock(left.value().thenBlock());
                        indent--;
                        emitIfContinuation(left.value());
                        indent--;
                        emitLine("}");
                    }
                }
                case Either.Right<IfStatement, Block> right -> {
                    emitLine("} else {");
                    indent++;
                    emitScopedBlock(right.value());
                    indent--;
                    emitLine("}");
                }
            }
        } else {
            emitLine("}");
        }
    }

    private void emitForOf(ForOfStatement fos) {
        Type iterableType = typeOf(fos.iterable());
        if (iterableType instanceof Type.String) {
            emitStringForOf(fos);
            return;
        }
        if (iterableType instanceof Type.Array arr) {
            emitArrayForOf(fos, arr);
            return;
        }
        unsupported("for-of over " + typeName(iterableType)
            + " (only arrays and strings iterate)", fos.span());
    }

    private void emitStringForOf(ForOfStatement fos) {
        String iterable = emitExpression(fos.iterable());
        int n = forOfCounter++;
        String iterVar = "__iter" + n;
        String idxVar = "__i" + n;
        String cpVar = "__cp" + n;
        // The iterated expression evaluates exactly once, before the loop
        // (LuaJIT evaluates the for-of expression once). Hoisted side
        // effects of its evaluation run before the materialization line.
        flushPreStatements();
        emitLine("java.lang.String " + iterVar + " = " + iterable + ";");
        emitLine("for (long " + idxVar + " = 0L; " + idxVar + " < "
            + iterVar + ".length(); ) {");
        indent++;
        emitLine("int " + cpVar + " = " + iterVar + ".codePointAt((int) "
            + idxVar + ");");
        localScopes.push(new LinkedHashMap<>());
        localTypeScopes.push(new LinkedHashMap<>());
        String loopVar = declareLocal(fos.varName(), Type.String.INSTANCE);
        emitLine("java.lang.String " + loopVar
            + " = new java.lang.String(java.lang.Character.toChars("
            + cpVar + "));");
        emitLine(idxVar + " += (long) java.lang.Character.charCount("
            + cpVar + ");");
        emitForOfLoopBody(fos, loopVar);
        localScopes.pop();
        localTypeScopes.pop();
        indent--;
        emitLine("}");
    }

    private void emitArrayForOf(ForOfStatement fos, Type.Array arr) {
        String iterable = emitExpression(fos.iterable());
        String wrapper = arrayWrapperName(arr.element());
        if (wrapper == null) {
            javaArrayElementType(arr.element(), fos.span());
            return;
        }
        Type element = arr.element();
        int n = forOfCounter++;
        String iterVar = "__iter" + n;
        String idxVar = "__i" + n;
        flushPreStatements();
        emitLine(wrapper + " " + iterVar + " = " + iterable + ";");
        emitLine("for (long " + idxVar + " = 0L; " + idxVar + " < (long) "
            + iterVar + ".data.length; " + idxVar + "++) {");
        indent++;
        localScopes.push(new LinkedHashMap<>());
        localTypeScopes.push(new LinkedHashMap<>());
        String loopVar = declareLocal(fos.varName(),
            resolveTypeNode(fos.varType()));
        String read = arrayForOfReadHelper(element, fos.span());
        if (read == null) {
            // Unsupported element shape (an unsupported function
            // signature or nested shape): E6000 recorded by the helper
            // lookup; emit the inert placeholder so the artifact gate
            // still sees a diagnostic-carrying (discarded) result.
            read = "null";
        }
        String javaType = javaLocalType(element, fos.span());
        emitLine((javaType == null ? "java.lang.Object" : javaType) + " "
            + loopVar + " = " + read + "(" + iterVar + ", " + idxVar
            + ");");
        emitForOfLoopBody(fos, loopVar);
        localScopes.pop();
        localTypeScopes.pop();
        indent--;
        emitLine("}");
    }

    /**
     * Body emission shared by the string and array for-of forms: a
     * captured loop variable gets a per-iteration fresh cell declared
     * before the body (closures created in this iteration capture THIS
     * iteration's cell), and the loop's continue semantics stay plain
     * Java (the update — {@code idxVar++} — lives in the for head).
     */
    private void emitForOfLoopBody(ForOfStatement fos, String loopVar) {
        loopContinueLabels.addLast(null);
        boolean captured = isCapturedMapped(loopVar);
        if (captured) {
            String cellType = javaLocalType(resolveTypeNode(fos.varType()),
                fos.span());
            localScopes.push(new LinkedHashMap<>());
            localTypeScopes.push(new LinkedHashMap<>());
            localScopes.peek().put(fos.varName(), loopVar + "$c[0]");
            emitLine("final " + cellType + "[] " + loopVar + "$c = { "
                + loopVar + " };");
        }
        emitScopedBlock(fos.body());
        if (captured) {
            localScopes.pop();
            localTypeScopes.pop();
        }
        loopContinueLabels.removeLast();
    }

    /** The per-element read helper for an array for-of over element type
     * {@code element}, or {@code null} (with E6000 recorded) for an
     * unsupported element shape. Mirrors the emitIndexRead helper chain:
     * function elements, nullable function elements, and nested-array
     * elements route through {@link #refArrayReadHelper} (the per-shape
     * {@code __fnRead$}/{@code __fnOrNullRead$}/{@code __nestedRead$}
     * helpers with their E6000 diagnostics for unsupported signatures)
     * — never the inert placeholder without a diagnostic. */
    private String arrayForOfReadHelper(Type element, Span span) {
        if (element instanceof Type.Nullable ne) {
            String h = orNullArrayReadHelper(ne.inner());
            if (h == null && ne.inner() instanceof Type.Class cls) {
                h = classArrayHelper(cls, "readOrNull", span);
            }
            if (h == null && ne.inner() instanceof Type.Func) {
                h = refArrayReadHelper(element, span);
            }
            return h;
        }
        if (element instanceof Type.Class cls) {
            return classArrayHelper(cls, "read", span);
        }
        if (element instanceof Type.Array || element instanceof Type.Func) {
            return refArrayReadHelper(element, span);
        }
        String h = arrayReadHelper(element);
        if (h != null) return h;
        javaArrayElementType(element, span);
        return null;
    }

    private void emitFor(ForStatement fs) {
        localScopes.push(new LinkedHashMap<>());
        localTypeScopes.push(new LinkedHashMap<>());
        String initCode = null;
        String loopVarDeal = null;
        String loopVarPlain = null;
        String loopVarJavaType = null;
        if (fs.init().isPresent()) {
            if (fs.init().get() instanceof ForInit.VarDecl vd) {
                VariableDeclaration decl = vd.decl();
                Type loopType = decl.typeAnnotation().isPresent()
                    ? resolveTypeNode(decl.typeAnnotation().get())
                    : typeOf(decl.initializer());
                String javaType = javaLocalType(loopType, decl.span());
                if (javaType == null) {
                    localScopes.pop();
                    localTypeScopes.pop();
                    return;
                }
                loopVarDeal = decl.name();
                loopVarJavaType = javaType;
                loopVarPlain = declareLocal(decl.name(), loopType);
                String initVal = emitTargeted(decl.initializer(),
                    loopType, false);
                if (needsBooleanBoundary(decl.initializer(), loopType)) {
                    initVal = "booleanNotNull(" + initVal + ")";
                }
                initVal = coerceNullValueCode(initVal, decl.initializer(),
                    javaType, decl.span());
                initVal = adaptIntBoundary(decl.initializer(), initVal,
                    loopType);
                initCode = javaType + " " + loopVarPlain + " = " + initVal;
            } else if (fs.init().get() instanceof ForInit.AssignExpr ae) {
                initCode = emitAssignmentCore(ae.expr());
            }
        }
        int preAfterInit = preStatements.size();
        // The loop HEADER reads the plain loop variable: a captured
        // variable's per-iteration cell is declared inside the body, so
        // the condition and update cannot reference it.
        if (loopVarPlain != null) {
            plainReadNames.add(loopVarPlain);
        }
        String condCode = "true";
        if (fs.condition().isPresent()) {
            condCode = emitExpression(fs.condition().get());
            if (needsBooleanBoundary(fs.condition().get(),
                    Type.Boolean.INSTANCE)) {
                condCode = "booleanNotNull(" + condCode + ")";
            }
        }
        int preAfterCond = preStatements.size();
        String updateCode = null;
        if (fs.update().isPresent()) {
            ExpressionNode u = fs.update().get();
            if (u instanceof AssignmentExpr ae) {
                updateCode = emitAssignmentCore(ae);
            } else {
                updateCode = emitExpression(u);
            }
        }
        if (loopVarPlain != null) {
            plainReadNames.remove(loopVarPlain);
        }
        boolean hoisted = !preStatements.isEmpty();
        boolean capturedLoopVar = loopVarPlain != null
            && isCapturedMapped(loopVarPlain);
        if (!hoisted && !capturedLoopVar) {
            emitLine("for (" + (initCode == null ? "" : initCode)
                + "; loopCond(" + condCode + "); "
                + (updateCode == null ? "" : updateCode) + ") {");
            indent++;
            loopContinueLabels.addLast(null);
            emitScopedBlock(fs.body());
            loopContinueLabels.removeLast();
            indent--;
            emitLine("}");
            localScopes.pop();
            localTypeScopes.pop();
            return;
        }
        if (!hoisted) {
            // Captured loop variable only: the plain head keeps the
            // shared Java variable; the body declares a fresh cell per
            // iteration and re-binds the DEAL name to the cell inside
            // the body scope (header reads stay on the plain variable).
            emitLine("for (" + (initCode == null ? "" : initCode)
                + "; loopCond(" + condCode + "); "
                + (updateCode == null ? "" : updateCode) + ") {");
            indent++;
            loopContinueLabels.addLast(null);
            emitCapturedLoopVarCell(fs.body(), loopVarDeal, loopVarPlain,
                loopVarJavaType);
            loopContinueLabels.removeLast();
            indent--;
            emitLine("}");
            localScopes.pop();
            localTypeScopes.pop();
            return;
        }
        // Transformed form (hoisted side effects): pre-statements are
        // flushed at their exact evaluation points — the initializer's
        // before the init assignment, the condition's per iteration
        // before the test, the update's per iteration before the update.
        if (initCode != null) {
            flushNPreStatements(preAfterInit);
            emitLine(initCode + ";");
        }
        emitLine("while (true) {");
        indent++;
        flushNPreStatements(preAfterCond - preAfterInit);
        emitLine("if (!loopCond(" + condCode + ")) { break; }");
        String contLabel = "cont$" + loopLabelCounter++;
        loopContinueLabels.addLast(contLabel);
        emitLine(contLabel + ": {");
        indent++;
        if (capturedLoopVar) {
            emitCapturedLoopVarCell(fs.body(), loopVarDeal, loopVarPlain,
                loopVarJavaType);
        } else {
            emitScopedBlock(fs.body());
        }
        indent--;
        emitLine("}");
        loopContinueLabels.removeLast();
        flushPreStatements();
        if (updateCode != null) emitLine(updateCode + ";");
        indent--;
        emitLine("}");
        localScopes.pop();
        localTypeScopes.pop();
    }

    /** Body of a loop whose captured loop variable gets a per-iteration
     * fresh cell (shared by the for and for-of emitters). */
    private void emitCapturedLoopVarCell(Block body, String dealName,
            String plainName, String javaType) {
        localScopes.push(new LinkedHashMap<>());
        localTypeScopes.push(new LinkedHashMap<>());
        localScopes.peek().put(dealName, plainName + "$c[0]");
        emitLine("final " + javaType + "[] " + plainName + "$c = { "
            + plainName + " };");
        emitScopedBlock(body);
        localScopes.pop();
        localTypeScopes.pop();
    }

    private void emitBreak(BreakStatement bs) {
        // The checker rejects break outside a loop (E2000); the nearest
        // emitted Java loop is always the nearest DEAL loop, in both the
        // plain and transformed forms.
        emitLine("break;");
    }

    private void emitContinue(ContinueStatement cs) {
        String label = loopContinueLabels.peekLast();
        if (label == null) {
            emitLine("continue;");
        } else {
            // Transformed for form: jump past the body to the re-placed
            // update (the label wraps the body block).
            emitLine("break " + label + ";");
        }
    }

    private void emitThrow(ThrowStatement th) {
        ExpressionNode e = th.expr();
        if (e instanceof ObjectLiteralExpr ol) {
            // The authoritative origin of a thrown error literal is the
            // `throw` keyword start (the D3 user-throw row): the thrown
            // object is the same DealError a rethrow re-raises, so a
            // rethrow across catch and function boundaries re-raises the
            // ORIGINAL origin, message, and fields unchanged.
            String errorCode = emitErrorLiteral(ol, th.span());
            if (errorCode.equals("null")) {
                return; // diagnostic recorded by emitErrorLiteral
            }
            flushPreStatements();
            emitLine("throw " + errorCode + ";");
            return;
        }
        String thrown = emitExpression(e);
        flushPreStatements();
        emitLine("throw (" + thrown + ");");
    }

    private void emitTry(TryStatement ts) {
        emitLine("try {");
        indent++;
        emitScopedBlock(ts.tryBlock());
        indent--;
        // The catch variable is a fresh function-level binding whose
        // Java name must be visible inside the catch block; declare it
        // in a scope pushed before the catch clause text is emitted.
        localScopes.push(new LinkedHashMap<>());
        localTypeScopes.push(new LinkedHashMap<>());
        String catchName = declareLocal(ts.catchVar(), errorClassType());
        emitLine("} catch (java.lang.RuntimeException " + catchName + ") {");
        indent++;

        if (isCapturedMapped(catchName)) {
            emitLine("final java.lang.RuntimeException[] " + catchName
                + "$c = { " + catchName + " };");
        }
        emitScopedBlock(ts.catchBlock());
        indent--;
        localScopes.pop();
        localTypeScopes.pop();
        emitLine("}");
    }

    private void emitDelete(DeleteStatement ds) {
        if (ds.target() instanceof MemberAccessExpr mae) {
            Type objType = typeOf(mae.object());
            if (objType instanceof Type.Table) {
                String obj = emitExpression(mae.object());
                flushPreStatements();
                emitLine("(" + obj + ").remove("
                    + quoteJavaString(mae.field()) + ");");
                return;
            }
            if (objType instanceof Type.Class cls
                    && !isBuiltinErrorType(cls)) {
                String f = javaName(mae.field());
                String javaType = javaLocalType(objType, ds.span());
                String obj = emitExpression(mae.object());
                if (!isPureAfterEmission(mae.object())) {
                    // The receiver is referenced twice (value clear +
                    // presence clear): materialize a side-effecting
                    // receiver so it evaluates exactly once, before both
                    // stores (spec §Operational semantics rule 1).
                    String temp = nextEvalTempName();
                    preStatements.add(new PreLine(
                        javaType + " " + temp + " = (" + javaType + ") "
                            + obj + ";", 0));
                    preStatementsDeclareTemps = true;
                    obj = temp;
                }
                flushPreStatements();
                ClassDeclaration cd = classDeclFor(cls);
                if (cd != null && cd.isJsonable()) {
                    // An optional @jsonable field delete restores the
                    // ABSENT state: the slot stores the Missing sentinel
                    // (the DECLARING module's sentinel for an imported
                    // class — the cross-module sentinel identity, never
                    // this module's).
                    String sentinel;
                    if (isLocalClassType(cls)) {
                        sentinel = "$MISSING";
                    } else {
                        String importedModule = importedClassModuleRef(cls,
                            ds.span());
                        if (importedModule == null) return;
                        sentinel = importedModule + ".$MISSING";
                    }
                    emitLine("((" + javaType + ") " + obj + ")." + f
                        + " = " + sentinel + ";");
                } else {
                    emitLine("((" + javaType + ") " + obj + ")." + f
                        + " = null;");
                    emitLine("((" + javaType + ") " + obj + ")." + f
                        + "$present = false;");
                }
                return;
            }
            unsupported("delete of " + typeName(objType)
                + " members", ds.span());
            return;
        }
        if (ds.target() instanceof IndexExpr idx
                && typeOf(idx.array()) instanceof Type.Table) {
            List<String> codes = emitOperandsInOrder(
                List.of(idx.array(), idx.index()));
            flushPreStatements();
            emitLine("$tRemove(" + codes.get(0) + ", " + codes.get(1)
                + ");");
            return;
        }
        unsupported("delete of this target shape", ds.span());
    }

    /** True when {@code c} is the builtin {@code Error} class type (the
     * checker types throw/catch values with the intrinsic
     * {@code @$builtin/Error} identity). */
    private static boolean isBuiltinErrorType(Type.Class c) {
        return c.identity().equals(new CanonicalClassIdentity(
            CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Error"));
    }

    private void emitWhile(WhileStatement ws) {
        String condition = emitExpression(ws.condition());
        if (needsBooleanBoundary(ws.condition(), Type.Boolean.INSTANCE)) {
            condition = "booleanNotNull(" + condition + ")";
        }
        if (preStatements.isEmpty()) {
            // Plain form. The condition routes through the emitted loopCond
            // identity helper so javac never sees a constant-expression
            // condition (JLS §14.21): `while (true)` would make statements
            // after the loop unreachable (LuaJIT never executes them, but
            // javac rejects them), and `while (false)` would make the body
            // unreachable (LuaJIT accepts it and skips the body).
            emitLine("while (loopCond(" + condition + ")) {");
            indent++;
            // A DEAL continue inside the body targets THIS while (the
            // nearest enclosing loop), never an enclosing transformed
            // for's re-placed-update label: the null frame makes
            // emitContinue emit plain `continue;`, which Java resolves
            // to the nearest Java loop — the while.
            loopContinueLabels.addLast(null);
            emitScopedBlock(ws.body());
            loopContinueLabels.removeLast();
            indent--;
            emitLine("}");
            return;
        }
        // A condition whose evaluation hoisted side-effecting statements
        // (a null-typed call): LuaJIT re-evaluates the condition on every
        // iteration, so the hoisted statements must run inside the loop
        // before the condition test — never once before the loop. The
        // `while (true)` head plus the reachable non-constant `if
        // (!loopCond(...)) break;` keeps the statement completing normally
        // per JLS §14.21 (the break is reachable because loopCond(...) is
        // not a constant expression).
        emitLine("while (true) {");
        indent++;
        flushPreStatements();
        emitLine("if (!loopCond(" + condition + ")) { break; }");
        // Same continue-target guard as the plain form: `continue;`
        // re-enters the `while (true)` head, which re-runs the hoisted
        // per-iteration pre-statements and the condition test — exactly
        // LuaJIT's re-evaluation — regardless of an enclosing
        // transformed for label below the frame.
        loopContinueLabels.addLast(null);
        emitScopedBlock(ws.body());
        loopContinueLabels.removeLast();
        indent--;
        emitLine("}");
    }

    private void emitBlock(Block b) {
        emitLine("{");
        indent++;
        emitScopedBlock(b);
        indent--;
        emitLine("}");
    }

    private void emitScopedBlock(Block b) {
        localScopes.push(new LinkedHashMap<>());
        localTypeScopes.push(new LinkedHashMap<>());
        List<StatementNode> body = b.statements();
        for (int i = 0; i < body.size(); i++) {
            emitStatement(body.get(i));
            if (!statementCompletesNormally(body.get(i))) {
                // Dead code after a non-completing statement (a return,
                // or an if/else whose branches all return): LuaJIT never
                // executes it and javac rejects it as unreachable
                // (JLS §14.21) — skip the rest of the block.
                break;
            }
        }
        localScopes.pop();
        localTypeScopes.pop();
    }

    private void emitExpressionStatement(ExpressionStatement es) {
        if (es.expr() instanceof CallExpr call) {
            if (typeOf(call) instanceof Type.Null) {
                // A null-typed call (console.log/console.error, a
                // null-returning function) is hoisted into a pre-statement
                // by emitExpression; its void Java result is not a value.
                emitExpression(call);
                flushPreStatements();
            } else {
                String raw = emitCall(call);
                flushPreStatements();
                if (int32Mode && typeOf(call) instanceof Type.Int
                        && intValueCodeIsWiderOrBoxed(call)) {

                    emitLine(intValueEmittedJavaType(call) + " "
                        + nextIgnoredName() + " = " + raw + ";");
                } else {
                    emitLine(raw + ";");
                }
            }
            return;
        }
        if (es.expr() instanceof AssignmentExpr ae) {
            // Parenthesized assignment is not a Java statement.
            String core = emitAssignmentCore(ae);
            flushPreStatements();
            emitLine(core + ";");
            return;
        }
        if (isPrimitiveArrayRead(es.expr())) {
            // A standalone array read's value is discarded: LuaJIT's
            // emitted read is dropped (nil past the end never crosses a
            // typed boundary, so no E8001 — the spec read-site contract
            // applies no boundary to a discarded value), while a negative
            // index still raises E8002 (LuaJIT raises that unconditionally
            // at the read). The boxed read yields null past the end, and
            // the dummy-local discard genuinely evaluates the receiver,
            // the index, and the helper call.
            IndexExpr idx = (IndexExpr) es.expr();
            Type element = ((Type.Array) typeOf(idx.array())).element();
            String boxed = emitBoxedReadTemp(idx);
            flushPreStatements();
            emitLine(arrayBoxedJavaType(element) + " " + nextIgnoredName()
                + " = " + boxed + ";");
            return;
        }
        // Any other standalone expression statement (e.g. `x + 1;`) is
        // checker-accepted and LuaJIT evaluates it — an int overflow there
        // is an observable E8004. Lower it to a dummy-local declaration so
        // it is genuinely evaluated instead of being rejected or discarded.
        String value = emitExpression(es.expr());
        flushPreStatements();
        Type t = typeOf(es.expr());
        // A nil-aware && / || result is a boxed Boolean temporary (null =
        // the Lua nil); the dummy discard must not unbox it (a null would
        // NPE where LuaJIT silently drops the nil).
        // The dummy-local declaration type follows the EMITTED code
        // shape: a wider int code under int32 (the retained time
        // expression — the one wider producer,
        // intValueCodeIsWiderOrBoxed) declares its real Java type —
        // the value is discarded with no declared boundary, so no
        // checkInt gate runs here (D3: the gate is a declared-boundary
        // seam, never a silent narrowing and never a phantom raise).
        String javaType = canYieldNil(es.expr())
            ? "java.lang.Boolean"
            : (int32Mode && t instanceof Type.Int
                && intValueCodeIsWiderOrBoxed(es.expr())
                ? intValueEmittedJavaType(es.expr())
                : javaLocalType(t, es.span()));
        if (javaType == null) return; // diagnostic already recorded
        emitLine(javaType + " " + nextIgnoredName() + " = " + value + ";");
    }

    // =========================================================================
    // Expressions
    // =========================================================================

    /**
     * Emits {@code e} with the read-site contextual target {@code target}
     * when {@code e} is a direct index read (see
     * {@link #emitIndexRead}); every other node shape emits normally — an
     * operator or call nested between the boundary and the read consumes
     * the read at ITS OWN typed operand position, where the element-typed
     * read (E8001 on the past-end nil) is the correct behavior. The
     * target only affects a DIRECT index-read child, exactly like
     * LuaJIT's check at the consuming boundary.
     */
    private String emitExpressionFor(ExpressionNode e, Type target) {
        if (e instanceof IndexExpr idx) return emitIndexRead(idx, target);
        return emitExpression(e);
    }

    /**
     * True when a read of {@code idx} (whose array element is the
     * non-nullable {@code T}) is consumed at a {@code T | null} target —
     * the read site's contextual target accepts the past-end nil
     * (LuaJIT's check_nullable), so the read must yield the DEAL null
     * instead of raising the element-typed E8001 (spec §Bounds and nil
     * behavior: the read result is checked against the TARGET type).
     */
    private boolean isNilYieldingReadTarget(IndexExpr idx, Type target) {
        if (!(typeOf(idx.array()) instanceof Type.Array arr)) return false;
        Type element = arr.element();
        if (element instanceof Type.Nullable) return false;
        if (!(target instanceof Type.Nullable nn)) return false;
        Type inner = nn.inner();
        if (element instanceof Type.Class eCls) {
            if (!(inner instanceof Type.Class tCls)) return false;
            // Class identity by name + locality: the checker types the
            // element with the SOURCE path while the backend resolves the
            // annotation with the MODULE path — both name the same local
            // class. Imported classes keep their module path in both.
            if (!eCls.name().equals(tCls.name())) return false;
            if (isLocalClassType(eCls) || isLocalClassType(tCls)) {
                return true;
            }
            return eCls.identity().equals(tCls.identity());
        }
        return Types.equals(inner, element);
    }

    private String emitExpression(ExpressionNode e) {
        return switch (e) {
            case LiteralExpr lit -> emitLiteral(lit);
            case IdentifierExpr id -> emitIdentifier(id);
            case BinaryExpr bin -> emitBinary(bin);
            case UnaryExpr u -> emitUnary(u);
            case CallExpr call -> {
                String raw = emitCall(call);
                if (typeOf(call) instanceof Type.Null) {
                    // Null-typed calls (console.log/console.error,
                    // null-returning functions) are void Java expressions.
                    // Hoist the call into a pre-statement emitted before the
                    // containing statement and yield the DEAL null value.
                    // Never wrap it in a lambda: a lambda capturing a local
                    // or parameter that is reassigned anywhere in its
                    // enclosing scope is a javac error, and DEAL locals and
                    // parameters are freely reassignable.
                    preStatements.add(new PreLine(raw + ";", 0));
                    yield "null";
                }
                yield raw;
            }
            case AssignmentExpr ae -> emitAssignment(ae);
            case MemberAccessExpr mae -> emitMemberAccessValue(mae);
            case IndexExpr idx -> emitIndexRead(idx, null);
            case ArrayLiteralExpr al -> emitArrayLiteral(al);
            case ObjectLiteralExpr ol -> emitObjectLiteral(ol);
            case FunctionExpr fe -> emitFunctionExpr(fe);
            case HasExpr he -> emitHas(he);
            case TemplateLiteralExpr tl -> emitTemplateLiteral(tl);
            case AwaitExpression aw -> {

                Span prevAwaitSpan = pendingAwaitCalleeSpan;
                ExpressionNode prevAwaitCallee = pendingAwaitCallee;
                if (aw.callee() instanceof CallExpr) {
                    pendingAwaitCalleeSpan = aw.span();
                    pendingAwaitCallee = aw.callee();
                }
                String raw;
                try {
                    raw = emitExpression(aw.callee());
                } finally {
                    pendingAwaitCalleeSpan = prevAwaitSpan;
                    pendingAwaitCallee = prevAwaitCallee;
                }
                if (!(typeOf(aw) instanceof Type.Int)) {
                    yield raw;
                }

                yield int32Mode
                    ? adaptIntBoundary(aw.callee(), raw, Type.Int.INSTANCE)
                    : "checkInt(" + raw + ")";
            }
        };
    }

    private String emitLiteral(LiteralExpr lit) {
        return switch (lit.value()) {
            case LiteralValue.NullLiteral() -> "null";
            case LiteralValue.BooleanLiteral b -> String.valueOf(b.value());
            case LiteralValue.IntLiteral i -> {
                long v = i.value();
                if (int32Mode) {

                    yield String.valueOf(v);
                }
                if (v > 9007199254740991L || v < -9007199254740991L) {
                    // Outside the DEAL int safe range ±(2^53-1): not a valid
                    // DEAL int value. LuaJIT silently rounds such literals
                    // to doubles before arithmetic and only fails when one
                    // crosses a check_int boundary; Java would silently
                    // compute with the exact long. Raise E8004 at the point
                    // of use — rejecting, never silently miscomputing.
                    yield "checkInt(" + v + "L)";
                }
                yield v + "L";
            }
            case LiteralValue.NumberLiteral n -> javaDoubleLiteral(n.value());
            case LiteralValue.StringLiteral s -> quoteJavaString(s.value());
        };
    }

    /** An object literal is either a class construction (class-typed
     * contextual target — the checker's {@code checkClassConstruction}),
     * a table literal ({@code table} target or no target), or out of
     * slice (E6000). */
    private String emitObjectLiteral(ObjectLiteralExpr ol) {
        Type t = typeOf(ol);
        if (t instanceof Type.Class cls && isBuiltinErrorType(cls)) {
            return emitErrorLiteral(ol, ol.span());
        }
        if (t instanceof Type.Class cls) {

            if (isHostModuleClass(cls)) {
                return emitHostClassConstruction(cls, ol);
            }
            return emitClassConstruction(cls, ol);
        }
        if (t instanceof Type.Table) {
            return emitTableLiteral(ol);
        }
        unsupported("object literals without a class or table target type",
            ol.span());
        return "null";
    }

    private String emitErrorLiteral(ObjectLiteralExpr ol, Span originSpan) {
        List<ExpressionNode> values = new ArrayList<>();
        for (Property prop : ol.properties()) {
            values.add(prop.value());
        }
        List<String> codes = emitOperandsInOrder(values);
        String codeCode = null;
        String messageCode = null;
        for (int i = 0; i < ol.properties().size(); i++) {
            if (ol.properties().get(i).name().equals("code")) {
                codeCode = codes.get(i);
            } else if (ol.properties().get(i).name().equals("message")) {
                messageCode = codes.get(i);
            }
        }
        if (codeCode == null) codeCode = "\"\"";
        if (messageCode == null) messageCode = "\"\"";
        // D1: the raise statement carries the origin (file/line/column)
        // arguments explicitly — the per-artifact source path constant
        // plus the authoritative node's compile-time start line/column
        // literals, never a thread-local, a stack lookup, or a runtime
        // parse. The Error literal in a throw position passes the throw
        // keyword's span; a literal outside a throw passes its own span
        // (the object it constructs keeps exactly that origin when it is
        // thrown later or rethrown).
        return "new DealError(" + codeCode + ", " + messageCode
            + ", __SRC, " + originSpan.startLine() + ", "
            + originSpan.startColumn() + ")";
    }

    private String emitClassConstruction(Type.Class cls, ObjectLiteralExpr obj) {
        // Locality is decided from the Type.Class MODULE PATH, then the
        // name-keyed lookup — a same-named local class must not satisfy
        // the guard for a foreign path (the literal would otherwise be
        // tagged with the LOCAL module identity — a silent
        // nominal-identity corruption).
        if (!isLocalClassType(cls)) {
            return emitImportedClassConstruction(cls, obj);
        }
        ClassDeclaration cd = moduleClasses.get(cls.name());
        if (cd == null) {
            unsupported("construction of class '" + cls.name()
                + "' (only local module-level classes are supported)",
                obj.span());
            return "null";
        }
        return emitClassConstructorCall(cd, classNameForClass(cd.name()),
            obj, true, null, publishedPlanFor(cd));
    }

    private String emitHostClassConstruction(Type.Class cls,
                                             ObjectLiteralExpr obj) {
        List<HostModuleDeclarations.HostField> fields = hostFieldsFor(cls);
        if (fields == null) {
            unsupported("construction of host class '" + cls.name()
                + "' (its declaration is unavailable)", obj.span());
            return "null";
        }
        String alias = hostAliasForClass(cls);
        if (alias == null) {
            unsupported("construction of host class '" + cls.name()
                + "' (no import alias maps the declaring host module)",
                obj.span());
            return "null";
        }
        String record = hostRecordJavaRef(cls);
        if (record == null) return "null";
        String defaultsRef = hostDefaultsFieldName(alias, cls.name());
        String identity = classCheckDescriptor(cls);
        // Provided values evaluate left-to-right in literal order with
        // the field-declared read-site targets (the same machinery the
        // project-class constructor call uses).
        List<ExpressionNode> valueNodes = new ArrayList<>();
        List<Type> valueTargets = new ArrayList<>();
        for (Property prop : obj.properties()) {
            valueNodes.add(prop.value());
            Type fieldType = null;
            for (HostModuleDeclarations.HostField hf : fields) {
                if (hf.declaration().name().equals(prop.name())) {
                    fieldType = hf.type();
                    break;
                }
            }
            valueTargets.add(fieldType);
        }
        List<String> codes = emitOperandsInOrder(valueNodes, valueTargets);
        for (int i = 0; i < valueNodes.size(); i++) {
            String code = codes.get(i);
            if (code.startsWith("__t")) continue; // already materialized
            if (isPureAfterEmission(valueNodes.get(i))) continue;
            String javaType = materializationTempType(valueNodes.get(i),
                valueTargets.get(i));
            if (javaType == null) continue; // diagnostic already recorded
            String temp = nextEvalTempName();
            preStatements.add(new PreLine(
                javaType + " " + temp + " = " + code + ";", 0));
            preStatementsDeclareTemps = true;
            codes.set(i, temp);
        }
        Map<String, ExpressionNode> providedNodes = new LinkedHashMap<>();
        Map<String, String> provided = new LinkedHashMap<>();
        for (int i = 0; i < obj.properties().size(); i++) {
            providedNodes.put(obj.properties().get(i).name(),
                valueNodes.get(i));
            provided.put(obj.properties().get(i).name(), codes.get(i));
        }
        // Phase 1 (parent D5): an extra provided name — a name the
        // host's defaults map does not carry — raises E8007 before any
        // default evaluation or field validation runs.
        for (Property prop : obj.properties()) {
            preStatements.add(new PreLine("if (!" + defaultsRef
                + ".containsKey(" + quoteJavaString(prop.name())
                + ")) throw new DealError(\"E8007\", \"extra field '"
                + prop.name() + "' in class '" + identity + "'\", "
                + originArgs(obj.span()) + ");", 0));
        }
        // Phases 2\u20134: constructor arguments in field declaration
        // order — provided values, defaults-map fills for omitted
        // required fields, absent optional fields, per-field validation,
        // and the published identity-carrying record.
        List<String> args = new ArrayList<>();
        for (HostModuleDeclarations.HostField hf : fields) {
            ClassField cf = hf.declaration();
            Type fieldType = hf.type();
            String code = provided.get(cf.name());
            ExpressionNode valueNode = providedNodes.get(cf.name());
            Type inner = fieldType instanceof Type.Nullable nn
                ? nn.inner() : fieldType;
            boolean optional = cf.optional();
            if (code != null && valueNode != null
                    && needsBooleanBoundary(valueNode, fieldType)) {
                code = "booleanNotNull(" + code + ")";
            }
            if (code == null && !optional) {
                // Omitted required field: the host defaults map fills it
                // and the raw map value validates against the declared
                // field type (checker-gated defensive: the checker
                // rejects omissions of fields without a declaration
                // default, and host declarations carry no default
                // expressions in the corpus).
                String desc = typeDescriptor(fieldType);
                code = "(" + hostRecordFieldJavaType(inner, optional)
                    + ") $check("
                    + quoteJavaString(desc)
                    + ", __hostMissingDefault(" + defaultsRef + ", "
                    + quoteJavaString(cf.name()) + ", "
                    + quoteJavaString(identity) + "))";
            } else if (code == null) {
                // Omitted optional field: absent (the DEAL null, not
                // present).
                code = "null";
            } else if (valueNode != null) {
                code = coerceNullValueCode(code, valueNode,
                    hostRecordFieldJavaType(inner, optional),
                    valueNode.span());
                code = adaptIntBoundary(valueNode, code, fieldType);
            }
            args.add(code);
            if (optional) {
                args.add(provided.containsKey(cf.name()) ? "true" : "false");
            }
        }
        return "new " + record + "(" + String.join(", ", args) + ")";
    }

    private String hostRecordFieldJavaType(Type inner, boolean optional) {
        if (optional) {
            return silentNullableJavaType(inner);
        }
        return silentJavaLocalType(inner);
    }

    private String emitImportedClassConstruction(Type.Class cls,
                                                 ObjectLiteralExpr obj) {
        String module = declaringModulePath(cls);
        Map<String, ClassDeclaration> decls = module == null
            ? null : importedClasses.get(module);
        ClassDeclaration cd = decls == null ? null : decls.get(cls.name());
        if (cd == null) {
            unsupported("construction of imported class '" + cls.name()
                + "' (its declaration is unavailable — the declaring "
                + "module is not an imported compiled project module of "
                + "this module, or the class is not module-level)",
                obj.span());
            return "null";
        }
        if (module == null || !importAliases.containsValue(module)) {
            unsupported("construction of imported class '" + cls.name()
                + "' (the declaring module is not imported)", obj.span());
            return "null";
        }
        for (ClassField cf : cd.fields()) {
            if (cf.optional()) {
                unsupported("optional fields of imported class '"
                    + cd.name() + "' (their reads produce nullable "
                    + "values)", cf.span());
                return "null";
            }
            if (cf.nullable()) {
                unsupported("nullable fields of imported class '"
                    + cd.name() + "'", cf.span());
                return "null";
            }
            Type fieldType = resolveTypeNode(cf.type());
            if (fieldType == Type.Error.INSTANCE) return "null";
            if (!(fieldType instanceof Type.Int)
                    && !(fieldType instanceof Type.Number)
                    && !(fieldType instanceof Type.Boolean)
                    && !(fieldType instanceof Type.String)) {
                unsupported("fields of imported class '" + cd.name()
                    + "' of type " + typeName(fieldType)
                    + " (only primitive fields are supported)", cf.span());
                return "null";
            }
        }

        for (ClassField cf : cd.fields()) {
            if (cf.defaultExpr().isPresent()
                    && !(cf.defaultExpr().get() instanceof LiteralExpr)
                    && publishedPlanForModule(module, cd) == null) {
                unsupported("construction of imported class '" + cd.name()
                    + "' whose field '" + cf.name() + "' has a "
                    + "non-literal default (imported defaults evaluate "
                    + "in the declaring module's scope under LuaJIT, so "
                    + "re-emitting them in the importing module's scope "
                    + "would bind different identifiers)",
                    cf.defaultExpr().get().span());
                return "null";
            }
        }
        String moduleClass = classNameFor(module);
        return emitClassConstructorCall(cd,
            moduleClass + "." + classNameForClass(cd.name()), obj, false,
            moduleClass, publishedPlanForModule(module, cd));
    }

    /**
     * Shared constructor-call emission for local and imported class
     * constructions (see {@link #emitClassConstruction}).
     *
     */
    private String emitClassConstructorCall(ClassDeclaration cd, String ctorExpr,
                                            ObjectLiteralExpr obj,
                                            boolean localDefaults,
                                            String providerRef,
                                            CompilerClassDefaultPlan planForCtor) {
        List<ExpressionNode> valueNodes = new ArrayList<>();
        List<Type> valueTargets = new ArrayList<>();
        for (Property prop : obj.properties()) {
            valueNodes.add(prop.value());
            // The read-site target of a provided value is the FIELD's
            // declared type: a direct T[] read provided to a T | null
            // field yields the DEAL null past the end (LuaJIT's
            // check_nullable at the construction boundary). Unknown
            // fields keep the element-typed read (the checker-gated
            // defensive path).
            Type fieldType = null;
            for (ClassField cf : cd.fields()) {
                if (cf.name().equals(prop.name())) {
                    fieldType = classFieldDeclaredType(cd, cf);
                    break;
                }
            }
            valueTargets.add(fieldType);
        }
        // D5 phase-order deferral: a table read provided to a plain
        // bytes field evaluates raw at phase 1 and validates against
        // the field's canonical descriptor at phase 3, after every
        // omitted required default of the attempt ran once (the
        // LuaJIT class_plan_ order — the read-site E8001 would
        // otherwise fire during phase 1 and suppress the defaults the
        // reference still runs).
        Set<ExpressionNode> deferredReads = null;
        for (Property prop : obj.properties()) {
            if (prop.value() instanceof MemberAccessExpr mae
                    && typeOf(mae.object()) instanceof Type.Table
                    && isDeferredConstructionProvidedField(cd,
                        prop.name())) {
                if (deferredReads == null) {
                    deferredReads = java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<>());
                }
                deferredReads.add(mae);
            }
        }
        Set<ExpressionNode> prevDeferredConstruction = deferredConstructionReads;
        deferredConstructionReads = deferredReads;
        List<String> codes;
        try {
            codes = emitOperandsInOrder(valueNodes, valueTargets);
        } finally {
            deferredConstructionReads = prevDeferredConstruction;
        }
        // The constructor arguments run in field DECLARATION order, but
        // LuaJIT evaluates the provided-fields table in LITERAL order — an
        // effectful provided value whose literal position differs from its
        // declaration position would otherwise run out of order. Every
        // still-inline effectful provided value is materialized into a
        // fresh temporary appended after the hoisted statements (emitOperands
        // InOrder already materialized every effectful value that precedes a
        // hoisting sibling, so the remaining ones all sit after the last
        // hoist and keep their relative literal order); pure values stay
        // inline.
        for (int i = 0; i < valueNodes.size(); i++) {
            String code = codes.get(i);
            if (code.startsWith("__t")) continue; // already materialized
            if (isPureAfterEmission(valueNodes.get(i))) continue;
            // The temporary's Java type follows the EMITTED code shape,
            // not just the static type: a null-typed effectful value
            // materializes into an Object temporary (javaLocalType(null)
            // is java.lang.Void and javac rejects
            // `java.lang.Void __t0 = (m = null);`), a nil-yielding read
            // (a direct T[] read at a T | null field) carries the boxed
            // element type, and a nil-aware && / || result stays boxed
            // (a `boolean` declaration would auto-unbox and NPE on null
            // before the constructor's booleanNotNull boundary could
            // raise E8001) — exactly like emitOperandsInOrder's own
            // materialization below.
            String javaType = materializationTempType(valueNodes.get(i),
                valueTargets.get(i));
            if (javaType == null) continue; // diagnostic already recorded
            String temp = nextEvalTempName();
            preStatements.add(new PreLine(
                javaType + " " + temp + " = " + code + ";", 0));
            preStatementsDeclareTemps = true;
            codes.set(i, temp);
        }
        Map<String, ExpressionNode> providedNodes = new LinkedHashMap<>();
        Map<String, String> provided = new LinkedHashMap<>();
        for (int i = 0; i < obj.properties().size(); i++) {
            providedNodes.put(obj.properties().get(i).name(),
                valueNodes.get(i));
            provided.put(obj.properties().get(i).name(), codes.get(i));
        }
        List<String> args = new ArrayList<>();
        for (ClassField cf : cd.fields()) {
            String code = provided.get(cf.name());
            ExpressionNode valueNode = providedNodes.get(cf.name());
            if (cf.optional() && cd.isJsonable()) {
                // An optional field of an @jsonable class contributes ONE
                // Object argument: the provided value (the DEAL null for
                // an explicit null) or the Missing sentinel for an
                // omitted field. A declared default on an optional field
                // is checker-validated metadata that NEVER evaluates
                // (provider-versioned-default-plans D2, runtime page D1):
                // an omitted optional field stays absent, so no default
                // runs here — the three states stay distinguishable
                // (absent is the sentinel reference, present-null is the
                // DEAL null).
                if (code != null && valueNode != null
                        && (localDefaults
                            || providedNodes.containsKey(cf.name()))
                        && needsBooleanBoundary(valueNode,
                            classFieldDeclaredType(cd, cf))) {
                    code = "booleanNotNull(" + code + ")";
                }
                if (code == null) {
                    // Omitted, no default: the Missing sentinel.
                    code = jsonableMissingRef(cd);
                } else if (valueNode != null) {
                    code = coerceNullValueCode(code, valueNode,
                        "java.lang.Object", valueNode.span());

                    code = adaptIntBoundary(valueNode, code,
                        classFieldDeclaredType(cd, cf));
                }
                args.add(code);
                continue;
            }
            if (code == null && cf.defaultExpr().isPresent()
                    && !cf.optional()) {
                if (planForCtor != null) {

                    code = (providerRef == null ? "" : providerRef + ".")
                        + defaultEvaluatorName(cd.name(), cf.name()) + "()";
                } else {
                    valueNode = cf.defaultExpr().get();
                    if (localDefaults) {
                        if (typeOf(valueNode) == Type.Error.INSTANCE) {

                            unsupported("default expression of field '" + cf.name()
                                + "' of class '" + cd.name()
                                + "' (unresolved default-expression type)",
                                valueNode.span());
                            code = zeroValueFor(cf.type());
                        } else {
                            code = emitExpressionFor(valueNode,
                                classFieldDeclaredType(cd, cf));
                        }
                    } else {
                        // Imported defaults are literal constants (validated
                        // by emitImportedClassConstruction); they emit
                        // standalone and carry no nil-aware boolean shape.
                        code = emitExpression(valueNode);
                    }
                }
                if (deferredReads != null) {
                    // D5 phase 2 must complete before the phase-3
                    // provided-value validation below: the omitted
                    // default evaluation materializes into a temporary
                    // (in class source order, after the phase-1
                    // provided temps) so a raising phase-3 check still
                    // observes every default of the attempt exactly
                    // once — the LuaJIT class_plan_ order.
                    Type defaultFieldType = classFieldDeclaredType(cd, cf);
                    String defaultJava = defaultFieldType == null ? null
                        : javaLocalType(defaultFieldType, cf.span());
                    if (defaultJava != null) {
                        String temp = nextEvalTempName();
                        preStatements.add(new PreLine(
                            defaultJava + " " + temp + " = " + code + ";",
                            0));
                        preStatementsDeclareTemps = true;
                        code = temp;
                    }
                }
            }
            if (code != null && valueNode != null
                    && (localDefaults || providedNodes.containsKey(cf.name()))
                    && needsBooleanBoundary(valueNode,
                        classFieldDeclaredType(cd, cf))) {

                code = "booleanNotNull(" + code + ")";
            }
            boolean optional = cf.optional();
            boolean presence = provided.containsKey(cf.name())
                || (!cf.optional() && cf.defaultExpr().isPresent());
            if (code == null && optional && !presence) {
                // A missing optional field constructs as the DEAL null,
                // not present (has() is false, reads yield null).
                code = "null";
            } else if (code == null) {
                // Checker-guaranteed unreachable (E4001 missing required
                // field); defensive E6000 keeps the artifact contract.
                unsupported("construction omitting the required-present field '"
                    + cf.name() + "' of class '" + cd.name() + "'", obj.span());
                code = zeroValueFor(cf.type());
            }
            if (valueNode != null) {
                Type declaredFieldType = classFieldDeclaredType(cd, cf);
                String fieldJava = declaredFieldType == null ? null
                    : javaLocalType(declaredFieldType, cf.span());
                if (deferredReads != null && deferredReads.contains(valueNode)) {
                    // D5 phase 3: the dynamic provided value validates
                    // against the field's canonical descriptor — after
                    // every omitted default above ran once — exactly
                    // where LuaJIT's class_plan_ validates present
                    // fields. A failed check publishes no instance (the
                    // constructor never runs).
                    code = "(($DealRt.Bytes) $check(\"bytes\", "
                        + code + "))";
                } else {
                    code = coerceNullValueCode(code, valueNode, fieldJava,
                        valueNode.span());

                    code = adaptIntBoundary(valueNode, code, declaredFieldType);
                }
            }
            args.add(code);
            if (cf.optional()) {
                args.add(presence ? "true" : "false");
            }
        }
        return "new " + ctorExpr + "(" + String.join(", ", args) + ")";
    }

    /**
     * True when the provided value of the named class field defers its
     * dynamic table-read boundary check to the D5 phase-3 validation
     * (the plain {@code bytes} field shape): the raw read lands in the
     * phase-1 unpublished slot and the canonical descriptor check runs
     * after the attempt's defaults, exactly like the LuaJIT
     * {@code class_plan_} order. Only the plain bytes field defers
     * today — the shape the
     * bytes-class-default-integration fixture pins.
     */
    private boolean isDeferredConstructionProvidedField(
            ClassDeclaration cd, String fieldName) {
        for (ClassField cf : cd.fields()) {
            if (cf.name().equals(fieldName)) {
                return classFieldDeclaredType(cd, cf)
                    instanceof Type.Bytes;
            }
        }
        return false;
    }

    private CompilerClassDefaultPlan publishedPlanFor(
            ClassDeclaration node) {
        if (node == null || identityIndex == null) {
            return null;
        }
        List<PlannedDefaultClass> plans = plansByModulePath.get(modulePath);
        if (plans == null) {
            return null;
        }
        for (PlannedDefaultClass planned : plans) {
            ClassDeclaration declaration = planned.declaration();
            if (declaration == node
                    || (declaration.name().equals(node.name())
                        && declaration.span().startLine()
                            == node.span().startLine()
                        && declaration.span().startColumn()
                            == node.span().startColumn())) {
                return planned.plan();
            }
        }
        return null;
    }

    /**
     * The published compiler plan of an imported class, looked up by
     * the declaring module path plus the declaration name/position pair
     * (the consumer receives the provider's own declaration objects, so
     * reference identity holds as well; the position pair is the
     * defensive fallback).
     */
    private CompilerClassDefaultPlan publishedPlanForModule(
            String declaringModulePath, ClassDeclaration node) {
        if (node == null || identityIndex == null) {
            return null;
        }
        List<PlannedDefaultClass> plans =
            plansByModulePath.get(declaringModulePath);
        if (plans == null) {
            return null;
        }
        for (PlannedDefaultClass planned : plans) {
            ClassDeclaration declaration = planned.declaration();
            if (declaration == node
                    || (declaration.name().equals(node.name())
                        && declaration.span().startLine()
                            == node.span().startLine()
                        && declaration.span().startColumn()
                            == node.span().startColumn())) {
                return planned.plan();
            }
        }
        return null;
    }

    /**
     * The deterministic static evaluator method name of one defaulted
     * entry ({@code $default$<C>$<field>}): the {@code $} prefix is
     * unreachable from {@link #javaName} (user identifiers cannot
     * contain {@code $} and underscores escape), so no user binding can
     * collide, and the {@code <C>} component is the generated nested
     * class name, which is unique per module-level declaration.
     */
    private String defaultEvaluatorName(String className, String fieldName) {
        return "$default$" + classNameForClass(className) + "$"
            + javaName(fieldName);
    }

    /** The deterministic per-class plan record name ({@code $plan$<C>}). */
    private String planRecordName(String className) {
        return "$plan$" + classNameForClass(className);
    }

    /**
     * The canonical descriptor text of a published plan's class
     * identity, through the compilation's one descriptor service
     * (identity carriage). Defensive: the planner only publishes plans
     * for identities the index registered.
     */
    private String identityText(
            deal.identity.CanonicalClassIdentity identity) {
        String text = identityIndex.descriptorTextFor(identity);
        if (text == null) {
            throw new IllegalStateException(
                "published default plan has no canonical identity text: "
                    + identity);
        }
        return text;
    }

    private RuntimeDefaultEvaluator.Invocation jvmInvocationSeam(
            String methodName) {
        return () -> {
            // The generated module class resolves through the calling
            // thread's context class loader, so the verification
            // battery (and any in-process consumer) can invoke the
            // real compiled evaluator by loading the artifact's output
            // directory first.
            try {
                ClassLoader loader = Thread.currentThread()
                    .getContextClassLoader();
                java.lang.reflect.Method method = (loader == null
                        ? Class.forName(generatedClassName)
                        : Class.forName(generatedClassName, true, loader))
                    .getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method.invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                    "the JVM default evaluator " + methodName
                        + " of generated module " + generatedClassName
                        + " could not be invoked reflectively", e);
            }
        };
    }

    private void emitDefaultPlanRealization(ClassDeclaration cd,
            CompilerClassDefaultPlan plan) {
        String identityText = identityText(plan.classIdentity());
        List<RuntimeDefaultPlanLowering.EvaluatorRealization>
            realizations = new ArrayList<>();
        for (CompilerClassDefaultEntry entry : plan.orderedFields()) {
            if (entry.optional()) {
                continue;
            }
            ResolvedDefaultExpression def = entry.defaultExpression();
            String label = RuntimeDefaultPlanLowering.labelOf(identityText,
                entry.name(), def.semanticDigest());
            String methodName = defaultEvaluatorName(cd.name(),
                entry.name());
            Type fieldType = entry.resolvedFieldType();
            String javaType = javaLocalType(fieldType,
                def.expressionAst().span());
            if (javaType == null) {
                unsupported("default evaluator of field '" + entry.name()
                    + "' of class '" + cd.name() + "' of type "
                    + typeName(fieldType),
                    def.expressionAst().span());
                continue;
            }
            emitLine("// default evaluator " + label);
            emitLine("static " + javaType + " " + methodName + "() {");
            indent++;
            // The evaluator body is a function frame: it runs at
            // construction time, never at load, so the load-time
            // call/value guards (currentModuleStatementIndex >= 0)
            // must not apply inside it — same save/restore the emitted
            // DEAL function bodies use.
            boolean savedModuleLevel = moduleLevel;
            int savedModuleIndex = currentModuleStatementIndex;
            currentModuleStatementIndex = -1;
            moduleLevel = false;
            String code;
            try {
                code = emitExpressionFor(def.expressionAst(), fieldType);
                if (needsBooleanBoundary(def.expressionAst(), fieldType)) {
                    code = "booleanNotNull(" + code + ")";
                }
                code = coerceNullValueCode(code, def.expressionAst(),
                    javaType, def.expressionAst().span());

                code = adaptIntBoundary(def.expressionAst(), code,
                    fieldType);
            } finally {
                currentModuleStatementIndex = savedModuleIndex;
                moduleLevel = savedModuleLevel;
            }
            flushPreStatements();
            emitLine("return " + code + ";");
            indent--;
            emitLine("}");
            realizations.add(new RuntimeDefaultPlanLowering
                .EvaluatorRealization(label,
                    "static " + javaType + " " + methodName + "() {"
                        + " return " + code + "; }",
                    jvmInvocationSeam(methodName)));
        }
        boolean complete = plan.orderedFields().stream()
            .filter(e -> !e.optional()).count() == realizations.size();
        if (complete) {
            runtimePlans.add(RuntimeDefaultPlanLowering.realize(plan,
                identityText, realizations).plan());
        }
        // The static per-class plan record: ordered entries {name,
        // canonical descriptor, optional, evaluator?} — the JVM
        // realization of the RuntimeClassDefaultPlan shape
        // (DefaultRuntimeAbiVersion "1").
        emitLine("// Runtime default plan for " + cd.name()
            + " — ordered entries {name, descriptor, optional,"
            + " evaluator} (DefaultRuntimeAbiVersion \""
            + DefaultRuntimeAbiVersion.CURRENT.version() + "\")");
        emitLine("static final $DealRt.DefaultPlanEntry[] "
            + planRecordName(cd.name())
            + " = new $DealRt.DefaultPlanEntry[] {");
        indent++;
        for (CompilerClassDefaultEntry entry : plan.orderedFields()) {
            String evaluatorRef = entry.optional() ? "null"
                : generatedClassName + "::"
                    + defaultEvaluatorName(cd.name(), entry.name());
            emitLine("new $DealRt.DefaultPlanEntry("
                + quoteJavaString(entry.name()) + ", "
                + quoteJavaString(entry.runtimeTypeDescriptor()) + ", "
                + entry.optional() + ", " + evaluatorRef + "),");
        }
        indent--;
        emitLine("};");
    }

    /** A placeholder for the defensive missing-required-field branch (the
     * artifact is discarded anyway — hasErrors gates compilation). */
    private String zeroValueFor(TypeNode typeNode) {
        if (typeNode instanceof NamedType nt) {
            return switch (nt.name()) {
                case "int" -> int32Mode ? "0" : "0L";
                case "number" -> "0.0";
                case "boolean" -> "false";
                // The two-character quoted Java literal — the bare empty
                // string here once emitted `java.lang.String f = ;`
                // (an artifact javac rejected after the CLI reported
                // success).
                case "string" -> "\"\"";
                default -> "null";
            };
        }
        return "null";
    }

    /**
     * Emits a table literal as {@code new $DealRt.Table().put(k1, v1).put(k2, v2)}.
     * Property values evaluate left-to-right in literal order (chained
     * {@code put} calls: each target evaluates before the next value
     * argument), with the hoisting machinery preserving order when a value
     * hoists a side-effecting null-typed call. Keys are the DEAL property
     * names verbatim (the spec's identifier-only property names), matching
     * LuaJIT's string-keyed table.
     */
    private String emitTableLiteral(ObjectLiteralExpr ol) {
        List<ExpressionNode> valueNodes = new ArrayList<>();
        for (Property prop : ol.properties()) valueNodes.add(prop.value());
        List<String> codes = emitOperandsInOrder(valueNodes);
        StringBuilder sb = new StringBuilder("new $DealRt.Table()");
        for (int i = 0; i < ol.properties().size(); i++) {

            String valueCode = adaptIntBoundary(valueNodes.get(i),
                codes.get(i), typeOf(valueNodes.get(i)));
            sb.append(".put(")
                .append(quoteJavaString(ol.properties().get(i).name()))
                .append(", ").append(valueCode).append(')');
        }
        return sb.toString();
    }

    /**
     * Lowers a template literal to Java string concatenation. The parser
     * alternates string-literal parts (even indices) with interpolated
     * expressions (odd indices); the checker types every interpolation as
     * {@code string} (E3016 otherwise) and the whole template as
     * {@code string}, so no runtime conversion is involved. Empty literal
     * parts are elided (Lua's {@code .. ""} concatenations contribute
     * nothing), and a template with no interpolations is just its literal.
     */
    private String emitTemplateLiteral(TemplateLiteralExpr tl) {
        List<ExpressionNode> parts = tl.parts();
        if (parts.size() == 1) {
            return emitExpression(parts.get(0));
        }
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        for (int i = 0; i < parts.size(); i++) {
            String emitted = emitExpression(parts.get(i));
            if (i % 2 == 0 && emitted.equals("\"\"")) {
                continue; // empty literal part contributes nothing
            }
            if (!first) sb.append(" + ");
            sb.append(emitted);
            first = false;
        }
        sb.append(")");
        return sb.toString();
    }

    /** The emitted Java name bound to {@code name} in the nearest visible
     * scope, or {@code null} when {@code name} is not a variable. */
    private String localJavaName(String name) {
        for (Map<String, String> scope : localScopes) {
            String mapped = scope.get(name);
            if (mapped != null) {
                if (plainReadNames.contains(mapped)) {
                    // The loop HEADER reads the plain variable; its
                    // per-iteration cell is declared inside the body.
                    return mapped;
                }

                for (Set<String> frame : capturedMappedStack) {
                    if (frame.contains(mapped)) {
                        return mapped + "$c[0]";
                    }
                }
                return mapped;
            }
        }
        return null;
    }

    /**
     * Declares {@code name} in the current scope and returns the emitted Java
     * name. Shadowing declarations (an emitted name already visible in an
     * enclosing scope) get a collision-free {@code $n} suffix, because Java
     * rejects redeclaring a visible local. The collision test compares
     * EMITTED Java names (the scope values) across every visible scope, not
     * DEAL names (the scope keys): a parameter or local already mapped to
     * {@code g$1} makes the emitted name {@code g$1} unavailable for a new
     * shadowing {@code g} (the old key-based test missed this — it asked
     * whether {@code g$1} was a KEY, but it is a VALUE, so the new binding
     * silently reused the taken name and its initializer's enclosing read
     * then referred to the uninitialized new binding instead of the outer
     * one). Module fields participate: their emitted static-field names are
     * visible to the whole module, and disambiguating against them keeps
     * the shadowed-initializer lowering (`long x$1 = x + 1;` — the RHS `x`
     * resolves to the field, since a Java local's scope starts at its own
     * initializer) legal.
     */
    private String declareLocal(String name, Type declaredType) {
        String base = javaName(name);
        String mapped = base;
        int n = 1;
        while (emittedNameVisible(mapped)) {
            mapped = base + "$" + (n++);
        }
        localScopes.peek().put(name, mapped);
        localTypeScopes.peek().put(name, declaredType);

        if (!capturedNamesStack.isEmpty()
                && capturedNamesStack.peek().contains(name)) {
            capturedMappedStack.peek().add(mapped);
        }
        // Parameters and function-body top-level lets share ONE scope map,
        // so this put() can overwrite an earlier mapping of the same DEAL
        // name (the parameter's). The overwritten emitted name is still in
        // Java scope for the rest of the method (JLS §6.4) and must stay
        // reserved for collision purposes: record it in the current
        // function's emitted-name set, which outlives the map overwrite.
        if (!functionBindingNames.isEmpty()) {
            functionBindingNames.peek().add(mapped);
        }
        return mapped;
    }

    /** True when {@code emittedName} is already used as the emitted Java
     * name of any binding in a visible scope (module fields, enclosing
     * locals, parameters) or of any binding still in Java scope inside the
     * current function (the per-function {@link #functionBindingNames}
     * sets — including names whose scope-map key was overwritten by a
     * shadowing {@code let}, e.g. a shadowed parameter). Java forbids two
     * visible locals with the same name, so the new binding must take a
     * fresh one. */
    private boolean emittedNameVisible(String emittedName) {
        for (Set<String> names : functionBindingNames) {
            if (names.contains(emittedName)) return true;
        }
        for (Map<String, String> scope : localScopes) {
            if (scope.containsValue(emittedName)) return true;
        }
        return false;
    }

    private String emitIdentifier(IdentifierExpr id) {
        Type readType = typeOf(id);
        String mapped = localJavaName(id.name());
        if (mapped != null) {
            Type declared = declaredTypeForBinding(id.name());
            return adaptNarrowedRead(mapped, declared, readType);
        }
        Symbol sym = symbols.resolve(id.name());
        if (sym instanceof Symbol.VariableSymbol vs) {
            // A module field read: its declared type comes from the
            // module-scope symbol (a narrowed read of a nullable module
            // field inside a function unboxes / null-adapts like a
            // local).
            return adaptNarrowedRead(javaName(id.name()), vs.type(), readType);
        }
        if (sym instanceof Symbol.IntrinsicSymbol) {

            return switch (id.name()) {
                case "int" -> "_int$fn";
                case "number" -> "_number$fn";
                default -> {
                    unsupported("intrinsic '" + id.name() + "' used as a value",
                        id.span());
                    yield "null";
                }
            };
        }
        if (sym instanceof Symbol.FunctionSymbol fs) {
            if (!moduleFunctions.containsKey(id.name())) {
                unsupported("non-module functions used as first-class values",
                    id.span());
                return "null";
            }
            // Load-time value use of a not-yet-declared function: LuaJIT
            // assigns each function value at its declaration point in
            // source order and reads the global nil for an earlier use,
            // failing at load; Java would emit an illegal forward
            // reference to the wrapper field (a class-body initializer
            // may not reference a later static field). Reject instead of
            // miscompiling. Function-body uses are fine: methods may
            // legally reference later-declared static fields, and at call
            // time every wrapper field is initialized (LuaJIT parity).
            if (currentModuleStatementIndex >= 0
                    && moduleFunctionIndices.getOrDefault(id.name(),
                        Integer.MAX_VALUE) >= currentModuleStatementIndex) {
                unsupported("module-level use of function '" + id.name()
                    + "' as a value before its declaration (LuaJIT reads "
                    + "the global nil at load; Java rejects the forward "
                    + "reference to the wrapper field)", id.span());
                return "null";
            }
            // The wrapper field always exists for a module function: the
            // shared $DealRt scope carries every wrapper shape, and
            // emitFunction emits the per-declaration wrapper field.
            // Signature shapes with table | null positions are rejected
            // earlier at the parameter/return type mapping (E6000), so
            // no dangling reference can be emitted here; the null
            // funcType arm stays defensive.
            if (fs.funcType() == null) {
                unsupported("function values whose signature contains "
                    + "unsupported carriers (table | null positions and "
                    + "host-class carriers — deferred to the ISSUE-0110 "
                    + "descriptor join)", id.span());
                return "null";
            }
            return javaName(id.name()) + "$fn";
        }
        if (sym instanceof Symbol.ModuleSymbol) {
            unsupported("module aliases used as values", id.span());
            return "null";
        }
        return javaName(id.name());
    }

    /** The declared type of the visible binding of {@code name}: nearest
     * local/parameter scope first (the {@link #localTypeScopes} stack,
     * parallel to {@link #localScopes}), then the module-scope symbol. */
    private Type declaredTypeForBinding(String name) {
        for (Map<String, Type> scope : localTypeScopes) {
            Type t = scope.get(name);
            if (t != null) return t;
        }
        Symbol sym = symbols.resolve(name);
        if (sym instanceof Symbol.VariableSymbol vs) return vs.type();
        return null;
    }

    private String adaptNarrowedRead(String code, Type declared, Type read) {
        if (read instanceof Type.Null) return "null";
        if (!(declared instanceof Type.Nullable)) return code;
        return switch (read) {
            case Type.Int ignored -> code
                + (int32Mode ? ".intValue()" : ".longValue()");
            case Type.Number ignored -> code + ".doubleValue()";
            case Type.Boolean ignored -> code + ".booleanValue()";
            default -> code;
        };
    }

    private String coerceNullValueCode(String code, ExpressionNode e,
                                       String targetJavaType, Span span) {
        if (targetJavaType == null) return code;
        if (e instanceof AssignmentExpr
                && typeOf(e) instanceof Type.Null) {
            return "(" + targetJavaType + ") (java.lang.Object) " + code;
        }
        return code;
    }

    private String emitBinary(BinaryExpr bin) {
        Type leftType = typeOf(bin.left());
        Type rightType = typeOf(bin.right());
        BinaryOp op = bin.op();

        // Boolean && / || short-circuit: the right operand is only
        // evaluated when the left operand does not already decide the
        // result (LuaJIT's `and`/`or` semantics). A null-typed
        // side-effecting call inside the right operand is hoisted into
        // pre-statements by emitExpression; flushing those statements
        // unconditionally would run the call even when the operand is
        // skipped (`false && helper() === null` must NOT call helper()).
        // The hoisted statements are guarded by the left operand and the
        // result is carried in a temporary instead.
        if ((op == BinaryOp.AND || op == BinaryOp.OR)
                && leftType instanceof Type.Boolean
                && rightType instanceof Type.Boolean) {
            return emitShortCircuit(bin, op == BinaryOp.AND);
        }

        if (op == BinaryOp.EQ || op == BinaryOp.NEQ) {
            boolean leftRead = isNilCapableArrayRead(bin.left());
            boolean rightRead = isNilCapableArrayRead(bin.right());
            if (leftRead || rightRead || canYieldNil(bin.left())
                    || canYieldNil(bin.right())) {
                return emitArrayReadComparison(bin, op == BinaryOp.EQ);
            }

            if (leftType instanceof Type.Nullable
                    || rightType instanceof Type.Nullable) {
                return emitNullableComparison(bin, op == BinaryOp.EQ);
            }

            if (leftType instanceof Type.Array
                    || leftType instanceof Type.Class
                    || leftType instanceof Type.Table
                    || leftType instanceof Type.Bytes) {
                List<String> idOps = emitOperandsInOrder(
                    List.of(bin.left(), bin.right()));
                return op == BinaryOp.EQ
                    ? "(" + idOps.get(0) + " == " + idOps.get(1) + ")"
                    : "(" + idOps.get(0) + " != " + idOps.get(1) + ")";
            }
        }

        List<String> operands = emitOperandsInOrder(
            List.of(bin.left(), bin.right()));
        String left = operands.get(0);
        String right = operands.get(1);

        // String concatenation: both operands must be string (checker-enforced).
        if (op == BinaryOp.ADD && leftType instanceof Type.String
                && rightType instanceof Type.String) {
            return "(" + left + " + " + right + ")";
        }

        // Integer arithmetic — always checked, with DEAL error codes.
        if (leftType instanceof Type.Int && rightType instanceof Type.Int) {

            left = adaptIntBoundary(bin.left(), left, Type.Int.INSTANCE);
            right = adaptIntBoundary(bin.right(), right, Type.Int.INSTANCE);

            String origin = originArgs(bin.span());
            return switch (op) {
                case ADD -> "intAdd(" + left + ", " + right + ", " + origin + ")";
                case SUB -> "intSub(" + left + ", " + right + ", " + origin + ")";
                case MUL -> "intMul(" + left + ", " + right + ", " + origin + ")";
                case DIV -> "intDiv(" + left + ", " + right + ", " + origin + ")";
                case MOD -> "intMod(" + left + ", " + right + ", " + origin + ")";
                case POW -> "intPow(" + left + ", " + right + ", " + origin + ")";
                case EQ -> "(" + left + " == " + right + ")";
                case NEQ -> "(" + left + " != " + right + ")";
                case LT -> "(" + left + " < " + right + ")";
                case LTE -> "(" + left + " <= " + right + ")";
                case GT -> "(" + left + " > " + right + ")";
                case GTE -> "(" + left + " >= " + right + ")";
                case AND -> "(" + left + " && " + right + ")";
                case OR -> "(" + left + " || " + right + ")";
            };
        }

        // Number arithmetic (mixed int/number widens like LuaJIT's number ops).
        if (leftType instanceof Type.Number || rightType instanceof Type.Number) {
            return switch (op) {
                case ADD -> "(" + left + " + " + right + ")";
                case SUB -> "(" + left + " - " + right + ")";
                case MUL -> "(" + left + " * " + right + ")";
                case DIV -> "(" + left + " / " + right + ")";
                case MOD -> "numMod(" + left + ", " + right + ")";
                case POW -> int32Mode
                    ? "numPow(" + left + ", " + right + ")"
                    : "java.lang.Math.pow(" + left + ", " + right + ")";
                case EQ -> "(" + left + " == " + right + ")";
                case NEQ -> "(" + left + " != " + right + ")";
                case LT -> "(" + left + " < " + right + ")";
                case LTE -> "(" + left + " <= " + right + ")";
                case GT -> "(" + left + " > " + right + ")";
                case GTE -> "(" + left + " >= " + right + ")";
                case AND -> "(" + left + " && " + right + ")";
                case OR -> "(" + left + " || " + right + ")";
            };
        }

        // Null equality: every null-typed value is the DEAL null value;
        // `null === null` is true (spec §Value equality). Operand side
        // effects were already hoisted into pre-statements by emitExpression,
        // so the emitted operands are null here. A null-typed ASSIGNMENT's
        // emitted code carries the assignment target's Java type (e.g.
        // `(m = null)` where `m: int | null` is Long-typed), so it is
        // widened to Object to compare against a Void-typed null operand.
        if (leftType instanceof Type.Null && rightType instanceof Type.Null) {
            String lc = bin.left() instanceof AssignmentExpr
                ? "(java.lang.Object) " + left : left;
            String rc = bin.right() instanceof AssignmentExpr
                ? "(java.lang.Object) " + right : right;
            return switch (op) {
                case EQ -> "(" + lc + " == " + rc + ")";
                case NEQ -> "(" + lc + " != " + rc + ")";
                default -> {
                    unsupported("operator " + op + " on null values", bin.span());
                    yield "null";
                }
            };
        }

        // Boolean equality (&& / || were handled by the short-circuit
        // branch above, which guards hoisted right-operand side effects).
        if (leftType instanceof Type.Boolean && rightType instanceof Type.Boolean) {
            return switch (op) {
                case EQ -> "(" + left + " == " + right + ")";
                case NEQ -> "(" + left + " != " + right + ")";
                default -> {
                    unsupported("operator " + op + " on booleans", bin.span());
                    yield "false";
                }
            };
        }

        // String equality and ordering. Ordering compares Unicode scalar
        // values (via the emitted scalarCompare helper): LuaJIT orders
        // bytewise in UTF-8, which is scalar-value order — including
        // supplementary characters, where String.compareTo's UTF-16
        // code-unit order diverges.
        if (leftType instanceof Type.String && rightType instanceof Type.String) {
            return switch (op) {
                case EQ -> "(" + left + ".equals(" + right + "))";
                case NEQ -> "(!" + left + ".equals(" + right + "))";
                case LT -> "(scalarCompare(" + left + ", " + right + ") < 0)";
                case LTE -> "(scalarCompare(" + left + ", " + right + ") <= 0)";
                case GT -> "(scalarCompare(" + left + ", " + right + ") > 0)";
                case GTE -> "(scalarCompare(" + left + ", " + right + ") >= 0)";
                default -> {
                    unsupported("operator " + op + " on strings", bin.span());
                    yield "\"\"";
                }
            };
        }

        if (leftType instanceof Type.Func && rightType instanceof Type.Func) {
            return switch (op) {
                case EQ -> "(" + left + " == " + right + ")";
                case NEQ -> "(" + left + " != " + right + ")";
                default -> {
                    unsupported("operator " + op + " on function values",
                        bin.span());
                    yield "false";
                }
            };
        }

        unsupported("operator " + op + " on operand types "
            + typeName(leftType) + " and " + typeName(rightType), bin.span());
        return "null";
    }

    private String emitNullableComparison(BinaryExpr bin, boolean eq) {
        Type leftType = typeOf(bin.left());
        Type rightType = typeOf(bin.right());
        boolean leftNullable = leftType instanceof Type.Nullable;
        boolean rightNullable = rightType instanceof Type.Nullable;
        List<String> codes = emitOperandsInOrder(
            List.of(bin.left(), bin.right()));
        String l = codes.get(0);
        String r = codes.get(1);
        if (leftNullable && rightNullable) {
            Type inner = ((Type.Nullable) leftType).inner();
            l = materializeIfEffectful(l, bin.left());
            r = materializeIfEffectful(r, bin.right());
            if (eq) {
                return "((" + l + " == null ? (" + r + " == null) : ("
                    + r + " != null && " + nullableEq(l, r, inner) + ")))";
            }
            return "((" + l + " == null ? (" + r + " != null) : ("
                + r + " == null || " + nullableNe(l, r, inner) + ")))";
        }
        if (leftNullable) {
            // The right side is a null-typed value: its observable side
            // effects (an inline assignment) must run before the
            // comparison — materialize it when it is not inert. The left
            // nullable operand is materialized FIRST (an inline effectful
            // left operand must run before the right operand's
            // materialized evaluation — LuaJIT evaluates left to right),
            // and the comparison itself then reads only inert values.
            l = materializeIfEffectful(l, bin.left());
            r = materializeIfEffectful(r, bin.right());
            return eq ? "(" + l + " == null)" : "(" + l + " != null)";
        }
        // Right nullable, left null-typed: the left operand's effects (a
        // hoisted call or an inline assignment) run before the right
        // operand, which the comparison references exactly once.
        l = materializeIfEffectful(l, bin.left());
        return eq ? "(" + r + " == null)" : "(" + r + " != null)";
    }

    /** Value equality of two non-null boxed nullable operands (the caller
     * guarded the null cases). */
    private String nullableEq(String l, String r, Type inner) {
        return switch (inner) {
            case Type.Int ignored -> l + (int32Mode ? ".intValue()" : ".longValue()")
                + " == " + r + (int32Mode ? ".intValue()" : ".longValue()");
            case Type.Number ignored -> l + ".doubleValue() == " + r + ".doubleValue()";
            case Type.Boolean ignored -> l + ".booleanValue() == " + r + ".booleanValue()";
            case Type.String ignored -> l + ".equals(" + r + ")";
            case Type.Bytes ignored -> "(" + l + " == " + r + ")";
            default -> "(" + l + " == " + r + ")";
        };
    }

    /** Value inequality of two non-null boxed nullable operands. */
    private String nullableNe(String l, String r, Type inner) {
        return switch (inner) {
            case Type.Int ignored -> l + (int32Mode ? ".intValue()" : ".longValue()")
                + " != " + r + (int32Mode ? ".intValue()" : ".longValue()");
            case Type.Number ignored -> l + ".doubleValue() != " + r + ".doubleValue()";
            case Type.Boolean ignored -> l + ".booleanValue() != " + r + ".booleanValue()";
            case Type.String ignored -> "(!" + l + ".equals(" + r + "))";
            case Type.Bytes ignored -> "(" + l + " != " + r + ")";
            default -> "(" + l + " != " + r + ")";
        };
    }

    /**
     * Emits a boolean {@code &&}/{@code ||} whose right operand may hoist
     * side-effecting statements, or whose operand can carry the Lua nil
     * of a past-end {@code boolean[]} read (see {@link #canYieldNil}).
     * When the right operand's emission hoisted statements, they are
     * guarded by the left operand so Java's short-circuit semantics hold
     * (the right operand must not be evaluated when the left operand
     * already decides the result), and the result is carried in a fresh
     * temporary:
     * <pre>
     *   boolean __sc0 = false;              // true for ||
     *   if (LEFT) { helper(); __sc0 = RIGHT; }   // if (!(LEFT)) for ||
     * </pre>
     * When the right operand has no hoisted statements, the plain
     * {@code (left && right)} form is emitted.
     *
     * <p>When either operand can yield nil, the result is a boxed
     * {@code java.lang.Boolean} temporary (null = the Lua nil): the
     * nil-capable operand is emitted as a nullable boxed temporary (a
     * boxed read helper call, or a nested nil-aware short-circuit temp),
     * the guard coerces the nil to falsy ({@code x != null &&
     * x.booleanValue()} — Lua's truthiness), and the result follows
     * Lua's {@code and}/{@code or}: {@code nil and x} → nil,
     * {@code nil or x} → x, {@code true and nil} → nil, {@code false or
     * nil} → nil. A plain inline-effectful left operand is materialized
     * once (the initializer and the guard both reference it), and the
     * right operand's hoisted statements — including a right-side boxed
     * read's helper call — stay inside the guard so a skipped right
     * operand never evaluates.
     */
    private String emitShortCircuit(BinaryExpr bin, boolean isAnd) {
        boolean leftNil = canYieldNil(bin.left());
        boolean rightNil = canYieldNil(bin.right());
        String left = emitBooleanNullableOperand(bin.left());
        int mark = preStatements.size();
        String right = emitBooleanNullableOperand(bin.right());
        boolean rightHoisted = preStatements.size() > mark;
        if (!leftNil && !rightNil && !rightHoisted) {
            return isAnd ? "(" + left + " && " + right + ")"
                         : "(" + left + " || " + right + ")";
        }
        // Extract the statements hoisted by the right operand and re-add
        // them inside a guard over the left operand, preserving their
        // relative order and evaluation order.
        List<PreLine> guarded = new ArrayList<>(
            preStatements.subList(mark, preStatements.size()));
        preStatements.subList(mark, preStatements.size()).clear();
        if (!leftNil && !rightNil) {
            // Plain boolean operands, hoisted right side effects only.
            String temp = nextShortCircuitName();
            preStatements.add(new PreLine(
                "boolean " + temp + " = " + (isAnd ? "false" : "true") + ";", 0));
            preStatements.add(new PreLine(
                isAnd ? "if (" + left + ") {" : "if (!" + left + ") {", 0));
            for (PreLine line : guarded) {
                preStatements.add(new PreLine(line.text(), line.extraIndent() + 1));
            }
            preStatements.add(new PreLine(temp + " = " + right + ";", 1));
            preStatements.add(new PreLine("}", 0));
            preStatementsDeclareTemps = true;
            return temp;
        }

        String leftCode = left;
        if (!leftNil && !isPureAfterEmission(bin.left())) {
            // A plain inline-effectful left operand (e.g. a call) is
            // referenced twice below (initializer + guard) — materialize
            // it once so its effects run exactly once.
            String lTemp = nextEvalTempName();
            preStatements.add(new PreLine(
                "boolean " + lTemp + " = " + left + ";", 0));
            preStatementsDeclareTemps = true;
            leftCode = lTemp;
        }
        String guard = leftNil
            ? "(" + leftCode + " != null && " + leftCode + ".booleanValue())"
            : leftCode;
        String init = leftNil ? leftCode
            : "java.lang.Boolean.valueOf(" + leftCode + ")";
        String temp = nextShortCircuitName();
        preStatements.add(new PreLine(
            "java.lang.Boolean " + temp + " = " + init + ";", 0));
        preStatements.add(new PreLine(
            isAnd ? "if (" + guard + ") {" : "if (!" + guard + ") {", 0));
        for (PreLine line : guarded) {
            preStatements.add(new PreLine(line.text(), line.extraIndent() + 1));
        }
        preStatements.add(new PreLine(temp + " = " + right + ";", 1));
        preStatements.add(new PreLine("}", 0));
        preStatementsDeclareTemps = true;
        return temp;
    }

    /** A fresh short-circuit temporary ({@code __sc0}, {@code __sc1}, …). */
    private String nextShortCircuitName() {
        String name = "__sc" + shortCircuitCounter;
        shortCircuitCounter++;
        return name;
    }

    /** A fresh evaluation-order temporary ({@code __t0}, {@code __t1}, …). */
    private String nextEvalTempName() {
        return "__t" + evalTempCounter++;
    }

    /**
     * True when the code emitted for {@code e} can no longer have an
     * observable effect at evaluation time (all of it is either inert or
     * already sequenced into {@link #preStatements}): literals and
     * identifier reads are inert; a null-typed call is always hoisted into
     * a pre-statement, so its emitted code is the inert {@code null}; a
     * non-null call, an assignment (its inline target write), checked int
     * arithmetic (E8004/E8005/E8006 can raise at evaluation time), and any
     * combination containing such a sub-expression stay effectful. When a
     * later sibling hoists, every earlier operand that is NOT pure after
     * emission must be materialized into a temporary before the hoisted
     * statements — otherwise its inline effects would run after them,
     * inverting LuaJIT's strict left-to-right evaluation (including the
     * nested case {@code f(g() + k(console.log("y")), console.log("x"))},
     * where the inline {@code k(…)} call inside the first operand must
     * still run before the second operand's hoisted print).
     */
    private boolean isPureAfterEmission(ExpressionNode e) {
        return switch (e) {
            case LiteralExpr lit -> true;
            case IdentifierExpr id -> true;
            case CallExpr call -> typeOf(call) instanceof Type.Null;
            case AssignmentExpr ae -> false;
            case BinaryExpr bin -> {
                if (typeOf(bin) instanceof Type.Int
                        && (bin.op() == BinaryOp.ADD || bin.op() == BinaryOp.SUB
                            || bin.op() == BinaryOp.MUL || bin.op() == BinaryOp.DIV
                            || bin.op() == BinaryOp.MOD || bin.op() == BinaryOp.POW)) {
                    yield false;
                }
                yield isPureAfterEmission(bin.left())
                    && isPureAfterEmission(bin.right());
            }
            case UnaryExpr u -> {
                if (u.op() == UnaryOp.NEG && typeOf(u) instanceof Type.Int) {
                    yield false;
                }
                yield isPureAfterEmission(u.expr());
            }
            // An array read can raise at evaluation time (E8002 negative
            // index / E8001 past the end), so it is never pure after
            // emission — a later hoisting operand must not run first.
            case IndexExpr idx -> false;
            // An array literal is inert apart from its elements: the
            // allocation has no observable effect in scope (array
            // equality is out of scope), so purity follows the elements.
            case ArrayLiteralExpr al -> {
                boolean pure = true;
                for (ExpressionNode elem : al.elements()) {
                    pure &= isPureAfterEmission(elem);
                }
                yield pure;
            }
            // Unsupported forms record an E6000 and emit no side effects,
            // but their emitted code is a placeholder — treat as effectful
            // so ordering never depends on them.
            default -> false;
        };
    }

    /**
     * Emits {@code nodes} (in evaluation order) and returns their inline
     * code strings, preserving DEAL's left-to-right evaluation order when
     * any operand hoists a side-effecting pre-statement. Hoisted
     * statements are flushed before the containing statement, so an
     * earlier <em>inline</em> side-effecting operand would otherwise run
     * after them — {@code f(g(), console.log("x"))} printed "x" before
     * {@code g()} ran, while LuaJIT evaluates arguments left to right
     * ("g-ran" first). Every operand that is followed by a hoisting
     * operand and whose emitted code is not pure after emission (an
     * inline call, an inline assignment, checked int arithmetic — even
     * inside a nested combination that hoisted other parts of itself) is
     * materialized into a fresh temporary assigned at the earliest hoist
     * start among the operands AFTER it — the start of the next hoisting
     * operand's pre-statement segment, i.e. immediately after the
     * operand's own evaluation (its own hoisted statements, when it
     * hoisted itself, already precede that point) and before the first
     * hoisted statement of every later operand. Operands after the last
     * hoist stay inline (the flush already precedes them), and operands
     * whose emitted code is inert (literals, reads, fully hoisted calls)
     * are left alone. The declarations
     * reference no user-controlled names ({@code __t<n>} is unreachable from
     * {@link #javaName}), and {@link #preStatementsDeclareTemps} is set so
     * a module-level field initializer referencing a materialized
     * temporary is routed through a static-block assignment (a class-body
     * initializer cannot see a block-local declaration).
     */
    private List<String> emitOperandsInOrder(List<ExpressionNode> nodes) {
        return emitOperandsInOrder(nodes, null, null);
    }

    /**
     * {@link #emitOperandsInOrder(List)} with a per-operand target type:
     * {@code targets.get(i)} is the contextual target of operand
     * {@code i} — for a call argument the callee's parameter type, for
     * other operand lists a direct index-read operand's read-site target
     * (an assignment RHS target, an array-literal element type, a
     * class-construction field type) or {@code null} for no target.
     * Two target consumers exist:
     * <ul>
     * <li>an operand whose static type is a narrower function type than
     * its target parameter (only arity extension is assignable here) is
     * a call argument LuaJIT's parameter boundary check rejects with
     * E8010 — the operand's VALUE is evaluated first (materialized into
     * a temporary at the argument's evaluation position) and the emitted
     * checking wrapper's construction then raises the same error after
     * any materialized later operands (see below), exactly like
     * LuaJIT's evaluate-all-arguments-then-check order;</li>
     * <li>a DIRECT index-read operand at a nullable target is emitted
     * with {@link #emitExpressionFor}, which yields the DEAL null past
     * the end instead of raising the element-typed E8001. Only direct
     * reads consume the target — an operator nested between the
     * boundary and the read types the read at its own operand position,
     * so the element-typed read stays.</li>
     * </ul>
     */
    private List<String> emitOperandsInOrder(List<ExpressionNode> nodes,
                                             List<Type> targets) {
        return emitOperandsInOrder(nodes, targets, null);
    }

    /**
     * {@link #emitOperandsInOrder(List, List)} with a per-operand
     * declared-boundary origin (D1/D3): {@code origins.get(i)} is the
     * authoritative origin the operand crosses — for a call argument
     * the callee's declared parameter-type annotation (the DECLARING
     * module's artifact for a cross-module callee) — pushed for the
     * duration of that operand's emission only.</p>
     */
    private List<String> emitOperandsInOrder(List<ExpressionNode> nodes,
                                             List<Type> targets,
                                             List<BoundaryOrigin> origins) {
        List<String> codes = new ArrayList<>(nodes.size());
        List<Integer> hoistStarts = new ArrayList<>(nodes.size());
        boolean[] throwsAtEval = new boolean[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            final int operandIndex = i;
            int before = preStatements.size();
            Type target = targets != null && i < targets.size()
                ? targets.get(i) : null;
            BoundaryOrigin operandOrigin = origins != null
                && operandIndex < origins.size()
                    ? origins.get(operandIndex) : null;
            if (target instanceof Type.Func tf
                    && typeOf(nodes.get(i)) instanceof Type.Func af
                    && !Types.equals(tf, af)) {
                // A call argument whose static function type is narrower
                // than the parameter: the checker admits only arity
                // extension here, and LuaJIT's parameter boundary check
                // raises E8010 when the call is made — the wrapper's
                // construction raises it, at the argument's evaluation
                // position.
                if (Types.isAssignable(af, tf)
                        && af.paramTypes().size() < tf.paramTypes().size()) {
                    codes.add(withBoundaryOrigin(operandOrigin,
                        () -> emitSignatureCheckWrapper(tf, af,
                            nodes.get(operandIndex))));
                    throwsAtEval[i] = true;
                } else {
                    unsupported("call argument function value with a "
                        + "signature not assignable to the parameter "
                        + "(checker should have rejected it)",
                        nodes.get(i).span());
                    codes.add("null");
                }
            } else {
                codes.add(withBoundaryOrigin(operandOrigin,
                    () -> emitExpressionFor(nodes.get(operandIndex),
                        target)));
            }
            hoistStarts.add(preStatements.size() > before ? before : -1);
        }
        // Process the hoisting operands right to left in contiguous
        // groups: for a hoisting operand j, the operands materialized
        // before j's first hoisted statement are exactly the earlier
        // operands from the previous hoisting operand (inclusive) up to
        // j — the previous hoisting operand's own inline remainder still
        // needs materialization immediately after its hoisted statements,
        // and no earlier operand may be anchored later than that earliest
        // boundary. Each materialization lands at the start of j's
        // hoisted statements, i.e. the earliest hoist start among the
        // operands AFTER the materialized operand: immediately after the
        // operand's own evaluation (its hoisted statements, when it
        // hoisted itself, already precede that point) and before the
        // first hoisted statement of every later operand. Anchoring at
        // the LAST hoisting operand's start instead would run the
        // operand's inline effects after an intermediate operand's
        // hoisted side effects (the
        // getArr("a", xs)[pick("i", console.log("b"))] = … miscompilation:
        // printed b, a instead of a, b). Within a group the
        // materializations are inserted consecutively in operand order,
        // so operand i's temporary assignment precedes operand i + 1's.
        // Processing groups right to left makes later insertions land
        // first and earlier insertions (smaller indices) only shift them
        // rightward — never reorder them. Every operand whose emitted
        // code is not pure after emission is materialized — including an
        // operand that hoisted itself but still carries an inline call
        // after its own hoisted statements (a nested combination), whose
        // inline effects would otherwise run after operand j's hoisted
        // statements. A check-position operand (throwsAtEval) is the
        // one exception: its inline code is the E8010 raising
        // construction, which deliberately stays at the argument
        // position (see the skip inside the loop below) — its VALUE is
        // already a pre-statement temporary at the operand's own
        // evaluation position, so nothing observable is left to anchor.
        boolean[] materialized = new boolean[nodes.size()];
        List<Integer> hoistOps = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            if (hoistStarts.get(i) >= 0) hoistOps.add(i);
        }
        for (int g = hoistOps.size() - 1; g >= 0; g--) {
            int j = hoistOps.get(g);
            int first = g == 0 ? 0 : hoistOps.get(g - 1);
            int insertAt = hoistStarts.get(j);
            for (int i = first; i < j; i++) {
                if (materialized[i]) continue;
                // A check-position operand's inline code is the RAISING
                // wrapper construction (emitSignatureCheckWrapper), and
                // its VALUE is already materialized into an
                // actual-shape temporary at the operand's own evaluation
                // position. Materializing the raising construction here
                // would (a) type the temporary with the ACTUAL wrapper
                // shape while the code is the TARGET wrapper subclass
                // (javac rejects the artifact after the CLI reported
                // success) and (b) run the raise before operand j's
                // hoisted statements — inverting LuaJIT's
                // evaluate-then-check order. The raise must stay INLINE
                // at the argument position: all pre-statements
                // (including j's hoisted statements and j's
                // check-loop-materialized remainder) flush first, so the
                // raise happens after every later argument's evaluation.
                if (throwsAtEval[i]) continue;
                if (isPureAfterEmission(nodes.get(i))) continue;
                // The temporary's Java type follows the EMITTED code
                // shape, not just the static type — see
                // materializationTempType.
                String javaType = materializationTempType(nodes.get(i),
                    targets == null ? null : targets.get(i));
                if (javaType == null) continue; // diagnostic already recorded
                String temp = nextEvalTempName();
                preStatements.add(insertAt++, new PreLine(
                    javaType + " " + temp + " = " + codes.get(i) + ";", 0));
                codes.set(i, temp);
                materialized[i] = true;
                preStatementsDeclareTemps = true;
            }
        }
        // A check-position operand whose evaluation raises (an arity
        // signature check) must not raise before every LATER argument's
        // evaluation completes: LuaJIT evaluates all arguments left to
        // right and only then checks the parameters at callee entry, so
        // `apply(inc, mark("x", 41))` runs mark first and raises E8010
        // after it. Every later operand that is not pure after emission
        // (an inline side-effecting call, a checked arithmetic result, or
        // a hoisted call that still carries an inline remainder) is
        // materialized into a temporary appended after its own hoisted
        // statements — its full evaluation runs before the raising
        // wrapper's construction, exactly like LuaJIT. Inert operands
        // (literals, reads, fully hoisted null-typed calls) are left
        // inline: their evaluation has no observable order.
        boolean[] materializedForCheck = new boolean[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            if (!throwsAtEval[i]) continue;
            for (int j = i + 1; j < nodes.size(); j++) {
                if (materializedForCheck[j]) continue;
                // A later operand that is itself a check position has
                // its VALUE already materialized into an actual-shape
                // temporary at its own evaluation position
                // (emitSignatureCheckWrapper); that pre-statement
                // precedes operand i's inline raise, so operand j's
                // value evaluates before the raise — exactly LuaJIT's
                // evaluate-all-arguments-then-check order. Its INLINE
                // code is the raising construction: materializing it
                // here would (a) type the temporary with the ACTUAL
                // shape while the code is the TARGET wrapper subclass
                // (javac rejects the artifact) and (b) raise operand
                // j's check before operand i's — inverting LuaJIT's
                // parameter-order E8010 (the FIRST mismatched parameter
                // raises).
                if (throwsAtEval[j]) continue;
                if (isPureAfterEmission(nodes.get(j))) continue;
                // The temporary's Java type follows the EMITTED code
                // shape, exactly like the main materialization loop: a
                // nil-yielding index read at a nullable target carries
                // the boxed element type, never the unboxed storage
                // type.
                String javaType = materializationTempType(nodes.get(j),
                    targets == null ? null : targets.get(j));
                if (javaType == null) continue; // diagnostic already recorded
                String temp = nextEvalTempName();
                preStatements.add(new PreLine(
                    javaType + " " + temp + " = " + codes.get(j) + ";", 0));
                codes.set(j, temp);
                materializedForCheck[j] = true;
                preStatementsDeclareTemps = true;
            }
        }
        return codes;
    }

    /**
     * The Java type of a materialized operand temporary. The temporary
     * must match the EMITTED code shape, never just the static type:
     * <ul>
     * <li>a null-typed effectful operand (an assignment like
     * {@code (m = null)} whose emitted code carries the assignment
     * TARGET's boxed Java type) materializes into an Object temporary —
     * {@code javaLocalType(null)} is {@code java.lang.Void} and javac
     * rejects {@code java.lang.Void __t0 = (m = null);} after the CLI
     * reported success;</li>
     * <li>a nil-yielding read (a direct {@code T[]} read emitted at a
     * {@code T | null} target) carries the boxed element type
     * ({@code java.lang.Long} under {@code LEGACY_SAFE_INT},
     * {@code java.lang.Integer} under {@code DEAL_V1_2_INT32} for an int
     * read), never the unboxed storage type;</li>
     * <li>a nil-aware {@code &&}/{@code ||} operand's emitted code is a
     * boxed {@code java.lang.Boolean} temporary (null = the Lua nil) —
     * a {@code boolean} declaration would auto-unbox and NPE on
     * null.</li>
     * </ul>
     */
    private String materializationTempType(ExpressionNode node,
                                           Type target) {
        Type t = typeOf(node);
        if (t instanceof Type.Null) return "java.lang.Object";

        if (int32Mode && t instanceof Type.Int
                && intValueCodeIsWiderOrBoxed(node)) {
            return intValueEmittedJavaType(node);
        }
        if (node instanceof IndexExpr idx
                && isNilYieldingReadTarget(idx, target)) {
            Type element = ((Type.Array) typeOf(idx.array())).element();
            String boxed = boxedArrayJavaType(element, idx.span());
            if (boxed != null) return boxed;
        }
        if (canYieldNil(node) && !isPrimitiveArrayRead(node)) {
            return "java.lang.Boolean";
        }
        return javaLocalType(t, node.span());
    }

    private String adaptIntBoundary(ExpressionNode e, String code,
                                    Type target) {
        if (int32Mode && isIntBoundaryTarget(target)
                && intValueCodeIsWiderOrBoxed(e)) {
            // The only wider producer reaching this seam is the locked
            // time selector's retained long nowMillis expression: its
            // declared-int boundary raise is the ONE sanctioned
            // span-less raise in the emission (D5). The one-argument
            // entry is the explicit absent-origin path (file=null,
            // line=-1, column=-1) — no origin literal is ever added at
            // this site, permanently.
            return "checkInt(" + code + ")";
        }
        return code;
    }

    /** True when {@code target} is an int-typed declared boundary —
     * {@code int} itself or {@code int | null} (the value crossing into
     * the nullable boxed carrier is still an int value). */
    private boolean isIntBoundaryTarget(Type target) {
        if (target instanceof Type.Int) return true;
        return target instanceof Type.Nullable n
            && n.inner() instanceof Type.Int;
    }

    private boolean intValueCodeIsWiderOrBoxed(ExpressionNode e) {
        if (!(e instanceof CallExpr call)
                || !(call.callee() instanceof MemberAccessExpr mae)
                || !(mae.object() instanceof IdentifierExpr id)) {
            return false;
        }
        return "std/time".equals(importAliases.get(id.name()));
    }

    /**
     * The emitted Java type of an int-typed expression whose code is
     * wider under {@code DEAL_V1_2_INT32} ({@code long} for the retained
     * time expression, {@code int} for everything else — host call
     * results included, whose wrappers return the primitive int carrier
     * per {@link #intValueCodeIsWiderOrBoxed}) — used by materialized
     * temporaries and dummy-local discards, whose Java declaration type
     * must follow the emitted code shape, never the declared carrier (a
     * mismatch there is exactly the javac-rejected artifact D3 forbids).
     */
    private String intValueEmittedJavaType(ExpressionNode e) {
        if (e instanceof CallExpr call
                && call.callee() instanceof MemberAccessExpr mae
                && mae.object() instanceof IdentifierExpr id) {
            if ("std/time".equals(importAliases.get(id.name()))) {
                return "long";
            }
        }
        return "int";
    }

    /**
     * Emits an expression against an optional target type. When the
     * expression's static type is a function type assignable to a
     * DIFFERENT target function type (arity extension: the actual has
     * fewer parameters than the target), the position decides the
     * lowering — exactly like the Lua backend:
     * <ul>
     * <li>variable initializers and assignments (adapter positions) wrap
     * the value in a delegating arity-extension adapter, so the produced
     * value carries the target signature and silently ignores the extra
     * parameters;</li>
     * <li>return values and call arguments (check positions) emit the
     * runtime signature check LuaJIT performs at the return/parameter
     * boundary: the wrapper's construction raises E8010 with the exact
     * "function signature mismatch" contract.</li>
     * </ul>
     */
    private String emitTargeted(ExpressionNode e, Type target,
            boolean checkPosition) {
        if (target instanceof Type.Func tf && typeOf(e) instanceof Type.Func) {
            return emitFunctionValue(tf, (Type.Func) typeOf(e), e, checkPosition);
        }
        return emitExpressionFor(e, target);
    }

    /**
     * Emits a function value of static type {@code actual} against the
     * target signature {@code target}. Equal signatures pass the plain
     * wrapper value through; arity extension (fewer actual parameters,
     * identical prefix types and return type — the only non-equal
     * assignable shape {@code Types.isAssignable} admits) lowers to the
     * delegating adapter at adapter positions and to the E8010 signature
     * check at check positions. Anything else is a checker bug —
     * defensive E6000.
     */
    private String emitFunctionValue(Type.Func target, Type.Func actual,
            ExpressionNode value, boolean checkPosition) {
        // Align checker-held class identities with the backend-held
        // local-class identities before comparing: the single-module
        // harness adapters type with the source filename while codegen
        // runs with the module path, so the checker's local class
        // identity and the backend's are different records for the
        // SAME class — and the emitted runtime class carries the
        // backend identity text, so the compared signatures (and the
        // wrapper descriptors they produce) must use the backend
        // spellings. Foreign and builtin identities pass through
        // unchanged.
        target = normalizeLocalClassIdentities(target);
        actual = normalizeLocalClassIdentities(actual);
        if (Types.equals(target, actual)) {
            return emitExpression(value);
        }
        if (Types.isAssignable(actual, target)
                && actual.paramTypes().size() < target.paramTypes().size()) {
            return checkPosition
                ? emitSignatureCheckWrapper(target, actual, value)
                : emitArityAdapter(target, actual, value);
        }
        unsupported("function value with a signature not assignable to the "
            + "target signature (checker should have rejected it)",
            value.span());
        return "null";
    }

    /** Re-keys the checker-held class identities inside a function
     * signature onto the backend-held local-class identities
     * ({@link #localClassIdentity}): the single-module harness
     * adapters type with the source filename while codegen runs with
     * the module path (the locality predicate accepts both), so a
     * checker-built and an annotation-built signature can name the
     * same local class with two different identity records — the
     * comparison and the produced wrapper descriptors must use the
     * backend spellings (the emitted runtime class carries the backend
     * identity text). Foreign-module classes and the builtin Error
     * identity pass through unchanged. */
    private Type.Func normalizeLocalClassIdentities(Type.Func f) {
        List<Type> params = new ArrayList<>();
        boolean changed = false;
        for (Type p : f.paramTypes()) {
            Type n = normalizeLocalClassType(p);
            params.add(n);
            if (n != p) changed = true;
        }
        Type rt = normalizeLocalClassType(f.returnType());
        if (rt != f.returnType()) changed = true;
        return changed ? new Type.Func(params, rt, f.isAsync()) : f;
    }

    /** One type of {@link #normalizeLocalClassIdentities}. */
    private Type normalizeLocalClassType(Type t) {
        return switch (t) {
            case Type.Class c -> {
                if (!isLocalClassType(c)
                        || c.identity().moduleIdentity()
                            instanceof CanonicalModuleIdentity.BuiltinModule) {
                    yield c;
                }
                CanonicalClassIdentity mine = localClassIdentity(c.name());
                yield mine.equals(c.identity())
                    ? c : Types.classType(c.name(), mine);
            }
            case Type.Array a -> {
                Type n = normalizeLocalClassType(a.element());
                yield n == a.element() ? a : new Type.Array(n);
            }
            case Type.Nullable n -> {
                Type inner = normalizeLocalClassType(n.inner());
                yield inner == n.inner() ? n : new Type.Nullable(inner);
            }
            case Type.Func f2 -> normalizeLocalClassIdentities(f2);
            default -> t;
        };
    }

    /**
     * Emits the runtime function-signature check for a check position
     * (return value or call argument) whose static function type is a
     * narrower arity-extension shape than the declared target. The value
     * expression is evaluated FIRST — materialized into a fresh
     * effectively-final temporary declared at its evaluation position
     * (for a call argument that is the argument's position in
     * {@link #emitOperandsInOrder}'s materialization order, for a return
     * value the return's evaluation) — and only then does the emitted
     * anonymous subclass of the TARGET wrapper class's instance
     * initializer raise E8010 with LuaJIT's exact "function signature
     * mismatch: expected …, got …" contract. This reproduces LuaJIT's
     * strict evaluate-then-check order (spec §Operational semantics rule
     * 2 and the return-value contract): a side effect in the checked
     * value expression always runs — never silently dropped — and the
     * raise happens where LuaJIT's parameter/return boundary check
     * raises (after any materialized later operands). The invoke body is
     * unreachable and throws to satisfy the abstract method.
     *
     * <p>The returned construction is the operand's INLINE code and must
     * stay at the argument position: {@link #emitOperandsInOrder}'s two
     * materialization loops deliberately skip check-position operands,
     * because materializing the raising construction would type a
     * temporary with the ACTUAL wrapper shape while the code is the
     * TARGET wrapper subclass (a javac-rejected artifact after the CLI
     * reported success) and would run the raise out of parameter order
     * — LuaJIT evaluates every argument's value left to right and only
     * then raises the FIRST mismatched parameter's E8010, so with two
     * arity-mismatched function arguments the first parameter's check
     * must raise and the second operand's value must still evaluate
     * first (its own value temporary, declared here, does that).</p>
     */
    private String emitSignatureCheckWrapper(Type.Func target, Type.Func actual,
            ExpressionNode value) {
        String shape = registerWrapperShape(target);
        if (shape == null) {
            unsupported("function values whose signature contains "
                + "unsupported carriers (table | null positions and "
                + "host-class carriers — deferred to the ISSUE-0110 "
                + "descriptor join)", value.span());
            return "null";
        }
        String actualShape = registerWrapperShape(actual);
        if (actualShape == null) {
            unsupported("function values whose signature contains "
                + "unsupported carriers (table | null positions and "
                + "host-class carriers — deferred to the ISSUE-0110 "
                + "descriptor join)", value.span());
            return "null";
        }
        // Evaluate the value expression first (strict left-to-right /
        // evaluate-then-check). The temporary's initializer runs at this
        // operand's evaluation position — before any materialized later
        // operand and before the raising construction below — exactly
        // like LuaJIT's value evaluation preceding the boundary check.
        String valueTemp = nextFunctionValueTempName();
        preStatements.add(new PreLine(actualShape + " " + valueTemp + " = "
            + emitExpression(value) + ";", 0));
        preStatementsDeclareTemps = true;
        StringBuilder sb = new StringBuilder("new ").append(shape)
            .append("() { { if (!checkSig(")
            .append(quoteJavaString(fnDescriptor(target))).append(", ")
            .append(quoteJavaString(fnDescriptor(actual)))
            .append(")) { throw new DealError(\"E8010\", ")
            .append("\"function signature mismatch: expected ")
            .append(fnDescriptor(target)).append(", got ")
            .append(fnDescriptor(actual)).append("\", ")
            .append(signatureCheckOriginArgs()).append(", ")
            .append(quoteJavaString(fnDescriptor(target))).append(", ")
            .append(quoteJavaString(fnDescriptor(actual)))
            .append(", null, null); } } @Override ");
        sb.append(javaReturnType(target.returnType(), value.span()))
            .append(" invoke(");
        for (int i = 0; i < target.paramTypes().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(javaLocalType(target.paramTypes().get(i), value.span()))
                .append(" p").append(i);
        }
        sb.append(") { throw new java.lang.AssertionError(\"unreachable\"); } }");
        return sb.toString();
    }

    private String emitArityAdapter(Type.Func target, Type.Func actual,
            ExpressionNode value) {
        String inner;
        if (value instanceof IdentifierExpr id) {
            String mapped = localJavaName(id.name());
            Symbol sym = symbols.resolve(id.name());
            if (mapped == null && sym instanceof Symbol.FunctionSymbol
                    && moduleFunctions.containsKey(id.name())) {
                inner = qualifiedStatic(javaName(id.name())) + "("
                    + overlappingAdapterArgs(actual.paramTypes().size()) + ")";
            } else if (mapped == null && sym instanceof Symbol.IntrinsicSymbol) {
                inner = switch (id.name()) {
                    case "int" -> "intFromNumber(p0)";
                    case "number" -> "numberFromInt(p0)";
                    default -> {
                        unsupported("intrinsic '" + id.name()
                            + "' used as a value", id.span());
                        yield "null";
                    }
                };
            } else if (moduleFieldIndices.containsKey(id.name())
                    && !shadowedByFunctionLocal(id.name())) {
                // A module field holding a function value: read the static
                // field LIVE on every invoke — exactly like LuaJIT's
                // adapter body re-reading the binding — so a reassignment
                // after the adapter's creation retargets the adapter.
                inner = (mapped != null ? mapped
                        : qualifiedStatic(javaName(id.name())))
                    + ".invoke("
                    + overlappingAdapterArgs(actual.paramTypes().size()) + ")";
            } else if (mapped != null) {

                if (capturedBindingReassignedInBody(id.name())) {
                    unsupported("an arity-extension adapter over the "
                        + "function-typed local/parameter '" + id.name()
                        + "' that the enclosing function body reassigns "
                        + "(LuaJIT's adapter reads the binding live on "
                        + "every invoke; a Java anonymous class cannot "
                        + "capture a reassigned local — deferred to "
                        + "ISSUE-0110)", id.span());
                    return "null";
                }
                inner = snapshotFunctionValue(actual, value);
            } else {
                unsupported("an arity-extension adapter over the function "
                    + "value '" + id.name() + "' that is not a module "
                    + "function, intrinsic, module field, or visible local "
                    + "binding", id.span());
                return "null";
            }
        } else {
            unsupported("an arity-extension adapter over a non-identifier "
                + "function-value expression (LuaJIT re-evaluates the "
                + "expression on every invoke; the JVM slice cannot yet "
                + "emit that — deferred to ISSUE-0110)", value.span());
            return "null";
        }
        String shape = registerWrapperShape(target);
        if (shape == null) {
            unsupported("function values whose signature contains "
                + "unsupported carriers (table | null positions and "
                + "host-class carriers — deferred to the ISSUE-0110 "
                + "descriptor join)", value.span());
            return "null";
        }
        StringBuilder sb = new StringBuilder("new ").append(shape)
            .append("() { @Override ");
        sb.append(javaReturnType(target.returnType(), value.span()))
            .append(" invoke(");
        for (int i = 0; i < target.paramTypes().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(javaLocalType(target.paramTypes().get(i), value.span()))
                .append(" p").append(i);
        }
        sb.append(") { ");
        if (target.returnType() instanceof Type.Null) {
            sb.append(inner).append("; } }");
        } else {
            sb.append("return ").append(inner).append("; } }");
        }
        return sb.toString();
    }

    /** True when some non-base scope map (a function parameter or local)
     * currently binds {@code name}, i.e. a module field of the same name
     * is shadowed and the identifier refers to the local binding. The
     * base (module-level) scope map is the deque's last element. */
    private boolean shadowedByFunctionLocal(String name) {
        for (Map<String, String> scope : localScopes) {
            if (scope == localScopes.peekLast()) break;
            if (scope.containsKey(name)) return true;
        }
        return false;
    }

    private boolean capturedBindingReassignedInBody(String name) {
        if (currentFunctionBody == null) return false;
        Deque<Set<String>> locals = new ArrayDeque<>();
        locals.push(new LinkedHashSet<>(currentFunctionParams));
        return statementsAssignLocal(currentFunctionBody, locals, name);
    }

    private boolean statementsAssignLocal(List<StatementNode> stmts,
            Deque<Set<String>> locals, String name) {
        for (StatementNode stmt : stmts) {
            if (statementAssignsLocal(stmt, locals, name)) return true;
        }
        return false;
    }

    private boolean blockAssignsLocal(Block b, Deque<Set<String>> locals,
            String name) {
        locals.push(new LinkedHashSet<>());
        boolean found = statementsAssignLocal(b.statements(), locals, name);
        locals.pop();
        return found;
    }

    private boolean statementAssignsLocal(StatementNode stmt,
            Deque<Set<String>> locals, String name) {
        return switch (stmt) {
            case VariableDeclaration vd -> {
                if (exprAssignsLocal(vd.initializer(), locals, name)) {
                    yield true;
                }
                locals.peek().add(vd.name());
                yield false;
            }
            case ReturnStatement rs -> rs.expr().isPresent()
                && exprAssignsLocal(rs.expr().get(), locals, name);
            case ExpressionStatement es ->
                exprAssignsLocal(es.expr(), locals, name);
            case IfStatement is -> {
                if (exprAssignsLocal(is.condition(), locals, name)
                        || blockAssignsLocal(is.thenBlock(), locals, name)) {
                    yield true;
                }
                if (is.elseBranch().isPresent()) {
                    switch (is.elseBranch().get()) {
                        case Either.Left<IfStatement, Block> left -> {
                            if (statementAssignsLocal(left.value(), locals, name)) {
                                yield true;
                            }
                        }
                        case Either.Right<IfStatement, Block> right -> {
                            if (blockAssignsLocal(right.value(), locals, name)) {
                                yield true;
                            }
                        }
                    }
                }
                yield false;
            }
            case WhileStatement ws -> {
                if (exprAssignsLocal(ws.condition(), locals, name)) {
                    yield true;
                }
                yield blockAssignsLocal(ws.body(), locals, name);
            }
            case Block b -> blockAssignsLocal(b, locals, name);
            // Function declarations and unsupported kinds (for/for-of,
            // try, classes, …) are separate scopes or rejected with E6000
            // when emitted; nothing to walk here.
            default -> false;
        };
    }

    private boolean exprAssignsLocal(ExpressionNode e,
            Deque<Set<String>> locals, String name) {
        return switch (e) {
            case AssignmentExpr ae -> {
                if (ae.target() instanceof IdentifierExpr id
                        && id.name().equals(name)
                        && isLocallyBound(locals, name)) {
                    yield true;
                }
                boolean found = exprAssignsLocal(ae.value(), locals, name);
                if (!found) {
                    // A non-identifier target's sub-expressions are value
                    // positions evaluated at the assignment: `t[g = dbl]
                    // = v` or `(g = dbl).x = v` reassigns the binding
                    // exactly like a bare statement assignment.
                    found = switch (ae.target()) {
                        case IndexExpr idx ->
                            exprAssignsLocal(idx.array(), locals, name)
                                || exprAssignsLocal(idx.index(), locals, name);
                        case MemberAccessExpr mae ->
                            exprAssignsLocal(mae.object(), locals, name);
                        default -> false;
                    };
                }
                yield found;
            }
            case BinaryExpr bin -> exprAssignsLocal(bin.left(), locals, name)
                || exprAssignsLocal(bin.right(), locals, name);
            case UnaryExpr u -> exprAssignsLocal(u.expr(), locals, name);
            case CallExpr call -> {
                boolean found = exprAssignsLocal(call.callee(), locals, name);
                for (ExpressionNode arg : call.args()) {
                    if (found) break;
                    found = exprAssignsLocal(arg, locals, name);
                }
                yield found;
            }

            case AwaitExpression aw ->
                exprAssignsLocal(aw.callee(), locals, name);
            case MemberAccessExpr mae ->
                exprAssignsLocal(mae.object(), locals, name);
            case IndexExpr idx -> exprAssignsLocal(idx.array(), locals, name)
                || exprAssignsLocal(idx.index(), locals, name);
            case ArrayLiteralExpr al -> {
                boolean found = false;
                for (ExpressionNode elem : al.elements()) {
                    if (exprAssignsLocal(elem, locals, name)) {
                        found = true;
                        break;
                    }
                }
                yield found;
            }
            // Table-literal and class-construction property values are
            // value positions evaluated at the literal/construction site:
            // an assignment hidden inside one (`let t = { x: (g = dbl)
            // }`) reassigns the binding exactly like a bare statement
            // assignment — the adapter's snapshot would silently go
            // stale if the scan missed it.
            case ObjectLiteralExpr ol -> {
                boolean found = false;
                for (Property prop : ol.properties()) {
                    if (exprAssignsLocal(prop.value(), locals, name)) {
                        found = true;
                        break;
                    }
                }
                yield found;
            }
            case TemplateLiteralExpr tl -> {
                boolean found = false;
                for (ExpressionNode part : tl.parts()) {
                    if (exprAssignsLocal(part, locals, name)) {
                        found = true;
                        break;
                    }
                }
                yield found;
            }
            // Literals and unsupported forms (rejected later) hold no writes.
            default -> false;
        };
    }

    /** Snapshots a never-reassigned local/parameter function value into
     * an effectively-final temporary (declared in the pre-statements,
     * evaluated in source order at adapter-creation time) and returns the
     * temporary's {@code .invoke(...)} delegation for the adapter body.
     * Only called after {@link #capturedBindingReassignedInBody} proved
     * the enclosing function body never reassigns the binding, so the
     * snapshot equals LuaJIT's live read forever. */
    private String snapshotFunctionValue(Type.Func actual, ExpressionNode value) {
        String shape = registerWrapperShape(actual);
        if (shape == null) {
            unsupported("function values whose signature contains "
                + "unsupported carriers (table | null positions and "
                + "host-class carriers — deferred to the ISSUE-0110 "
                + "descriptor join)", value.span());
            return "null";
        }
        String tmp = nextFunctionValueTempName();
        preStatements.add(new PreLine(shape + " " + tmp + " = "
            + emitExpression(value) + ";", 0));
        preStatementsDeclareTemps = true;
        return tmp + ".invoke(" + overlappingAdapterArgs(actual.paramTypes().size())
            + ")";
    }

    /** The {@code p0, p1, …} references the adapter forwards to the
     * actual function (its overlapping parameter prefix). */
    private static String overlappingAdapterArgs(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append("p").append(i);
        }
        return sb.toString();
    }

    /** A fresh function-value snapshot temporary ({@code __fn0},
     * {@code __fn1}, …). Unreachable from {@link #javaName}. */
    private String nextFunctionValueTempName() {
        return "__fn" + functionValueTempCounter++;
    }

    private String emitUnary(UnaryExpr u) {
        return switch (u.op()) {
            case NOT -> {
                // LuaJIT's `not` coerces the operand's nil (a past-end
                // boolean[] read, or a nil-aware && / || result) to true
                // (`not nil` is true), so no E8001 is raised in this
                // position — the operand is emitted as a nullable boxed
                // temporary (always a temporary: a boxed read temp or a
                // short-circuit temp, so referencing it twice evaluates
                // it once) and the coercion is `(x == null ||
                // !x.booleanValue())`: not nil → true, not true → false,
                // not false → true.
                ExpressionNode operand = u.expr();
                if (canYieldNil(operand)) {
                    String code = emitBooleanNullableOperand(operand);
                    yield "(" + code + " == null || !" + code
                        + ".booleanValue())";
                }
                yield "(!" + emitExpression(operand) + ")";
            }
            case NEG -> {
                Type t = typeOf(u.expr());
                if (t instanceof Type.Int) {

                    if (int32Mode && u.expr() instanceof LiteralExpr lit
                            && lit.value() instanceof LiteralValue.IntLiteral i
                            && i.value() == 2147483648L) {
                        yield "-2147483648";
                    }

                    yield "intNeg("
                        + adaptIntBoundary(u.expr(), emitExpression(u.expr()),
                            Type.Int.INSTANCE)
                        + ", " + originArgs(u.span()) + ")";
                }
                if (t instanceof Type.Number) yield "(-" + emitExpression(u.expr()) + ")";
                unsupported("unary - on " + typeName(t), u.span());
                yield "0L";
            }
        };
    }

    private String emitCall(CallExpr call) {
        if (call.callee() instanceof MemberAccessExpr mae) {

            Type calleeType = typeOf(mae);
            if (calleeType instanceof Type.Func
                    && typeOf(mae.object()) instanceof Type.Class) {
                return emitClassFieldCall(mae, call, (Type.Func) calleeType);
            }
            return emitMemberAccessCall(mae, call);
        }
        if (call.callee() instanceof IdentifierExpr id) {
            String mapped = localJavaName(id.name());
            if (mapped != null) {

                Type calleeType = typeOf(call.callee());
                if (calleeType instanceof Type.Func f) {
                    // Module-level (load-time) indirect calls through a
                    // function-typed field get the same use-before-
                    // declaration protection as module-level direct calls:
                    // LuaJIT evaluates the field's initializer at load and
                    // would fail on a value whose function reads a
                    // later-declared field or reaches a later-declared
                    // function, while Java would silently run the
                    // initialized static field / hoisted method.
                    if (currentModuleStatementIndex >= 0
                            && moduleFieldIndices.containsKey(id.name())) {
                        String risk = moduleIndirectCallRisk(id.name());
                        if (risk != null) {
                            unsupported(risk, call.span());
                            return "null";
                        }
                    }
                    List<String> argCodes = emitOperandsInOrder(call.args(),
                        f.paramTypes());
                    for (int i = 0; i < argCodes.size(); i++) {
                        argCodes.set(i, boundaryArgCode(
                            call.args().get(i), argCodes.get(i),
                            f.paramTypes().get(i)));
                    }
                    return mapped + ".invokeAt("
                        + invokeAtArgs(originArgs(call.span()),
                            String.join(", ", argCodes))
                        + ")";
                }
                unsupported("calls through non-function values", call.span());
                return "null";
            }
            Symbol sym = symbols.resolve(id.name());
            if (sym instanceof Symbol.IntrinsicSymbol) {
                return emitIntrinsicCall(id.name(), call);
            }
            if (!(sym instanceof Symbol.FunctionSymbol)) {
                unsupported("calls through non-function values", call.span());
                return "null";
            }
            if (currentModuleStatementIndex >= 0
                    && !moduleFunctions.containsKey(id.name())) {
                // The only FunctionSymbols outside moduleFunctions are the
                // compiler-generated @jsonable helpers (C$fromJson /
                // C$toJson): LuaJIT assigns them at the END of the module
                // chunk (its deferred @jsonable pass), so a load-time
                // call reads the not-yet-assigned local and fails with a
                // nil read, while Java hoists methods and would silently
                // run.
                unsupported("module-level call of the compiler-generated "
                    + "helper '" + id.name() + "' (LuaJIT assigns the "
                    + "@jsonable helpers at the end of the module chunk, "
                    + "so a load-time call reads nil and fails; Java "
                    + "would silently run the hoisted method)", call.span());
                return "null";
            }
            // Module-level call to a module function whose body
            // (transitively) reads a module field declared later than the
            // call site: LuaJIT fails at load for such reads (the field is
            // nil until its declaration runs) while Java would silently
            // read the field's default value — reject instead of
            // miscompiling.
            if (currentModuleStatementIndex >= 0
                    && moduleFunctions.containsKey(id.name())) {
                String later = laterFieldRead(id.name(),
                    currentModuleStatementIndex);
                if (later != null) {
                    unsupported("module-level call of '" + id.name()
                        + "' whose body (transitively) reads the module "
                        + "field '" + later + "' declared at or after the "
                        + "call site (LuaJIT fails at load with a nil read; "
                        + "Java would silently read the default value)",
                        call.span());
                    return "null";
                }
                String laterFn = laterFunctionCall(id.name(),
                    currentModuleStatementIndex);
                if (laterFn != null) {
                    unsupported("module-level call of '" + id.name()
                        + "' reaching function '" + laterFn
                        + "' declared at or after the call site (LuaJIT "
                        + "assigns function values at their declaration "
                        + "point and fails at load with a nil read; Java "
                        + "hoists methods and would silently run)",
                        call.span());
                    return "null";
                }
                String laterImport = laterImportRead(id.name(),
                    currentModuleStatementIndex);
                if (laterImport != null) {
                    unsupported("module-level call of '" + id.name()
                        + "' whose body (transitively) uses the import '"
                        + laterImport + "' declared at or after the call "
                        + "site (LuaJIT has not run the require yet and "
                        + "fails at load; Java would silently initialize "
                        + "the imported class)", call.span());
                    return "null";
                }

                String indirect = transitiveIndirectCalls
                    .getOrDefault(id.name(), Set.of()).stream().findFirst()
                    .orElse(null);
                if (indirect != null) {
                    unsupported("module-level call of '" + id.name()
                        + "' whose body (transitively) invokes the "
                        + "function value '" + indirect + "' (an indirect "
                        + "call executed at load time — the held value is "
                        + "not statically known to the load-time guard; "
                        + "LuaJIT fails at load when the invoked function "
                        + "reaches a not-yet-declared value, Java would "
                        + "silently run the hoisted method)", call.span());
                    return "null";
                }

                String laterFnValue = laterFunctionValueRead(id.name(),
                    currentModuleStatementIndex);
                if (laterFnValue != null) {
                    unsupported("module-level call of '" + id.name()
                        + "' whose body (transitively) reads the function "
                        + "value of '" + laterFnValue + "' declared at or "
                        + "after the call site (LuaJIT assigns function "
                        + "values at their declaration point and fails at "
                        + "load with a nil read; Java would silently read "
                        + "the uninitialized static field's default)",
                        call.span());
                    return "null";
                }
            }
            List<Type> argTargets = new ArrayList<>(call.args().size());
            List<BoundaryOrigin> argOrigins =
                new ArrayList<>(call.args().size());
            FunctionDeclaration calleeDecl = moduleFunctions.get(id.name());
            for (int i = 0; i < call.args().size(); i++) {
                argTargets.add(paramDeclaredType(id.name(), i));
                // D1/D3: the callee's declared parameter-type annotation
                // is the authoritative origin of a parameter-boundary
                // raise.
                argOrigins.add(paramOrigin(calleeDecl, i,
                    artifactFileOf(modulePath)));
            }
            List<String> argCodes = emitOperandsInOrder(call.args(),
                argTargets, argOrigins);
            StringBuilder sb = new StringBuilder(javaName(id.name())).append('(');
            for (int i = 0; i < argCodes.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(boundaryArgCode(call.args().get(i),
                    argCodes.get(i), paramDeclaredType(id.name(), i)));
            }
            // A module-local @jsonable C$toJson helper (the
            // compiler-generated name; a FunctionSymbol outside
            // moduleFunctions, and no user identifier contains '$')
            // additionally receives the call-expression origin so the
            // callee's shape/encode walk reports this call site (D3).
            if (!moduleFunctions.containsKey(id.name())
                    && id.name().endsWith("$toJson")) {
                sb.append(", ").append(originArgs(call.span()));
            }
            return sb.append(')').toString();
        }
        // Any other function-typed callee expression (a call or
        // assignment producing a function value): the callee evaluates
        // BEFORE every argument (spec \u00a7Operational semantics rule 1 —
        // LuaJIT evaluates the callee expression completely first), so it
        // is materialized into a fresh effectively-final temporary
        // declared at its evaluation position — after its own hoisted
        // statements and BEFORE any argument's hoisted pre-statements (a
        // hoisted side-effecting argument, an E8010-checked argument's
        // value temporary) — and the dispatch goes through the temporary:
        // `picker()(41, 999)` becomes `__fn0 = picker(); __fn0.invoke(41L,
        // 999L)`, never `picker().invoke(...)` with the argument
        // pre-statements running first. No capture is introduced (the
        // temporary is single-assignment and effectively final).
        Type calleeType = typeOf(call.callee());
        if (calleeType instanceof Type.Func f) {

            if (currentModuleStatementIndex >= 0) {
                unsupported("module-level indirect calls through "
                    + "non-identifier callees (a call-result or "
                    + "assignment-produced function value — the value "
                    + "is not statically known to the load-time guard; "
                    + "LuaJIT fails at load when the produced function "
                    + "reads or reaches a not-yet-declared value, Java "
                    + "would read the uninitialized static wrapper "
                    + "field or run the hoisted method)",
                    call.callee().span());
                return "null";
            }
            String shape = registerWrapperShape(f);
            if (shape == null) {
                unsupported("function values whose signature contains "
                    + "unsupported carriers (table | null positions and "
                    + "host-class carriers — deferred to the ISSUE-0110 "
                    + "descriptor join)", call.callee().span());
                return "null";
            }
            String callee = emitExpression(call.callee());
            if (callee == null) return "null"; // diagnostic already recorded
            String calleeTemp = nextFunctionValueTempName();
            preStatements.add(new PreLine(shape + " " + calleeTemp + " = "
                + callee + ";", 0));
            preStatementsDeclareTemps = true;
            List<String> argCodes = emitOperandsInOrder(call.args(),
                f.paramTypes());
            for (int i = 0; i < argCodes.size(); i++) {
                argCodes.set(i, boundaryArgCode(
                    call.args().get(i), argCodes.get(i),
                    f.paramTypes().get(i)));
            }
            return calleeTemp + ".invokeAt("
                + invokeAtArgs(originArgs(call.span()),
                    String.join(", ", argCodes))
                + ")";
        }
        unsupported("calls through non-identifier callees", call.span());
        return "null";
    }

    private String emitClassFieldCall(MemberAccessExpr mae, CallExpr call,
            Type.Func f) {
        String obj = emitExpression(mae.object());
        String recv;
        if (isPureAfterEmission(mae.object())) {
            recv = "(" + obj + ")";
        } else {
            String clsRef = javaLocalType(typeOf(mae.object()), mae.span());
            if (clsRef == null) return "null";
            String tmp = nextEvalTempName();
            preStatements.add(new PreLine(clsRef + " " + tmp + " = "
                + obj + ";", 0));
            preStatementsDeclareTemps = true;
            recv = tmp;
        }
        String shape = registerWrapperShape(f);
        if (shape == null) {
            unsupported("function values whose signature contains "
                + "unsupported carriers (table | null positions and "
                + "host-class carriers — deferred to the ISSUE-0110 "
                + "descriptor join)", call.callee().span());
            return "null";
        }
        String calleeTemp = nextFunctionValueTempName();
        preStatements.add(new PreLine(shape + " " + calleeTemp + " = "
            + recv + "." + javaName(mae.field()) + ";", 0));
        preStatementsDeclareTemps = true;
        List<String> argCodes = emitOperandsInOrder(call.args(),
            f.paramTypes());
        for (int i = 0; i < argCodes.size(); i++) {
            argCodes.set(i, boundaryArgCode(call.args().get(i),
                argCodes.get(i), f.paramTypes().get(i)));
        }
        return calleeTemp + ".invokeAt("
            + invokeAtArgs(originArgs(call.span()),
                String.join(", ", argCodes))
            + ")";
    }

    private String emitMemberAccessCall(MemberAccessExpr mae, CallExpr call) {
        if (!(mae.object() instanceof IdentifierExpr id)) {
            unsupported("member access on non-identifier objects", mae.span());
            return "null";
        }
        String module = importAliases.get(id.name());
        if (module == null) {

            Type memberObjType = typeOf(mae.object());
            if (memberObjType instanceof Type.Nullable nn
                    && nn.inner() instanceof Type.Class) {
                memberObjType = nn.inner();
            }
            if (memberObjType instanceof Type.Class cls
                    && !isBuiltinErrorType(cls)
                    && typeOf(mae) instanceof Type.Func f
                    && Types.containsBytes(f)
                    && registerWrapperShape(f) != null) {
                String shape = registerWrapperShape(f);
                String calleeTemp = nextFunctionValueTempName();
                preStatements.add(new PreLine(shape + " " + calleeTemp
                    + " = (" + emitExpression(mae.object()) + ")."
                    + javaName(mae.field()) + ";", 0));
                preStatementsDeclareTemps = true;
                List<Type> argTargets = f.paramTypes();
                List<String> argCodes = emitOperandsInOrder(call.args(),
                    argTargets);
                StringBuilder sb = new StringBuilder(calleeTemp)
                    .append(".invokeAt(")
                    .append(originArgs(call.span()));
                for (int i = 0; i < argCodes.size(); i++) {
                    sb.append(", ");
                    sb.append(boundaryArgCode(call.args().get(i),
                        argCodes.get(i), f.paramTypes().get(i)));
                }
                return sb.append(')').toString();
            }
            unsupported("member access (only module function calls on "
                + "imported project modules, the supported stdlib "
                + "modules — std/console output, std/string, std/math, "
                + "std/time — and host modules are supported)", mae.span());
            return "null";
        }

        String hostRaw = hostAliases.get(id.name());
        if (hostRaw != null) {
            if (currentModuleStatementIndex >= 0) {
                Integer importIdx = importAliasStatementIndices.get(id.name());
                if (importIdx != null && importIdx > currentModuleStatementIndex) {
                    unsupported("module-level use of import '" + id.name()
                        + "' before its import statement (LuaJIT loads the "
                        + "host module at the import's source position and "
                        + "fails at load for an earlier use; Java would "
                        + "silently skip the load-time presence check)",
                        mae.span());
                    return "null";
                }
            }
            Map<String, Type> exports = hostModules.get(hostRaw);
            Type exportType = exports.get(mae.field());
            if (!(exportType instanceof Type.Func f)) {
                unsupported("host export '" + mae.field() + "' of module '"
                    + hostRaw + "'", mae.span());
                return "null";
            }

            List<Type> argTargets = new ArrayList<>(call.args().size());
            for (int i = 0; i < call.args().size(); i++) {
                argTargets.add(f.paramTypes().get(i));
            }
            Set<ExpressionNode> deferredReads = null;
            for (ExpressionNode arg : call.args()) {
                if (arg instanceof MemberAccessExpr) {
                    if (deferredReads == null) {
                        deferredReads = java.util.Collections.newSetFromMap(
                            new java.util.IdentityHashMap<>());
                    }
                    deferredReads.add(arg);
                }
            }
            Set<ExpressionNode> prevDeferredReads = deferredHostArgReads;
            deferredHostArgReads = deferredReads;
            List<String> argCodes;
            try {
                argCodes = emitOperandsInOrder(call.args(), argTargets);
            } finally {
                deferredHostArgReads = prevDeferredReads;
            }
            StringBuilder sb = new StringBuilder(
                hostWrapperName(id.name(), mae.field())).append('(');
            for (int i = 0; i < argCodes.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(coerceNullValueCode(argCodes.get(i),
                    call.args().get(i), "java.lang.Object",
                    call.args().get(i).span()));
            }
            // The declared host boundary's origins: the call expression
            // start (parameter/sync-return raises) and — when this call
            // is the direct callee of an await — the await keyword start
            // (the completion-value check).
            if (sb.length() > 0
                    && sb.charAt(sb.length() - 1) != '(') {
                sb.append(", ");
            }
            sb.append(originArgs(call.span()));
            sb.append(", ");
            sb.append(pendingAwaitCallee == call
                    && pendingAwaitCalleeSpan != null
                ? originArgs(pendingAwaitCalleeSpan)
                : originArgs(call.span()));
            return sb.append(')').toString();
        }
        if ("std/console".equals(module)) {
            String target = switch (mae.field()) {
                case "log" -> "java.lang.System.out";
                case "error" -> "java.lang.System.err";
                default -> {
                    unsupported("export '" + mae.field() + "' of std/console", mae.span());
                    yield null;
                }
            };
            if (target == null) return "null";
            // D1/D3 (stdlib declared-parameter boundary): the call
            // expression's own start is the authoritative origin of a
            // checked stdlib argument.
            List<String> argCodes = withBoundaryOrigin(
                originOfExpression(call, artifactFileOf(modulePath)),
                () -> emitOperandsInOrder(call.args()));
            StringBuilder sb = new StringBuilder(target).append(".println(");
            for (int i = 0; i < argCodes.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(argCodes.get(i));
            }
            return sb.append(')').toString();
        }
        if ("std/string".equals(module)) {
            return emitStdlibStringMemberCall(mae, call);
        }
        if ("std/math".equals(module)) {
            return emitStdlibMathMemberCall(mae, call);
        }
        if ("std/time".equals(module)) {
            return emitStdlibTimeMemberCall(mae, call);
        }
        if ("std/table".equals(module)) {

            if ("keys".equals(mae.field())) {
                List<String> argCodes = withBoundaryOrigin(
                    originOfExpression(call, artifactFileOf(modulePath)),
                    () -> emitOperandsInOrder(call.args()));
                return "__tableKeys(" + argCodes.get(0) + ")";
            }
            unsupported("export '" + mae.field() + "' of std/table",
                mae.span());
            return "null";
        }
        if ("std/json".equals(module)) {

            if ("parse".equals(mae.field())) {
                List<String> argCodes = emitOperandsInOrder(call.args());
                // The call-expression origin (D3): a parse rejection
                // carries the json.parse call-site span.
                return "$jsonParse(" + argCodes.get(0) + ", "
                    + originArgs(call.span()) + ")";
            }
            if ("stringify".equals(mae.field())) {
                List<String> argCodes = emitOperandsInOrder(call.args());
                // The call-expression origin (D3) threads unchanged
                // through the recursive encode walk, so an
                // unsupported-type rejection at any nesting depth
                // reports the json.stringify call-site span.
                return "__jsonStringify(" + argCodes.get(0) + ", "
                    + originArgs(call.span()) + ")";
            }
            unsupported("export '" + mae.field() + "' of std/json",
                mae.span());
            return "null";
        }

        if (currentModuleStatementIndex >= 0) {
            Integer importIdx = importAliasStatementIndices.get(id.name());
            if (importIdx != null && importIdx > currentModuleStatementIndex) {
                unsupported("module-level use of import '" + id.name()
                    + "' before its import statement (LuaJIT reads the "
                    + "not-yet-required global at load and fails; Java "
                    + "would silently initialize the imported class)",
                    mae.span());
                return "null";
            }
        }
        String className = classNameFor(module);

        Type.Func exported = typeOf(mae) instanceof Type.Func f ? f : null;
        List<Type> argTargets = exported == null
            ? null : exported.paramTypes();
        // D1/D3 + D6 (imported declared-parameter boundary): every
        // argument's origin is the DECLARING module's parameter-type
        // annotation span, carried across the import seam; a module the
        // context does not carry leaves the argument without an origin,
        // never a fabricated location.
        ImportedModuleSurface declaringSurface = importedSurfaces.get(module);
        FunctionDeclaration importedDecl = declaringSurface == null
            ? null : declaringSurface.functions().get(mae.field());
        List<BoundaryOrigin> argOrigins =
            new ArrayList<>(call.args().size());
        for (int i = 0; i < call.args().size(); i++) {
            argOrigins.add(paramOrigin(importedDecl, i,
                artifactFileOf(module)));
        }
        List<String> argCodes = emitOperandsInOrder(call.args(), argTargets,
            argOrigins);
        StringBuilder sb = new StringBuilder(className).append('.')
            .append(javaName(mae.field())).append('(');
        for (int i = 0; i < argCodes.size(); i++) {
            if (i > 0) sb.append(", ");
            Type paramType = argTargets != null
                && i < argTargets.size() ? argTargets.get(i) : null;
            sb.append(boundaryArgCode(call.args().get(i),
                argCodes.get(i), paramType));
        }
        // A cross-module @jsonable C$toJson helper (the
        // compiler-generated name; no user identifier contains '$')
        // additionally receives the call-expression origin so the
        // callee's shape/encode walk reports this call site (D3).
        if (mae.field().endsWith("$toJson")) {
            sb.append(", ").append(originArgs(call.span()));
        }
        return sb.append(')').toString();
    }

    private String emitStdlibStringMemberCall(MemberAccessExpr mae, CallExpr call) {
        // D1/D3 (stdlib declared-parameter boundary): the call
        // expression's own start is the authoritative origin of a
        // checked stdlib argument.
        List<String> argCodes = withBoundaryOrigin(
            originOfExpression(call, artifactFileOf(modulePath)),
            () -> emitOperandsInOrder(call.args()));
        String a0 = argCodes.get(0);
        StringBuilder sb = new StringBuilder();
        switch (mae.field()) {
            case "length" -> sb.append("__strLength(").append(a0).append(')');
            case "substring" -> sb.append("__strSubstring(").append(a0).append(", ")
                .append(adaptIntBoundary(call.args().get(1), argCodes.get(1),
                    Type.Int.INSTANCE)).append(", ")
                .append(adaptIntBoundary(call.args().get(2), argCodes.get(2),
                    Type.Int.INSTANCE)).append(')');
            case "contains" -> sb.append('(').append(a0).append(").contains(")
                .append(argCodes.get(1)).append(')');
            case "startsWith" -> sb.append('(').append(a0).append(").startsWith(")
                .append(argCodes.get(1)).append(')');
            case "endsWith" -> sb.append('(').append(a0).append(").endsWith(")
                .append(argCodes.get(1)).append(')');
            case "replace" -> sb.append("__strReplace(").append(a0).append(", ")
                .append(argCodes.get(1)).append(", ").append(argCodes.get(2))
                .append(')');
            case "split" -> sb.append("__strSplit(").append(a0).append(", ")
                .append(argCodes.get(1)).append(')');
            case "trim" -> sb.append("__strTrim(").append(a0).append(')');
            default -> {
                unsupported("export '" + mae.field() + "' of std/string",
                    mae.span());
                return "null";
            }
        }
        return sb.toString();
    }

    private String emitStdlibMathMemberCall(MemberAccessExpr mae, CallExpr call) {
        // D1/D3 (stdlib declared-parameter boundary): the call
        // expression's own start is the authoritative origin of a
        // checked stdlib argument.
        List<String> argCodes = withBoundaryOrigin(
            originOfExpression(call, artifactFileOf(modulePath)),
            () -> emitOperandsInOrder(call.args()));
        String a0 = argCodes.isEmpty() ? null : argCodes.get(0);
        return switch (mae.field()) {
            case "floor" -> "java.lang.Math.floor(" + a0 + ")";
            case "ceil" -> "java.lang.Math.ceil(" + a0 + ")";
            case "sqrt" -> "__mathSqrt(" + a0 + ")";
            case "absInt" -> int32Mode

                ? "checkInt(java.lang.Math.abs((long) " + a0 + "), "
                    + originArgs(call.span()) + ")"
                : "checkInt(java.lang.Math.abs(" + a0 + "), "
                    + originArgs(call.span()) + ")";
            case "absNumber" -> "java.lang.Math.abs(" + a0 + ")";
            case "minInt" -> "java.lang.Math.min("
                + adaptIntBoundary(call.args().get(0), a0, Type.Int.INSTANCE)
                + ", " + adaptIntBoundary(call.args().get(1), argCodes.get(1),
                    Type.Int.INSTANCE) + ")";
            case "maxInt" -> "java.lang.Math.max("
                + adaptIntBoundary(call.args().get(0), a0, Type.Int.INSTANCE)
                + ", " + adaptIntBoundary(call.args().get(1), argCodes.get(1),
                    Type.Int.INSTANCE) + ")";
            default -> {
                unsupported("export '" + mae.field() + "' of std/math",
                    mae.span());
                yield "null";
            }
        };
    }

    private String emitStdlibTimeMemberCall(MemberAccessExpr mae, CallExpr call) {
        if (!"nowMillis".equals(mae.field())) {
            unsupported("export '" + mae.field() + "' of std/time", mae.span());
            return "null";
        }
        return "(java.lang.System.currentTimeMillis() / 1000L) * 1000L";
    }

    private String emitTableWrite(MemberAccessExpr mae, ExpressionNode value) {
        Type valueType = typeOf(value);
        List<String> codes = emitOperandsInOrder(
            List.of(mae.object(), value));

        String valueCode = adaptIntBoundary(value, codes.get(1), valueType);
        String boxed = switch (valueType) {
            case Type.Int ignored ->
                (int32Mode ? "java.lang.Integer" : "java.lang.Long")
                    + ".valueOf(" + valueCode + ")";
            case Type.Number ignored ->
                "java.lang.Double.valueOf(" + codes.get(1) + ")";
            case Type.Boolean ignored ->
                "java.lang.Boolean.valueOf(" + codes.get(1) + ")";
            case Type.Null ignored -> "(java.lang.Object) null";
            case Type.Bytes ignored -> codes.get(1);
            default -> codes.get(1);
        };
        String unbox = switch (valueType) {
            case Type.Int ignored -> int32Mode ? ".intValue()" : ".longValue()";
            case Type.Number ignored -> ".doubleValue()";
            case Type.Boolean ignored -> ".booleanValue()";
            case Type.Bytes ignored -> "";
            default -> "";
        };
        return "$tPut(" + codes.get(0) + ", "
            + quoteJavaString(mae.field()) + ", " + boxed + ")" + unbox;
    }

    private String emitHas(HasExpr he) {
        Type objType = typeOf(he.object());
        if (objType instanceof Type.Class cls
                && !isBuiltinErrorType(cls)) {
            ClassField cf = isHostModuleClass(cls)
                ? hostClassField(cls, he.field())
                : backendClassField(cls, he.field());
            if (cf == null || !cf.optional()) {
                unsupported("has() on a non-optional field", he.span());
                return "false";
            }
            String obj = emitExpression(he.object());
            String clsRef = javaLocalType(cls, he.span());
            if (clsRef == null) return "false";
            String recv;
            if (isPureAfterEmission(he.object())) {
                recv = "(" + obj + ")";
            } else {
                String tmp = nextEvalTempName();
                preStatements.add(new PreLine(clsRef + " " + tmp
                    + " = " + obj + ";", 0));
                recv = tmp;
            }

            if (isHostModuleClass(cls)) {
                return "(" + recv + ")." + javaName(he.field())
                    + "$present";
            }
            ClassDeclaration cd = classDeclFor(cls);
            if (cd != null && cd.isJsonable()) {
                String sentinel;
                if (isLocalClassType(cls)) {
                    sentinel = "$MISSING";
                } else {
                    String importedModule = importedClassModuleRef(cls,
                        he.span());
                    if (importedModule == null) return "false";
                    sentinel = importedModule + ".$MISSING";
                }
                return "(" + recv + "." + javaName(he.field())
                    + " != " + sentinel + ")";
            }
            return "((" + clsRef + ") " + recv + ")."
                + javaName(he.field()) + "$present";
        }
        unsupported("has() on a non-class optional field", he.span());
        return "false";
    }

    private String emitMemberAccessValue(MemberAccessExpr mae) {
        Type objType = typeOf(mae.object());
        if (mae.object() instanceof IdentifierExpr id) {

            String hostRaw = hostAliases.get(id.name());
            if (hostRaw != null) {
                if (currentModuleStatementIndex >= 0) {
                    Integer importIdx =
                        importAliasStatementIndices.get(id.name());
                    if (importIdx != null
                            && importIdx > currentModuleStatementIndex) {
                        unsupported("module-level use of import '"
                            + id.name() + "' as a value before its import "
                            + "statement (LuaJIT loads the host module at "
                            + "the import's source position and fails at "
                            + "load for an earlier use; Java would "
                            + "silently skip the load-time presence "
                            + "check)", mae.span());
                        return "null";
                    }
                }
                Map<String, Type> exports = hostModules.get(hostRaw);
                Type exportType =
                    exports == null ? null : exports.get(mae.field());
                if (exportType instanceof Type.Func f
                        && registerWrapperShape(f) != null) {
                    return hostFnValueFieldName(id.name(), mae.field());
                }
                unsupported("host export '" + mae.field()
                    + "' of module '" + hostRaw
                    + "' used as a value (only declared function "
                    + "exports with representable signatures are "
                    + "supported)", mae.span());
                return "null";
            }
            String module = importAliases.get(id.name());
            if (module != null
                    && !SUPPORTED_STDLIB_MODULES.contains(module)
                    && !hostAliases.containsKey(id.name())) {

                if (typeOf(mae) instanceof Type.Func f
                        && registerWrapperShape(f) != null) {
                    return classNameFor(module) + "."
                        + javaName(mae.field()) + "$fn";
                }
                unsupported("imported module members used as values "
                    + "(only imported functions are supported)",
                    mae.span());
                return "null";
            }
        }
        if (objType instanceof Type.Nullable nn
                && nn.inner() instanceof Type.Class) {

            objType = nn.inner();
        }
        if (objType instanceof Type.Bytes
                && "length".equals(mae.field())) {

            String obj = emitExpression(mae.object());
            return "bytesLength(" + obj + ")";
        }
        if (objType instanceof Type.Array && "length".equals(mae.field())) {
            String obj = emitExpression(mae.object());

            return int32Mode ? obj + ".data.length"
                : "((long) " + obj + ".data.length)";
        }
        if (objType instanceof Type.Class cls && isBuiltinErrorType(cls)) {

            String obj = emitExpression(mae.object());
            if ("code".equals(mae.field())) {
                return "__errorCode(" + obj + ")";
            }
            if ("message".equals(mae.field())) {
                return "(" + obj + ").getMessage()";
            }
            unsupported("Error member other than code/message", mae.span());
            return "null";
        }
        if (objType instanceof Type.Class cls
                && isHostModuleClass(cls)) {

            ClassField cf = hostClassField(cls, mae.field());
            if (cf == null) {
                unsupported("host class member other than a declared "
                    + "field", mae.span());
                return "null";
            }
            String obj = emitExpression(mae.object());
            String recv;
            if (isPureAfterEmission(mae.object())) {
                recv = "(" + obj + ")";
            } else {
                String tmp = nextEvalTempName();
                preStatements.add(new PreLine(
                    javaLocalType(cls, mae.span()) + " " + tmp
                        + " = " + obj + ";", 0));
                recv = tmp;
            }
            return "(" + recv + ")." + javaName(mae.field());
        }
        if (objType instanceof Type.Class cls) {
            ClassField cf = backendClassField(cls, mae.field());
            if (cf != null && cf.optional()) {
                // An optional class field read yields T | null: absent
                // reads as the DEAL null, present values read boxed
                // (the checker already typed the read as Nullable(T)).
                // The receiver materializes into a temporary when it is
                // not pure after emission, so an effectful receiver
                // evaluates exactly once (the same single-evaluation
                // convention the constructor and operand
                // materializations follow).
                String obj = emitExpression(mae.object());
                String readJava = javaLocalType(typeOf(mae), mae.span());
                if (readJava == null) return "null";
                String clsRef = javaLocalType(cls, mae.span());
                if (clsRef == null) return "null";
                String recv;
                if (isPureAfterEmission(mae.object())) {
                    recv = "(" + obj + ")";
                } else {
                    String tmp = nextEvalTempName();
                    preStatements.add(new PreLine(clsRef + " " + tmp
                        + " = " + obj + ";", 0));
                    recv = tmp;
                }
                ClassDeclaration cd = classDeclFor(cls);
                if (cd != null && cd.isJsonable()) {
                    // Missing-sentinel storage: absent is the sentinel,
                    // the DEAL null is present — absent reads as the
                    // DEAL null, present values cast from the Object
                    // slot.
                    String sentinel;
                    if (isLocalClassType(cls)) {
                        sentinel = "$MISSING";
                    } else {
                        String importedModule = importedClassModuleRef(cls,
                            mae.span());
                        if (importedModule == null) return "null";
                        sentinel = importedModule + ".$MISSING";
                    }
                    return "(" + recv + "." + javaName(mae.field())
                        + " == " + sentinel + " ? null : (" + readJava
                        + ") " + recv + "." + javaName(mae.field()) + ")";
                }

                return "(" + recv + ")." + javaName(mae.field());
            }
            String obj = emitExpression(mae.object());
            return "(" + obj + ")." + javaName(mae.field());
        }
        if (objType instanceof Type.Table) {
            return emitTableRead(mae);
        }
        unsupported("member access as a value", mae.span());
        return "null";
    }

    private String emitIndexRead(IndexExpr idx, Type target) {
        Type arrayType = typeOf(idx.array());
        if (arrayType instanceof Type.Bytes) {

            List<String> codes = emitOperandsInOrder(
                List.of(idx.array(), idx.index()));
            String indexCode = adaptIntBoundary(idx.index(),
                codes.get(1), Type.Int.INSTANCE);
            return "bytesGet(" + codes.get(0) + ", " + indexCode
                + ", " + originArgs(idx.span()) + ")";
        }
        if (!(arrayType instanceof Type.Array arr)) {
            unsupported("indexing of " + typeName(arrayType), idx.span());
            return "null";
        }
        Type element = arr.element();
        if (isNilYieldingReadTarget(idx, target)) {
            // The read site's target type is Nullable(T) for this T[]
            // element — the target's check_nullable accepts the past-end
            // nil, so the read yields the DEAL null instead of raising
            // the element-typed E8001.
            String nilHelper = null;
            if (element instanceof Type.Class cls) {

                nilHelper = classArrayHelper(cls, "readOrNull",
                    idx.span());
            } else {
                nilHelper = boxedArrayReadHelper(element);
            }
            if (nilHelper != null) {
                List<String> codes = emitOperandsInOrder(
                    List.of(idx.array(), idx.index()));
                String indexCode = adaptIntBoundary(idx.index(),
                    codes.get(1), Type.Int.INSTANCE);
                return nilHelper + "(" + codes.get(0) + ", "
                    + indexCode + ", " + originArgs(idx.span()) + ")";
            }
            // Local-only class guard failed: fall through to the
            // element-typed chain below, which records the E6000.
        }
        String helper = null;
        if (element instanceof Type.Nullable ne) {
            // (T | null)[] reads: the boxed read yields the DEAL null
            // past the end (a valid nullable element — LuaJIT's nil, no
            // boundary failure at the read); a negative index still
            // raises E8002. Nullable class elements use the per-class
            // helper with the nominal check; nullable function elements
            // use the per-signature helper.
            helper = orNullArrayReadHelper(ne.inner());
            if (helper == null && ne.inner() instanceof Type.Class cls) {
                helper = classArrayHelper(cls, "readOrNull", idx.span());
            }
            if (helper == null && ne.inner() instanceof Type.Func) {
                helper = refArrayReadHelper(element, idx.span());
            }
        } else if (element instanceof Type.Class cls) {
            helper = classArrayHelper(cls, "read", idx.span());
        } else if (element instanceof Type.Array
                || element instanceof Type.Func
                || (element instanceof Type.Nullable ne
                    && ne.inner() instanceof Type.Func)) {
            helper = refArrayReadHelper(element, idx.span());
        } else {
            helper = arrayReadHelper(element);
        }
        if (helper == null) {
            // Unsupported element type (nested/function/… arrays):
            // record the E6000 and emit the inert placeholder.
            javaArrayElementType(element, idx.span());
            return "null";
        }
        List<String> codes = emitOperandsInOrder(List.of(idx.array(), idx.index()));
        String indexCode = adaptIntBoundary(idx.index(), codes.get(1),
            Type.Int.INSTANCE);
        return helper + "(" + codes.get(0) + ", " + indexCode
            + ", " + readHelperOrigins(element, idx.span()) + ")";
    }

    private String readHelperOrigins(Type element, Span indexSpan) {
        String origins = originArgs(indexSpan);
        if (arrayReadHelper(element) == null) return origins;
        return origins + ", " + boundaryCheckOriginArgs(indexSpan);
    }

    private String boundaryCheckOriginArgs(Span fallback) {
        BoundaryOrigin origin = currentBoundaryOrigin();
        if (origin == null) return originArgs(fallback);
        return quoteJavaString(origin.file()) + ", " + origin.line() + ", "
            + origin.column();
    }

    private boolean isPrimitiveArrayRead(ExpressionNode e) {
        if (!(e instanceof IndexExpr idx)) return false;
        if (!(typeOf(idx.array()) instanceof Type.Array arr)) return false;
        return arrayReadHelper(arr.element()) != null;
    }

    /** {@code true} when {@code e} is an index read of one of the four
     * supported primitive array types whose element type is boolean. */
    private boolean isPrimitiveBooleanArrayRead(ExpressionNode e) {
        if (!isPrimitiveArrayRead(e)) return false;
        return ((Type.Array) typeOf(((IndexExpr) e).array())).element()
            instanceof Type.Boolean;
    }

    /** {@code true} when {@code e} is an index read of a LOCAL class
     * array ({@code C[]}) — the nil-capable read shape of the class
     * slice (the same past-end nil semantics as the primitive reads;
     * imported class arrays stay E6000 at the read). Side-effect-free:
     * the locality guard must not record the E6000 a second time (the
     * read emission records it once). */
    private boolean isClassArrayRead(ExpressionNode e) {
        if (!(e instanceof IndexExpr idx)) return false;
        if (!(typeOf(idx.array()) instanceof Type.Array arr)) return false;
        if (!(arr.element() instanceof Type.Class cls)) return false;
        return isLocalClassType(cls) && moduleClasses.containsKey(cls.name());
    }

    /** {@code true} when {@code e} is an index read whose array element
     * is a non-nullable primitive or local class — the read shapes that
     * yield the LuaJIT nil past the end with no boundary at the read
     * site ({@code (T | null)[]} reads yield the nil through their own
     * nullable element type and route through the nullable comparison
     * lowering instead). */
    private boolean isNilCapableArrayRead(ExpressionNode e) {
        return isPrimitiveArrayRead(e) || isClassArrayRead(e);
    }

    /**
     * True when the LuaJIT value of {@code e} can be the Lua nil — a
     * boolean-typed expression whose evaluation can read a
     * {@code boolean[]} element past the end (LuaJIT's read yields nil
     * there; spec §Bounds and nil behavior) with no typed boundary in
     * between. The only nil sources are {@code boolean[]} reads (the
     * other three element types can only reach {@code ===}/{@code !==}
     * operands, which never propagate nil) and {@code &&}/{@code ||}
     * results built from them (Lua's {@code and}/{@code or} pass nil
     * through: {@code nil and x} → nil, {@code nil or x} → x,
     * {@code true and nil} → nil — conservatively reported when EITHER
     * operand can yield nil). Lua's {@code not nil} is {@code true}, so
     * {@code !} always produces a real boolean and stops propagation.
     */
    private boolean canYieldNil(ExpressionNode e) {
        return switch (e) {
            case IndexExpr idx -> isPrimitiveBooleanArrayRead(idx);
            case BinaryExpr bin -> {
                Type t = typeOf(bin);
                yield (t instanceof Type.Boolean)
                    && (bin.op() == BinaryOp.AND || bin.op() == BinaryOp.OR)
                    && (canYieldNil(bin.left()) || canYieldNil(bin.right()));
            }
            default -> false;
        };
    }

    /** True when a typed boolean boundary consuming {@code e} must convert
     * the emitted nullable boxed code with {@code booleanNotNull}: only
     * when {@code e} is NOT a direct read (a direct read's typed helper
     * already raises E8001 at the read — the boundary failure) and its
     * Lua value can be nil (a nil-aware {@code &&}/{@code ||} result). */
    private boolean needsBooleanBoundary(ExpressionNode e, Type t) {
        return t instanceof Type.Boolean
            && canYieldNil(e)
            && !isPrimitiveArrayRead(e);
    }

    private String emitBoxedReadTemp(IndexExpr idx) {
        Type element = ((Type.Array) typeOf(idx.array())).element();
        List<String> ops = emitOperandsInOrder(
            List.of(idx.array(), idx.index()));
        String n = nextEvalTempName();
        preStatements.add(new PreLine(boxedArrayJavaType(element, idx.span())
            + " " + n + " = " + boxedArrayReadHelper(element)
            + "(" + ops.get(0) + ", " + ops.get(1) + ", "
            + originArgs(idx.span()) + ");", 0));
        preStatementsDeclareTemps = true;
        return n;
    }

    /** True when a {@code ===}/{@code !==} operand carries LuaJIT nil
     * semantics — a primitive or local-class array read past the end or
     * a nil-aware {@code &&}/{@code ||} result (see
     * {@link #canYieldNil}). */
    private boolean isNilCapableOperand(ExpressionNode e) {
        return isNilCapableArrayRead(e) || canYieldNil(e);
    }

    /**
     * Emits a nil-capable comparison operand as nullable boxed code: a
     * direct primitive array read becomes a boxed read temporary, and
     * any other shape emits normally (a nil-aware {@code &&}/{@code ||}
     * already lowered itself to a boxed temporary). The returned code
     * can be {@code null} at runtime when {@link #isNilCapableOperand}
     * reported true.
     */
    private String emitNilCapableOperand(ExpressionNode e) {
        if (isNilCapableArrayRead(e)) {
            return emitBoxedReadTemp((IndexExpr) e);
        }
        return emitExpression(e);
    }

    /** The comparison element type: the read side's element when one
     * operand is a primitive array read, otherwise boolean (the only
     * nil-capable non-read operands are boolean-typed {@code &&}/
     * {@code ||} results). */
    private Type comparisonElement(BinaryExpr bin) {
        if (isNilCapableArrayRead(bin.left())) {
            return ((Type.Array) typeOf(
                ((IndexExpr) bin.left()).array())).element();
        }
        if (isNilCapableArrayRead(bin.right())) {
            return ((Type.Array) typeOf(
                ((IndexExpr) bin.right()).array())).element();
        }
        return Type.Boolean.INSTANCE;
    }

    /**
     * Emits {@code ===}/{@code !==} where at least one operand carries
     * LuaJIT nil semantics — a primitive or local-class array read or a
     * nil-aware {@code &&}/{@code ||} result. The spec's read-site
     * contract (spec
     * §Bounds and nil behavior) applies no typed boundary to a comparison
     * operand, so a read past the end must NOT raise E8001 here: LuaJIT
     * reads nil and computes the comparison on that value —
     * {@code nil === v} → {@code false}, {@code nil !== v} →
     * {@code true}, {@code nil === nil} → {@code true} (the reviewer's
     * four-type probes: {@code xs[99] === 5} → {@code neq}). Each
     * nil-capable operand is therefore emitted as a nullable boxed
     * temporary (the boxed read helper still raises E8002 for a negative
     * index — LuaJIT raises that unconditionally at the read) and the
     * comparison evaluates the boxed values with the nil semantics.
     * Operand evaluation stays strict left-to-right: the left operand is
     * emitted completely — including a left read's boxed helper call —
     * before the right operand is even emitted, so a left read's E8002
     * always raises before any right-operand hoisted side effect
     * (appending both helper pre-statements after a single four-operand
     * {@code emitOperandsInOrder} call placed the right operand's
     * hoisted println first: {@code ys[-1] === makeArr("made",
     * console.log("h"))[0]} printed "h" before the E8002 while LuaJIT
     * raises with no output). An effectful non-nil operand is
     * materialized into a pre-statement temporary so the final
     * comparison references only inert values and its Java {@code &&}/
     * {@code ||} short-circuit can never skip a DEAL-visible effect
     * (LuaJIT evaluates both {@code ===} operands strictly) — a plain
     * effectful LEFT operand is materialized right after its emission,
     * before the right operand is emitted at all, so a right read's
     * E8002 helper call can never run first (a late materialization
     * inverted the order: mark("lhs", 5) === xs[-1] raised the read's
     * E8002 before printing "lhs", and (9007199254740991 + 1) ===
     * xs[-1] raised the read's E8002 where LuaJIT raises the left
     * arithmetic's E8004 first).
     */
    private String emitArrayReadComparison(BinaryExpr bin, boolean eq) {
        Type element = comparisonElement(bin);
        if (boxedArrayReadHelper(element) == null
                || boxedArrayJavaType(element, bin.span()) == null) {
            unsupported("array comparison on " + typeName(element)
                + " elements", bin.span());
            return "false";
        }
        boolean leftNil = isNilCapableOperand(bin.left());
        boolean rightNil = isNilCapableOperand(bin.right());
        String l = emitNilCapableOperand(bin.left());
        if (!leftNil) {
            // A plain non-nil left operand is emitted as inline code.
            // Materialize any inline effect IMMEDIATELY — before the
            // right operand is even emitted — because the right
            // operand's boxed read temp appends its helper
            // pre-statement (and its receiver/index operands' hoisted
            // side effects) to preStatements, and a late
            // materialization would land the left operand's evaluation
            // after them, inverting the spec's strict left-to-right
            // order (§Operational semantics): mark("lhs", 5) === xs[-1]
            // ran the right read's E8002 before the left call printed
            // "lhs", and (9007199254740991 + 1) === xs[-1] raised the
            // read's E8002 where LuaJIT raises the left arithmetic's
            // E8004 first.
            l = materializeIfEffectful(l, bin.left());
        }
        String r = emitNilCapableOperand(bin.right());
        if (leftNil && rightNil) {
            // Both operands boxed nullable values: nil === nil is true
            // and nil !== nil is false, otherwise the unboxed values
            // compare. Neither side short-circuits an operand evaluation
            // (both are already materialized temporaries or inert code).
            if (eq) {
                return "((" + l + " == null && " + r + " == null) || ("
                    + l + " != null && " + r + " != null && "
                    + boxedEq(l, r, element) + "))";
            }
            return "((" + l + " == null) != (" + r + " == null) || ("
                + l + " != null && " + r + " != null && "
                + boxedNe(l, r, element) + "))";
        }
        // One nil-capable boxed side, one plain value side (the checker
        // enforces identical operand types, so the mixed shapes pair a
        // boolean read/&& || with a boolean value, an int read with an
        // int value, etc.).
        if (leftNil) {
            r = materializeIfEffectful(r, bin.right());
            if (eq) return "(" + l + " != null && "
                + boxedEqValue(l, r, element) + ")";
            return "(" + l + " == null || "
                + boxedNeValue(l, r, element) + ")";
        }
        // rightNil: the plain left operand was already materialized
        // before the right operand was emitted, so the comparison
        // references only inert code and its Java &&/|| short-circuit
        // can never skip a DEAL-visible effect.
        if (eq) return "(" + r + " != null && "
            + valueBoxedEq(l, r, element) + ")";
        return "(" + r + " == null || " + valueBoxedNe(l, r, element) + ")";
    }

    /**
     * Materializes the already-emitted plain comparison operand code
     * into a fresh temporary when the operand's emitted code can still
     * have an observable effect (an inline call, an assignment, checked
     * int arithmetic — anything {@link #isPureAfterEmission} flags), so
     * the surrounding nil-aware comparison expression only references
     * inert values and a Java {@code &&}/{@code ||} short-circuit can
     * never skip a DEAL-visible effect. Returns the code to use in the
     * comparison (the temporary or the inert inline code).
     */
    private String materializeIfEffectful(String code, ExpressionNode v) {
        if (isPureAfterEmission(v)) return code;

        String javaType = materializationTempType(v, null);
        if (javaType == null) return code; // diagnostic already recorded
        String temp = nextEvalTempName();
        preStatements.add(new PreLine(
            javaType + " " + temp + " = " + code + ";", 0));
        preStatementsDeclareTemps = true;
        return temp;
    }

    /**
     * Emits the boolean operand {@code e} whose Lua value can be nil
     * (see {@link #canYieldNil}) as nullable boxed code: a direct
     * {@code boolean[]} read becomes a boxed read temporary, and any
     * other shape emits normally (a nested {@code &&}/{@code ||}
     * already lowered itself to a boxed temporary). The returned code
     * can be {@code null} at runtime — always a temporary, so callers
     * may reference it more than once.
     */
    private String emitBooleanNullableOperand(ExpressionNode e) {
        if (isPrimitiveArrayRead(e)) return emitBoxedReadTemp((IndexExpr) e);
        return emitExpression(e);
    }

    /** The declared type of the {@code index}-th parameter of the local
     * module function {@code name}, or {@code null} when the function or
     * parameter is unknown (checker-gated unreachable). */
    private Type paramDeclaredType(String name, int index) {
        FunctionDeclaration fd = moduleFunctions.get(name);
        if (fd == null || index >= fd.params().size()) return null;
        return resolveTypeNode(fd.params().get(index).type());
    }

    /** The call-argument code for {@code arg}: a boolean-typed nil-aware
     * {@code &&}/{@code ||} result is converted at the parameter boundary
     * (LuaJIT's callee prologue checks the parameter and fails on nil
     * with E8001), every other argument keeps its emitted code. The
     * boundary keys on the parameter's DECLARED type: a {@code
     * boolean | null} parameter accepts the DEAL null (check_nullable),
     * a {@code boolean} parameter fails with E8001. */
    private String boundaryArgCode(ExpressionNode arg, String code,
                                   Type paramType) {

        Type intTarget = paramType != null ? paramType : typeOf(arg);
        code = adaptIntBoundary(arg, code, intTarget);
        if (needsBooleanBoundary(arg, paramType)) {
            return "booleanNotNull(" + code + ")";
        }
        if (paramType == null && needsBooleanBoundary(arg, typeOf(arg))) {
            return "booleanNotNull(" + code + ")";
        }
        if (paramType != null) {
            String paramJava = javaLocalType(paramType, arg.span());
            return coerceNullValueCode(code, arg, paramJava, arg.span());
        }
        return code;
    }
    /** Equality of two boxed read values ({@code null} handled by the
     * caller): strings via {@code equals}, the other primitives unboxed. */
    private String boxedEq(String a, String b, Type element) {
        return element instanceof Type.String
            ? a + ".equals(" + b + ")"
            : boxedUnbox(a, element) + " == " + boxedUnbox(b, element);
    }

    /** Inequality of two boxed read values ({@code null} handled by the
     * caller). */
    private String boxedNe(String a, String b, Type element) {
        return element instanceof Type.String
            ? "(!" + a + ".equals(" + b + "))"
            : boxedUnbox(a, element) + " != " + boxedUnbox(b, element);
    }

    /** Equality of a boxed read value with a plain value operand. */
    private String boxedEqValue(String boxed, String value, Type element) {
        return element instanceof Type.String
            ? boxed + ".equals(" + value + ")"
            : boxedUnbox(boxed, element) + " == " + value;
    }

    /** Inequality of a boxed read value with a plain value operand. */
    private String boxedNeValue(String boxed, String value, Type element) {
        return element instanceof Type.String
            ? "(!" + boxed + ".equals(" + value + "))"
            : boxedUnbox(boxed, element) + " != " + value;
    }

    /** Equality of a plain value operand with a boxed read value. */
    private String valueBoxedEq(String value, String boxed, Type element) {
        return element instanceof Type.String
            ? value + ".equals(" + boxed + ")"
            : value + " == " + boxedUnbox(boxed, element);
    }

    /** Inequality of a plain value operand with a boxed read value. */
    private String valueBoxedNe(String value, String boxed, Type element) {
        return element instanceof Type.String
            ? "(!" + value + ".equals(" + boxed + "))"
            : value + " != " + boxedUnbox(boxed, element);
    }

    /** Java unboxing accessor for a boxed read temporary. */
    private String boxedUnbox(String boxed, Type element) {
        return switch (element) {
            case Type.Int ignored -> boxed
                + (int32Mode ? ".intValue()" : ".longValue()");
            case Type.Number ignored -> boxed + ".doubleValue()";
            case Type.Boolean ignored -> boxed + ".booleanValue()";
            case Type.Bytes ignored -> boxed;
            default -> boxed;
        };
    }

    /**
     * Emits an array literal {@code [e1, …, eN]} for the four primitive
     * element types as {@code new __IntArray(new long[]{…})} (and the
     * empty form {@code new long[]{} — an empty literal is
     * only checker-accepted with a contextual array type, which
     * {@link #typeOf} carries}); nullable-element and class-element
     * literals emit the corresponding or-null/per-class wrapper with
     * boxed/upcast storage. Elements are emitted with
     * {@link #emitOperandsInOrder}, so left-to-right element evaluation
     * holds even when an element hoists side-effecting pre-statements
     * (an earlier inline element is materialized into a temporary before
     * the hoisted statements, exactly like LuaJIT's per-element
     * evaluation order).
     */
    private String emitArrayLiteral(ArrayLiteralExpr al) {
        Type arrayType = typeOf(al);
        if (!(arrayType instanceof Type.Array arr)) {
            unsupported("array literals without an array type", al.span());
            return "null";
        }
        String wrapper = arrayWrapperName(arr.element());
        String elemJava = javaArrayElementType(arr.element(), al.span());
        if (wrapper == null || elemJava == null) return "null";
        // Element codes flow through Java's assignment conversion inside
        // the initializer, which boxes unboxed primitive elements for the
        // nullable-element wrappers (java.lang.Long[]{5L, null}) and
        // upcasts class instances into __RefArray storage.
        List<Type> elementTargets = new ArrayList<>(al.elements().size());
        for (int i = 0; i < al.elements().size(); i++) {
            elementTargets.add(arr.element());
        }
        List<String> codes = emitOperandsInOrder(al.elements(), elementTargets);
        StringBuilder sb = new StringBuilder("new ").append(wrapper)
            .append("(new ").append(elemJava).append("[]{");
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) sb.append(", ");
            String code = codes.get(i);
            if (needsBooleanBoundary(al.elements().get(i), arr.element())) {
                code = "booleanNotNull(" + code + ")";
            }
            code = adaptIntBoundary(al.elements().get(i), code,
                arr.element());
            sb.append(code);
        }
        return sb.append("})").toString();
    }

    private String emitTableRead(MemberAccessExpr mae) {
        String obj = emitExpression(mae.object());
        Type target = typeOf(mae);
        String get = "(" + obj + ").get(" + quoteJavaString(mae.field()) + ")";
        if ((deferredHostArgReads != null
                && deferredHostArgReads.contains(mae))
                || (deferredConstructionReads != null
                    && deferredConstructionReads.contains(mae))) {

            String temp = nextEvalTempName();
            preStatements.add(new PreLine(
                "java.lang.Object " + temp + " = " + get + ";", 0));
            preStatementsDeclareTemps = true;
            return temp;
        }
        if (target instanceof Type.Class cls) {

            if (isHostModuleClass(cls)) {
                String record = hostRecordJavaRef(cls);
                if (record == null) return "null";
                return "((" + record + ") "
                    + checkCall(quoteJavaString(classCheckDescriptor(cls)),
                        get) + ")";
            }
            if (!isLocalClassType(cls)) {
                String importedModule = importedClassModuleRef(cls, mae.span());
                if (importedModule == null) return "null";
                return "((" + importedModule + "." + classNameForClass(cls.name())
                    + ") " + checkCall(
                        quoteJavaString(classCheckDescriptor(cls)), get,
                        importedModule + ".") + ")";
            }
            if (!moduleClasses.containsKey(cls.name())) {
                unsupported("class-typed table read for class '"
                    + cls.name() + "' (only local module-level classes "
                    + "are supported)", mae.span());
                return "null";
            }
            return "((" + classNameForClass(cls.name()) + ") "
                + checkCall(quoteJavaString(classCheckDescriptor(cls)), get)
                + ")";
        }
        if (target instanceof Type.Table) {
            return "(($DealRt.Table) " + checkCall("\"table\"", get)
                + ")";
        }
        if (target instanceof Type.Int) {

            return int32Mode
                ? "((java.lang.Integer) " + checkCall("\"int\"", get)
                    + ").intValue()"
                : "((java.lang.Long) " + checkCall("\"int\"", get)
                    + ").longValue()";
        }
        if (target instanceof Type.Number) {
            return "((java.lang.Double) " + checkCall("\"number\"", get)
                + ").doubleValue()";
        }
        if (target instanceof Type.Boolean) {
            return "((java.lang.Boolean) " + checkCall("\"boolean\"", get)
                + ").booleanValue()";
        }
        if (target instanceof Type.Func f) {

            String shape = registerWrapperShape(f);
            if (shape == null) {
                unsupported("table field reads with target type "
                    + typeName(target), mae.span());
                return "null";
            }
            return "((" + shape + ") "
                + checkCall(quoteJavaString(typeDescriptor(f)), get) + ")";
        }
        if (target instanceof Type.String) {

            return "((java.lang.String) " + checkCall("\"string\"", get)
                + ")";
        }
        if (target instanceof Type.Bytes) {

            return "(($DealRt.Bytes) " + checkCall("\"bytes\"", get)
                + ")";
        }
        if (target instanceof Type.Nullable nn) {

            String temp = nextEvalTempName();
            preStatements.add(new PreLine(
                "java.lang.Object " + temp + " = " + get + ";", 0));
            preStatementsDeclareTemps = true;
            Type inner = nn.inner();
            if (inner instanceof Type.Class cls) {
                if (!isLocalClassType(cls) || !moduleClasses.containsKey(cls.name())) {
                    unsupported("class-typed table read for class '"
                        + cls.name() + "' (only local module-level classes "
                        + "are supported)", mae.span());
                    return "null";
                }
                return "((" + classNameForClass(cls.name()) + ") "
                    + checkCall(
                        quoteJavaString("?" + classCheckDescriptor(cls)),
                        temp) + ")";
            }
            if (inner instanceof Type.Array arr
                    && arrayWrapperName(arr.element()) != null
                    && (elementCheckDescriptor(arr.element()) != null
                        || arr.element() instanceof Type.Array
                        || arr.element() instanceof Type.Func
                        || (arr.element() instanceof Type.Nullable ne
                            && ne.inner() instanceof Type.Func))) {
                String elemDesc = elementCheckDescriptor(arr.element());
                if (elemDesc == null) {
                    registerRefArrayShape(arr.element());
                    elemDesc = typeDescriptor(arr.element());
                }
                return "((" + arrayWrapperName(arr.element()) + ") "
                    + checkCall(quoteJavaString("?[" + elemDesc + "]"),
                        temp) + ")";
            }
            if (inner instanceof Type.Bytes) {

                return "(($DealRt.Bytes) " + checkCall("\"?bytes\"", temp)
                    + ")";
            }
            if (inner instanceof Type.Func f) {

                String shape = registerWrapperShape(f);
                if (shape == null) {
                    unsupported("table field reads with target type "
                        + typeName(target), mae.span());
                    return "null";
                }
                return "((" + shape + ") "
                    + checkCall(quoteJavaString("?" + typeDescriptor(f)),
                        temp) + ")";
            }
            if (inner instanceof Type.Int || inner instanceof Type.Number
                    || inner instanceof Type.Boolean
                    || inner instanceof Type.String) {
                // The retired $checkNullable<primitive> helpers accepted
                // exactly these four inner types; anything else falls
                // through to the single E6000 below (the pre-join gate
                // order, so the diagnostic surface stays identical).
                return "((" + nullableJavaType(inner, mae.span()) + ") "
                    + checkCall(
                        quoteJavaString(runtimeTypeDescriptor(target)),
                        temp) + ")";
            }
            unsupported("table field reads with target type "
                + typeName(target), mae.span());
            return "null";
        }
        if (target instanceof Type.Array arr) {
            String elementDesc = elementCheckDescriptor(arr.element());
            if (elementDesc == null) {

                if (arrayWrapperName(arr.element()) != null
                        && (arr.element() instanceof Type.Array
                            || arr.element() instanceof Type.Func
                            || (arr.element() instanceof Type.Nullable ne
                                && ne.inner() instanceof Type.Func))) {
                    registerRefArrayShape(arr.element());
                    elementDesc = typeDescriptor(arr.element());
                } else {
                    unsupported("table field reads with target type "
                        + typeName(target), mae.span());
                    return "null";
                }
            }
            String temp = nextEvalTempName();
            preStatements.add(new PreLine(
                "java.lang.Object " + temp + " = " + get + ";", 0));
            preStatementsDeclareTemps = true;
            return "((" + arrayWrapperName(arr.element()) + ") "
                + checkCall(quoteJavaString("[" + elementDesc + "]"),
                    temp) + ")";
        }
        unsupported("table field reads with target type " + typeName(target)
            + " (this slice checks class, nullable, array, and table "
            + "targets only)", mae.span());
        return "null";
    }

    private String emitIntrinsicCall(String name, CallExpr call) {
        if (call.args().size() != 1) {
            // Checker enforces arity; defensive backend diagnostic.
            unsupported("intrinsic '" + name + "' with " + call.args().size()
                + " arguments", call.span());
            return "null";
        }
        ExpressionNode arg = call.args().get(0);
        Type argType = typeOf(arg);
        String emitted = emitExpression(arg);

        String origin = originArgs(call.span());
        return switch (name) {
            case "int" -> {
                if (argType instanceof Type.Number) {
                    yield "intFromNumber(" + emitted + ", " + origin + ")";
                }
                if (argType instanceof Type.Int) yield emitted;
                if (argType instanceof Type.Nullable nn
                        && nn.inner() instanceof Type.Int) {
                    // int(x: int | null) — null fails at runtime with
                    // E8001 (runtime.lua's int_convert "cannot convert
                    // null to int").
                    yield "intFromNullable(" + emitted + ", " + origin + ")";
                }
                unsupported("int() on " + typeName(argType), call.span());
                yield "0L";
            }
            case "number" -> {
                if (argType instanceof Type.Int) {

                    yield "numberFromInt("
                        + adaptIntBoundary(arg, emitted,
                            Type.Int.INSTANCE) + ")";
                }
                if (argType instanceof Type.Number) yield emitted;
                if (argType instanceof Type.Nullable nn
                        && nn.inner() instanceof Type.Number) {
                    yield "numberFromNullable(" + emitted + ", " + origin
                        + ")";
                }
                unsupported("number() on " + typeName(argType), call.span());
                yield "0.0";
            }
            case "bytes" -> {

                if (argType instanceof Type.Int) {
                    yield "bytesNew("
                        + adaptIntBoundary(arg, emitted,
                            Type.Int.INSTANCE)
                        + ", " + originArgs(call.span()) + ")";
                }
                unsupported("bytes() on " + typeName(argType), call.span());
                yield "null";
            }
            default -> {
                unsupported("intrinsic '" + name + "'", call.span());
                yield "null";
            }
        };
    }

    private String emitAssignment(AssignmentExpr ae) {
        return "(" + emitAssignmentCore(ae) + ")";
    }

    /** {@code target = value} without parentheses (valid as a Java statement). */
    private String emitAssignmentCore(AssignmentExpr ae) {
        if (ae.target() instanceof IdentifierExpr id) {
            String mapped = localJavaName(id.name());
            if (mapped == null && !moduleFieldIndices.containsKey(id.name())) {
                // A write with no visible local binding and no module field:
                // the only checker-accepted such program is a write to a
                // later-declared function-local (`x = 5; let x: int = 1` —
                // the checker resolves the target contextually, so the
                // module-scope symbol table reports null). LuaJIT writes
                // the enclosing scope and then the later `local` shadows it
                // (observable only through a same-named outer binding,
                // which would be a visible local here); Java rejects the
                // forward reference, so emit E6000 instead of an artifact
                // javac would reject. Module-level writes to later-declared
                // fields (legal in Java, JLS §8.3.3 forward-reference LHS
                // exception, same final value as LuaJIT) and function-body
                // writes to a module field declared BEFORE the function
                // (LuaJIT's upvalue write — full parity) are allowed and
                // keep using the static field name. Function-body writes
                // to a field declared AFTER the function were already
                // rejected in emitFunction (forwardWriteViolations:
                // LuaJIT binds them to the GLOBAL, the Java static field
                // would pollute later readers).
                unsupported("assignment to '" + id.name() + "' before its "
                    + "declaration with no enclosing binding (LuaJIT writes "
                    + "the enclosing scope; Java rejects the forward "
                    + "reference)", ae.span());
                return "null";
            }
            String target = mapped != null ? mapped : javaName(id.name());
            Type targetType = declaredTypeForBinding(id.name());
            // The read-site target for a direct index-read RHS is the
            // assignment target's declared type: a T[] read assigned to a
            // T | null binding yields the DEAL null past the end
            // (LuaJIT's check_nullable at the assignment boundary), while
            // a non-nullable target keeps the element-typed E8001 read;
            // the same target selects the arity-extension adapter when a
            // narrower function value is assigned to a wider
            // function-typed binding.
            String value = emitTargeted(ae.value(), targetType, false);
            if (needsBooleanBoundary(ae.value(), targetType)) {
                // The boundary keys on the TARGET's declared type: a
                // nil-capable boolean result assigned into a
                // `boolean | null` binding is the DEAL null (LuaJIT's
                // check_nullable stores it, no failure), while a
                // `boolean` binding fails with E8001 exactly where
                // LuaJIT's check_boolean fails.
                value = "booleanNotNull(" + value + ")";
            }
            if (targetType == null) targetType = typeOf(ae.value());
            String targetJava = javaLocalType(targetType, ae.span());
            value = coerceNullValueCode(value, ae.value(), targetJava, ae.span());
            value = adaptIntBoundary(ae.value(), value, targetType);
            return target + " = " + value;
        }
        if (ae.target() instanceof IndexExpr idx) {
            if (typeOf(idx.array()) instanceof Type.Bytes) {

                List<String> codes = emitOperandsInOrder(
                    List.of(idx.array(), idx.index(), ae.value()),
                    Arrays.asList(null, null, Type.Int.INSTANCE));
                String rhs = adaptIntBoundary(ae.value(), codes.get(2),
                    Type.Int.INSTANCE);
                String indexCode = adaptIntBoundary(idx.index(),
                    codes.get(1), Type.Int.INSTANCE);
                return "bytesSet(" + codes.get(0) + ", " + indexCode
                    + ", " + rhs + ", " + originArgs(idx.span()) + ")";
            }

            Type indexType = typeOf(idx);
            if (indexType instanceof Type.Table) {
                unsupported("table indexing", idx.span());
                return "null";
            }
            if (indexType instanceof Type.Error) {
                unsupported("array element assignment to an errored type",
                    ae.span());
                return "null";
            }
            String writeHelper = arrayWriteHelper(indexType);
            if (writeHelper == null) {
                // (T | null)[] and C[] writes route through the
                // or-null/class helpers; any other element type records
                // the E6000.
                if (indexType instanceof Type.Nullable ne) {
                    writeHelper = orNullArrayWriteHelper(ne.inner());
                    if (writeHelper == null
                            && ne.inner() instanceof Type.Class cls) {
                        writeHelper = classArrayHelper(cls, "writeOrNull",
                            ae.span());
                    }
                    if (writeHelper == null
                            && ne.inner() instanceof Type.Func) {
                        writeHelper = refArrayWriteHelper(indexType,
                            ae.span());
                    }
                } else if (indexType instanceof Type.Class cls) {
                    writeHelper = classArrayHelper(cls, "write",
                        ae.span());
                } else if (indexType instanceof Type.Array
                        || indexType instanceof Type.Func
                        || (indexType instanceof Type.Nullable ne
                            && ne.inner() instanceof Type.Func)) {
                    writeHelper = refArrayWriteHelper(indexType, ae.span());
                }
            }
            if (writeHelper == null) {
                javaArrayElementType(indexType, ae.span());
                return "null";
            }
            // Spec §Operational semantics rule 3: the receiver, the index,
            // and the assignment RHS all evaluate (left to right) BEFORE
            // the LHS write check. emitOperandsInOrder keeps that order
            // when any operand hoists side-effecting pre-statements, and
            // the emitted helper call's Java arguments evaluate left to
            // right before the helper performs the bounds check, the
            // element value check, and the store. The helper returns the
            // stored value, so the assignment expression keeps its DEAL
            // value in value positions (`return xs[0] = 5;`,
            // `f(xs[0] = 5)`).
            List<String> codes = emitOperandsInOrder(
                List.of(idx.array(), idx.index(), ae.value()),
                Arrays.asList(null, null, indexType));
            String rhs = codes.get(2);
            if (needsBooleanBoundary(ae.value(), indexType)) {
                rhs = "booleanNotNull(" + rhs + ")";
            }
            // A null-typed assignment value (`(m = null)`) carries the
            // boxed target's Java type; coerce it to the element's
            // storage type (the write helper's parameter type).
            rhs = coerceNullValueCode(rhs, ae.value(),
                javaArrayElementType(indexType, ae.span()), ae.span());

            rhs = adaptIntBoundary(ae.value(), rhs, indexType);
            String indexCode = adaptIntBoundary(idx.index(), codes.get(1),
                Type.Int.INSTANCE);
            return writeHelper + "(" + codes.get(0) + ", " + indexCode
                + ", " + rhs + ", " + originArgs(idx.span()) + ")";
        }
        if (ae.target() instanceof MemberAccessExpr mae) {
            Type objType = typeOf(mae.object());
            if (objType instanceof Type.Table) {

                return emitTableWrite(mae, ae.value());
            }
            if (objType instanceof Type.Class) {
                Type.Class ccls = (Type.Class) objType;
                ClassField wcf = backendClassField(ccls, mae.field());
                if (wcf != null && wcf.optional()) {
                    ClassDeclaration wcd = classDeclFor(ccls);
                    if (wcd != null && wcd.isJsonable()) {
                        // A write to an OPTIONAL @jsonable class field
                        // stores the boxed value (the DEAL null for an
                        // explicit null) — the presence state is
                        // implicit: the stored value is never the
                        // Missing sentinel, so the field reads present
                        // afterwards.
                        List<String> codes = emitOperandsInOrder(
                            List.of(mae.object(), ae.value()),
                            Arrays.asList(null, typeOf(mae)));
                        String value = codes.get(1);
                        if (needsBooleanBoundary(ae.value(),
                                declaredFieldType(ccls, mae.field()))) {
                            value = "booleanNotNull(" + value + ")";
                        }
                        value = coerceNullValueCode(value, ae.value(),
                            "java.lang.Object", ae.span());
                        // The boxed Object slot is a dynamic int boundary:
                        // a wider int value narrows through checkInt
                        // before boxing (Integer storage under int32).
                        value = adaptIntBoundary(ae.value(), value,
                            declaredFieldType(ccls, mae.field()));
                        return "(" + codes.get(0) + ")."
                            + javaName(mae.field()) + " = " + value;
                    }
                }

                HostModuleDeclarations.HostField hostField = null;
                if (isHostModuleClass(ccls)) {
                    List<HostModuleDeclarations.HostField> hostFields =
                        hostFieldsFor(ccls);
                    if (hostFields != null) {
                        for (HostModuleDeclarations.HostField hf
                                : hostFields) {
                            if (hf.declaration().name()
                                    .equals(mae.field())) {
                                hostField = hf;
                                break;
                            }
                        }
                    }
                }
                Type fieldType = hostField != null
                    ? hostField.type()
                    : declaredFieldType(
                        (Type.Class) objType, mae.field());
                // The read-site target for a direct index-read RHS is the
                // declared field type (a T[] read assigned to a T | null
                // field yields the DEAL null past the end); an imported
                // class's unresolvable field falls back to the value's
                // own type below, keeping the element-typed read.
                List<String> codes = emitOperandsInOrder(
                    List.of(mae.object(), ae.value()),
                    Arrays.asList(null, fieldType));
                String value = codes.get(1);
                if (fieldType == null) fieldType = typeOf(ae.value());
                if (needsBooleanBoundary(ae.value(), fieldType)) {
                    // Same target-type boundary rule as the identifier
                    // branch: a `boolean | null` field stores the DEAL
                    // null, a `boolean` field raises E8001.
                    value = "booleanNotNull(" + value + ")";
                }
                if (fieldType != null) {
                    String fieldJava = javaLocalType(fieldType, ae.span());
                    value = coerceNullValueCode(value, ae.value(),
                        fieldJava, ae.span());
                    value = adaptIntBoundary(ae.value(), value, fieldType);
                }
                if (hostField != null) {
                    // A host-class field write stores the synthesized
                    // record slot with the declared-type coercion applied
                    // above (the record's storage type is exactly the
                    // DEAL-side JVM mapping of the declared field type).
                    // An OPTIONAL host-class field write also sets the
                    // presence flag — routed through the record's
                    // per-field $optSet$ helper (the project-class
                    // __optSet$ analog, emitted once in the shared scope)
                    // so the assignment keeps its DEAL value in value
                    // positions and has() observes the write exactly like
                    // the LuaJIT class machinery.
                    if (hostField.declaration().optional()) {
                        return "(" + codes.get(0) + ").$optSet$"
                            + javaName(mae.field()) + "(" + value + ")";
                    }
                    return "(" + codes.get(0) + ")."
                        + javaName(mae.field()) + " = " + value;
                }

                Type.Class cls = (Type.Class) objType;
                ClassDeclaration cd = moduleClasses.get(cls.name());
                if (cd != null) {
                    for (ClassField cf : cd.fields()) {
                        if (cf.optional() && cf.name().equals(mae.field())) {
                            return "__optSet$" + classNameForClass(cd.name())
                                + "$" + javaName(cf.name()) + "("
                                + codes.get(0) + ", " + value + ")";
                        }
                    }
                } else if (!isBuiltinErrorType(cls)) {
                    String module = declaringModulePath(cls);
                    Map<String, ClassDeclaration> decls = module == null
                        ? null : importedClasses.get(module);
                    ClassDeclaration icd = decls == null ? null
                        : decls.get(cls.name());
                    if (icd != null
                            && hasOptionalField(icd, mae.field())) {
                        unsupported("optional field writes on imported class '"
                            + cls.name() + "'", ae.span());
                        return "null";
                    }
                }
                return "(" + codes.get(0) + ")." + javaName(mae.field())
                    + " = " + value;
            }
            unsupported("assignment to table fields", ae.span());
            return "null";
        }
        unsupported("assignment to non-variable targets", ae.span());
        return "null";
    }

    /** The declared internal type of a class field (its annotation type,
     * wrapped in {@code Nullable} for a {@code f: T | null} field). The
     * type resolution records an E6000 only for an unsupported annotation,
     * which the class emission already rejected — so a supported field's
     * re-resolution is diagnostic-free. */
    private Type classFieldDeclaredType(ClassDeclaration cd, ClassField cf) {
        Type t = resolveTypeNode(cf.type());
        if (t == Type.Error.INSTANCE) return null;
        if (cf.nullable()) {
            // The parser keeps the whole `T | null` annotation as the
            // field's type node; the resolved type is already Nullable(T).
            if (!(t instanceof Type.Nullable)) return null;
        }
        if (cf.optional() && !(t instanceof Type.Nullable)) {

            return new Type.Nullable(t);
        }
        return t;
    }

    /** True when the class declaration {@code cd} declares an optional
     * field named {@code field} — consulted for the imported-class
     * optional-write guard. */
    private static boolean hasOptionalField(ClassDeclaration cd,
            String field) {
        for (ClassField cf : cd.fields()) {
            if (cf.optional() && cf.name().equals(field)) return true;
        }
        return false;
    }

    /** The declared type of a local class's field, or {@code null} when
     * the class or field is unknown (checker-gated unreachable). */
    private Type declaredFieldType(Type.Class cls, String fieldName) {
        ClassDeclaration cd = moduleClasses.get(cls.name());
        if (cd == null) return null;
        for (ClassField cf : cd.fields()) {
            if (cf.name().equals(fieldName)) {
                return classFieldDeclaredType(cd, cf);
            }
        }
        return null;
    }

    // =========================================================================
    // Use-before-declaration detection
    // =========================================================================

    /**
     * Returns the name of a value-position identifier use in {@code stmt}
     * that binds to no enclosing variable and resolves to nothing usable at
     * module scope — i.e. a use of a variable before its own declaration (or
     * a self-reference in its initializer) with no outer binding to fall
     * back to, or a module-level (load-time) forward reference to a
     * later-declared module field. LuaJIT reads the not-yet-declared global
     * value for such uses (nil unless a prior write established it), and
     * emitting a Java forward reference would make javac reject an artifact
     * the CLI reported as successful. Returns {@code null} when the
     * statement is clean.
     *
     * <p>Function-body reads of <em>declared</em> module fields are NOT
     * flagged even when the field is declared later: method bodies may
     * legally reference later-declared static fields, and post-load reads
     * match LuaJIT (see {@link #isUndeclaredVariableUse}).
     *
     * <p>Only the statement's own value positions are checked; nested
     * statements are checked individually when they are emitted, so a block
     * that declares a variable and then uses it stays clean.
     */
    private String undeclaredVariableUse(StatementNode stmt) {
        return switch (stmt) {
            case VariableDeclaration vd -> undeclaredUseIn(vd.initializer());
            // Every condition of the if/else-if chain: emitIf and
            // emitIfContinuation emit the follow-on conditions directly via
            // emitExpression (no statement-level guard runs for them), so
            // the head statement must walk the whole chain — a
            // later-declared variable in an else-if condition would
            // otherwise emit an illegal forward reference (module level)
            // or a cannot-find-symbol reference (function body) that javac
            // rejects after the CLI reported success.
            case IfStatement is -> undeclaredUseInIfChain(is);
            // The while condition is emitted directly by emitWhile (no
            // per-statement guard runs for it) — a later-declared variable
            // there would emit an illegal forward reference (module level)
            // or a cannot-find-symbol reference (function body). Body
            // statements are checked individually when emitted.
            case WhileStatement ws -> undeclaredUseIn(ws.condition());
            case ReturnStatement rs -> rs.expr().map(this::undeclaredUseIn).orElse(null);
            case ExpressionStatement es -> undeclaredUseIn(es.expr());
            default -> null; // functions/imports/exports: separate scopes or no
                              // value uses; unsupported kinds rejected elsewhere
        };
    }

    /** Walks the conditions of an {@code if}/{@code else if} chain. */
    private String undeclaredUseInIfChain(IfStatement is) {
        String r = undeclaredUseIn(is.condition());
        if (r != null) return r;
        Optional<Either<IfStatement, Block>> branch = is.elseBranch();
        if (branch.isPresent()
                && branch.get() instanceof Either.Left<IfStatement, Block> left) {
            return undeclaredUseInIfChain(left.value());
        }
        // then/else BLOCK statements are checked individually when emitted.
        return null;
    }

    private String undeclaredUseIn(ExpressionNode e) {
        return switch (e) {
            case IdentifierExpr id ->
                isUndeclaredVariableUse(id.name()) ? id.name() : null;
            case BinaryExpr bin ->
                firstNonNull(undeclaredUseIn(bin.left()), undeclaredUseIn(bin.right()));
            case UnaryExpr u -> undeclaredUseIn(u.expr());
            case CallExpr call -> {
                String r = null;
                if (call.callee() instanceof IdentifierExpr id
                        && isUndeclaredVariableUse(id.name())) {
                    r = id.name();
                }
                for (ExpressionNode arg : call.args()) {
                    if (r == null) r = undeclaredUseIn(arg);
                }
                yield r;
            }
            // Assignment target (write) positions get their own guard in
            // emitAssignmentCore: a write to a later-declared local with
            // no enclosing binding is E6000 there, so only the value side
            // is walked here. An INDEX target's array and index
            // expressions are value positions (reads): a later-declared
            // identifier there is a forward reference the emitted Java
            // would reject (or — at module level — an illegal static-field
            // forward reference), so they are walked like any other read.
            case AssignmentExpr ae -> {
                String r = undeclaredUseIn(ae.value());
                if (r != null) yield r;
                if (ae.target() instanceof IndexExpr idx) {
                    r = undeclaredUseIn(idx.array());
                    if (r == null) r = undeclaredUseIn(idx.index());
                }
                yield r;
            }
            case MemberAccessExpr mae -> undeclaredUseIn(mae.object());
            case IndexExpr idx ->
                firstNonNull(undeclaredUseIn(idx.array()), undeclaredUseIn(idx.index()));
            case ArrayLiteralExpr al -> firstNonNullIn(al.elements());
            case ObjectLiteralExpr ol ->
                firstNonNullIn(ol.properties().stream().map(Property::value).toList());
            case HasExpr he -> undeclaredUseIn(he.object());
            case TemplateLiteralExpr tl -> firstNonNullIn(tl.parts());
            case AwaitExpression aw -> undeclaredUseIn(aw.callee());
            case FunctionExpr fe -> null; // nested scope of its own
            case LiteralExpr lit -> null;
        };
    }

    private String firstNonNullIn(List<ExpressionNode> exprs) {
        for (ExpressionNode e : exprs) {
            String r = undeclaredUseIn(e);
            if (r != null) return r;
        }
        return null;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    /**
     * True when a value-position use of {@code name} has no enclosing
     * variable binding at emission time: either the name resolves to nothing
     * at module scope (a later-declared function-local, or a use the checker
     * only accepted because it binds to a variable declared later in an
     * enclosing scope) or it resolves to a module variable that has not been
     * emitted yet (a forward field reference or a module-level
     * self-reference).
     */
    private boolean isUndeclaredVariableUse(String name) {
        if (localJavaName(name) != null) return false;
        Symbol sym = symbols.resolve(name);
        if (sym == null) return true;
        if (sym instanceof Symbol.VariableSymbol) {
            // A value-position read of a module field from inside a
            // FUNCTION body is legal Java (method bodies may reference
            // later-declared static fields; the illegal-forward-reference
            // rule of JLS §8.3.3 covers only initializers). Reads of a
            // field declared BEFORE the function are the module-local
            // upvalue read — full parity. Reads of a field declared
            // AFTER the function never reach this point: they are
            // rejected by the write-dominance analysis in emitFunction
            // (LuaJIT binds them to the GLOBAL at call time — nil unless
            // a prior write established it — while Java would silently
            // read the initialized static field). At module level (load
            // time) a later-declared field is genuinely
            // not-yet-declared under LuaJIT (nil unless written) and an
            // illegal forward reference in Java — keep rejecting there.
            if (moduleFieldIndices.containsKey(name)
                    && currentModuleStatementIndex < 0) {
                return false;
            }
            return true;
        }
        return false;
    }

    // =========================================================================
    // Type mapping
    // =========================================================================

    private Type typeOf(ExpressionNode e) {
        Type t = typeMap.get(e);
        return t != null ? t : Type.Error.INSTANCE;
    }

    /**
     * Resolves a type annotation to the internal type. Unsupported forms
     * (qualified types, function types, tables, and the unsupported
     * named/inner forms) record an E6000 diagnostic and return
     * {@code Type.Error.INSTANCE}; nullable and array wrappers resolve
     * their inner forms and delegate support checks to the Java type
     * mappers.
     */
    private Type resolveTypeNode(TypeNode typeNode) {
        return switch (typeNode) {
            case NamedType nt -> switch (nt.name()) {
                case "null" -> Type.Null.INSTANCE;
                case "boolean" -> Type.Boolean.INSTANCE;
                case "int" -> Type.Int.INSTANCE;
                case "number" -> Type.Number.INSTANCE;
                case "string" -> Type.String.INSTANCE;
                case "table" -> Type.Table.INSTANCE;
                case "bytes" -> {

                    Symbol sym = symbols.resolve(nt.name());
                    if (sym instanceof Symbol.ClassSymbol
                            && moduleClasses.containsKey(nt.name())) {
                        yield classTypeFor(nt.name(), modulePath);
                    }
                    yield Type.Bytes.INSTANCE;
                }
                default -> {

                    Symbol sym = symbols.resolve(nt.name());
                    if (sym instanceof Symbol.ClassSymbol
                            && moduleClasses.containsKey(nt.name())) {
                        yield classTypeFor(nt.name(), modulePath);
                    }
                    if ("Error".equals(nt.name())
                            && sym instanceof Symbol.ClassSymbol) {

                        yield errorClassType();
                    }
                    unsupported("type '" + nt.name() + "' (only local classes "
                        + "are supported)", nt.span());
                    yield Type.Error.INSTANCE;
                }
            };
            case QualifiedType qt -> {

                String hostRaw = hostAliases.get(qt.moduleName());
                if (hostRaw != null) {
                    Map<String, Type> exports = hostModules.get(hostRaw);
                    Type exportType = exports == null ? null
                        : exports.get(qt.typeName());
                    if (exportType instanceof Type.Class c) {
                        yield c;
                    }
                    unsupported("qualified type '" + qt.moduleName() + "."
                        + qt.typeName() + "' (not a declared host class "
                        + "export)", qt.span());
                    yield Type.Error.INSTANCE;
                }

                String module = importAliases.get(qt.moduleName());
                Map<String, ClassDeclaration> decls =
                    module == null ? null : importedClasses.get(module);
                if (decls == null || !decls.containsKey(qt.typeName())) {
                    unsupported("qualified type '" + qt.moduleName() + "."
                        + qt.typeName() + "' (only classes of imported "
                        + "compiled project modules are supported)",
                        qt.span());
                    yield Type.Error.INSTANCE;
                }
                yield classTypeFor(qt.typeName(), module);
            }
            case ArrayType at -> {
                Type elem = resolveTypeNode(at.elementType());
                if (elem == Type.Error.INSTANCE) {
                    // Inner resolution already recorded its E6000 (e.g. a
                    // nested array, a function element, or an unsupported
                    // named type); do not double-report.
                    yield Type.Error.INSTANCE;
                }
                if (javaArrayElementType(elem, at.span()) == null) {
                    yield Type.Error.INSTANCE;
                }
                yield new Type.Array(elem);
            }
            case NullableType nt -> {
                Type inner = resolveTypeNode(nt.innerType());
                if (inner == Type.Error.INSTANCE) {
                    // Inner resolution already recorded its E6000; do not
                    // double-report.
                    yield Type.Error.INSTANCE;
                }
                if (inner instanceof Type.Null
                        || inner instanceof Type.Nullable) {
                    // `null | null` and `(T | null) | null` are frontend
                    // errors (spec §Type grammar); the defensive gate keeps
                    // the Type.Nullable invariant (inner is never null or
                    // nullable).
                    unsupported("nullable type '" + typeName(inner)
                        + " | null'", nt.span());
                    yield Type.Error.INSTANCE;
                }
                yield new Type.Nullable(inner);
            }
            case FunctionType ft -> {

                List<Type> paramTypes = new ArrayList<>();
                boolean ok = true;
                for (FunctionTypeParam p : ft.params()) {
                    Type pt = resolveTypeNode(p.type());
                    if (pt == Type.Error.INSTANCE) {
                        ok = false;
                        break;
                    }
                    if (hasTableCarrier(pt)) {
                        unsupported("function values whose signature "
                            + "contains table carriers (deferred to "
                            + "the ISSUE-0110 descriptor join)",
                            p.type().span());
                        ok = false;
                        break;
                    }
                    paramTypes.add(pt);
                }
                Type rt = resolveTypeNode(ft.returnType());
                if (rt == Type.Error.INSTANCE) {
                    ok = false;
                } else if (hasTableCarrier(rt)) {
                    unsupported("function values whose signature contains "
                        + "table carriers (deferred to the ISSUE-0110 "
                        + "descriptor join)", ft.returnType().span());
                    ok = false;
                }
                yield ok ? new Type.Func(paramTypes, rt, ft.isAsync())
                         : Type.Error.INSTANCE;
            }
        };
    }

    private static boolean hasTableCarrier(Type t) {
        if (t instanceof Type.Table) {
            return true;
        }
        if (t instanceof Type.Array a) {
            return hasTableCarrier(a.element());
        }
        if (t instanceof Type.Nullable n) {
            return hasTableCarrier(n.inner());
        }
        if (t instanceof Type.Func f) {
            for (Type p : f.paramTypes()) {
                if (hasTableCarrier(p)) return true;
            }
            return hasTableCarrier(f.returnType());
        }
        return false;
    }

    /** Java type for a local/parameter/field. {@code null} when unsupported. */
    private String javaLocalType(Type t, Span span) {
        return switch (t) {

            case Type.Int ignored -> int32Mode ? "int" : "long";
            case Type.Number ignored -> "double";
            case Type.Boolean ignored -> "boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Null ignored -> "java.lang.Void";
            case Type.Array a -> {
                if (javaArrayElementType(a.element(), span) == null) {
                    yield null;
                }
                yield arrayWrapperName(a.element());
            }
            case Type.Table ignored -> "$DealRt.Table";
            case Type.Class c -> {

                if (isBuiltinErrorType(c)) {
                    yield "java.lang.RuntimeException";
                }

                if (isHostModuleClass(c)) {
                    String record = hostRecordJavaRef(c);
                    if (record == null) {
                        unsupported("values of class type '" + c.name()
                            + "' (the externals identity is "
                            + "unrepresentable)", span);
                        yield null;
                    }
                    yield record;
                }

                if (isLocalClassType(c)) {
                    if (!moduleClasses.containsKey(c.name())) {
                        unsupported("values of class type '" + c.name()
                            + "' (only local module-level classes are "
                            + "supported)", span);
                        yield null;
                    }
                    yield classNameForClass(c.name());
                }
                String importedModule = importedClassModuleRef(c, span);
                if (importedModule == null) yield null;
                yield importedModule + "." + classNameForClass(c.name());
            }
            case Type.Nullable n -> {
                // T | null maps to the boxed reference representation
                // (spec-v1.2 §JVM value mapping: "nullable JVM reference
                // or tagged nullable wrapper for primitives"): boxed
                // java.lang.Long/Double/Boolean for the numeric
                // primitives, the (already-nullable) java.lang.String
                // reference, the generated class reference, and the
                // emitted array wrapper reference for T[] | null.
                yield nullableJavaType(n.inner(), span);
            }
            case Type.Bytes ignored -> {

                yield "$DealRt.Bytes";
            }
            case Type.Error ignored -> null;
            case Type.Func f -> {

                String shape = registerWrapperShape(f);
                if (shape == null) {
                    unsupported("function values whose signature contains "
                        + "unsupported carriers (table | null positions and "
                        + "host-class carriers — deferred to the ISSUE-0110 "
                        + "descriptor join)", span);
                    yield null;
                }
                yield shape;
            }
            default -> {
                unsupported("values of type " + typeName(t), span);
                yield null;
            }
        };
    }

    /** Java type of a LOCAL class value: only local module-level classes
     * have emitted Java types (see {@link #isLocalClassType}). */
    private String localClassJavaType(Type.Class c, Span span) {

        if (!isLocalClassType(c)) {
            unsupported("values of imported class type '" + c.name()
                + "' (imported classes / cross-module nominal identity "
                + "are deferred to ISSUE-0109)", span);
            return null;
        }
        if (!moduleClasses.containsKey(c.name())) {
            unsupported("values of class type '" + c.name()
                + "' (only local module-level classes are supported)",
                span);
            return null;
        }
        return classNameForClass(c.name());
    }

    private String nullableJavaType(Type inner, Span span) {
        return switch (inner) {

            case Type.Int ignored ->
                int32Mode ? "java.lang.Integer" : "java.lang.Long";
            case Type.Number ignored -> "java.lang.Double";
            case Type.Boolean ignored -> "java.lang.Boolean";
            case Type.String ignored -> "java.lang.String";
            case Type.Class c -> {

                if (isBuiltinErrorType(c)) {
                    yield "java.lang.RuntimeException";
                }

                if (isHostModuleClass(c)) {
                    String record = hostRecordJavaRef(c);
                    if (record == null) {
                        unsupported("values of class type '" + c.name()
                            + "' | null", span);
                        yield null;
                    }
                    yield record;
                }
                if (isLocalClassType(c)) {
                    yield localClassJavaType(c, span);
                }
                String importedModule = importedClassModuleRef(c, span);
                if (importedModule == null) yield null;
                yield importedModule + "." + classNameForClass(c.name());
            }
            case Type.Array a -> {
                if (javaArrayElementType(a.element(), span) == null) {
                    yield null;
                }
                yield arrayWrapperName(a.element());
            }
            case Type.Null ignored -> {
                unsupported("nullable type 'null | null'", span);
                yield null;
            }
            case Type.Func f -> {

                String shape = registerWrapperShape(f);
                if (shape == null) {
                    unsupported("function values whose signature contains "
                        + "unsupported carriers (table | null positions and "
                        + "host-class carriers — deferred to the ISSUE-0110 "
                        + "descriptor join)", span);
                    yield null;
                }
                yield shape;
            }
            case Type.Bytes ignored -> {

                yield "$DealRt.Bytes";
            }
            default -> {
                unsupported("values of type " + typeName(inner) + " | null"
                    + " (only primitive, local class, and supported array"
                    + " inner types)", span);
                yield null;
            }
        };
    }

    /**
     * Java storage element type for a supported array element type, or
     * {@code null} (with an E6000 diagnostic recorded) for any other
     * element type: the primitive elements, their nullable forms
     * (boxed storage for {@code (T | null)[]}), bytes elements
     * ({@code bytes[]} and {@code (bytes | null)[]} through the shared
     * {@code __BytesArray}/{@code __BytesOrNullArray} carriers), local
     * class elements ({@code java.lang.Object} storage inside the
     * per-class {@code $Array$<C>} wrapper), nested arrays, and function
     * elements ({@code java.lang.Object} storage inside the shared
     * per-element-shape {@code $DealRt} wrappers with per-element
     * checks) are in scope — table elements and other unsupported
     * shapes are rejected, never silently miscompiled.
     */
    private String javaArrayElementType(Type element, Span span) {
        return switch (element) {
            case Type.Int ignored -> int32Mode ? "int" : "long";
            case Type.Number ignored -> "double";
            case Type.String ignored -> "java.lang.String";
            case Type.Boolean ignored -> "boolean";
            case Type.Nullable ne -> switch (ne.inner()) {
                case Type.Int ignored ->
                    int32Mode ? "java.lang.Integer" : "java.lang.Long";
                case Type.Number ignored -> "java.lang.Double";
                case Type.String ignored -> "java.lang.String";
                case Type.Boolean ignored -> "java.lang.Boolean";
                case Type.Class c -> {

                    if (isDeclaredHostClass(c)) {
                        yield "java.lang.Object";
                    }
                    if (localClassJavaType(c, span) == null) yield null;
                    yield "java.lang.Object";
                }
                case Type.Bytes ignored -> {

                    yield "$DealRt.Bytes";
                }
                case Type.Func f -> {

                    yield "java.lang.Object";
                }
                default -> {
                    unsupported("arrays with element type "
                        + typeName(element)
                        + " (only int[], number[], string[], boolean[], "
                        + "bytes[], class arrays, nested arrays, function "
                        + "arrays, and their nullable-element forms are "
                        + "supported)", span);
                    yield null;
                }
            };
            case Type.Class c -> {

                if (isDeclaredHostClass(c)) {
                    yield "java.lang.Object";
                }
                if (isLocalClassType(c)) {
                    if (localClassJavaType(c, span) == null) yield null;
                } else {
                    if (importedClassModuleRef(c, span) == null) yield null;
                }
                yield "java.lang.Object";
            }
            case Type.Bytes ignored -> {

                yield "$DealRt.Bytes";
            }
            case Type.Array inner -> {

                if (arrayWrapperName(inner) == null) {
                    unsupported("nested arrays with element type "
                        + typeName(element), span);
                    yield null;
                }
                yield "java.lang.Object";
            }
            case Type.Func f -> {

                yield "java.lang.Object";
            }
            default -> {
                unsupported("arrays with element type " + typeName(element)
                    + " (only int[], number[], string[], boolean[], "
                    + "bytes[], class arrays, nested arrays, function "
                    + "arrays, and their nullable-element forms are "
                    + "supported)", span);
                yield null;
            }
        };
    }

    private String arrayWrapperName(Type element) {
        switch (element) {
            case Type.Int ignored -> {
                return "$DealRt.__IntArray";
            }
            case Type.Number ignored -> {
                return "$DealRt.__NumberArray";
            }
            case Type.String ignored -> {
                return "$DealRt.__StringArray";
            }
            case Type.Boolean ignored -> {
                return "$DealRt.__BooleanArray";
            }
            case Type.Nullable ne -> {
                return switch (ne.inner()) {
                    case Type.Int ignored -> "$DealRt.__IntOrNullArray";
                    case Type.Number ignored -> "$DealRt.__NumberOrNullArray";
                    case Type.String ignored -> "$DealRt.__StringOrNullArray";
                    case Type.Boolean ignored -> "$DealRt.__BooleanOrNullArray";
                    case Type.Class c -> {

                        if (isDeclaredHostClass(c)) {
                            yield hostClassArrayWrapperRef(c);
                        }
                        yield classOrNullArrayWrapperName(c.name());
                    }
                    case Type.Func f -> "$DealRt."
                        + refArrayWrapperId(element);
                    case Type.Bytes ignored -> "$DealRt.__BytesOrNullArray";
                    default -> null;
                };
            }
            case Type.Class c -> {

                if (isDeclaredHostClass(c)) {
                    return hostClassArrayWrapperRef(c);
                }
                return isLocalClassType(c)
                    ? classArrayWrapperName(c.name())
                    : importedArrayWrapperName(c);
            }

            case Type.Array inner -> {
                return "$DealRt." + refArrayWrapperId(element);
            }
            case Type.Func f -> {
                return "$DealRt." + refArrayWrapperId(element);
            }
            case Type.Bytes ignored -> {
                return "$DealRt.__BytesArray";
            }
            default -> {
                return null;
            }
        }
    }

    /** The identifier-safe wrapper class id for a nested-array,
     * function-array, or bytes-array element shape:
     * {@code __A$<escaped canonical descriptor>} — injective over the
     * canonical grammar like {@link #fnShapeId}, distinct from every
     * primitive wrapper id and from {@link #javaName} output. */
    private String refArrayWrapperId(Type element) {
        return "__A$" + escapedIdentifier(typeDescriptor(element));
    }

    /** The identifier-safe wrapper id (without the {@code $DealRt.}
     * prefix) for any element shape: the primitive/nullable-primitive
     * class names, the per-class wrapper names, and the
     * {@code __A$...} ids for the complex shapes. Helper-method names
     * key on this id, so they stay valid Java identifiers across the
     * shared scope. */
    private String arrayWrapperId(Type element) {
        return switch (element) {
            case Type.Int ignored -> "__IntArray";
            case Type.Number ignored -> "__NumberArray";
            case Type.String ignored -> "__StringArray";
            case Type.Boolean ignored -> "__BooleanArray";
            case Type.Bytes ignored -> "__BytesArray";
            case Type.Nullable ne -> switch (ne.inner()) {
                case Type.Int ignored -> "__IntOrNullArray";
                case Type.Number ignored -> "__NumberOrNullArray";
                case Type.String ignored -> "__StringOrNullArray";
                case Type.Boolean ignored -> "__BooleanOrNullArray";
                case Type.Bytes ignored -> "__BytesOrNullArray";
                case Type.Class c -> classOrNullArrayWrapperName(c.name());
                default -> refArrayWrapperId(element);
            };
            case Type.Class c -> classArrayWrapperName(c.name());
            default -> refArrayWrapperId(element);
        };
    }

    /** Emitted read-helper method name for a supported primitive element
     * type (same {@code null}-on-unsupported contract as
     * {@link #arrayWrapperName}). */
    private String arrayReadHelper(Type element) {
        return switch (element) {
            case Type.Int ignored -> "__intArrayRead";
            case Type.Number ignored -> "__numberArrayRead";
            case Type.String ignored -> "__stringArrayRead";
            case Type.Boolean ignored -> "__booleanArrayRead";
            case Type.Bytes ignored -> "__bytesArrayRead";
            default -> null;
        };
    }

    /** Emitted boxed read-helper method name for a supported primitive
     * element type (same {@code null}-on-unsupported contract as
     * {@link #arrayReadHelper}). The boxed helper yields {@code null}
     * past the end (the LuaJIT nil) instead of raising E8001, for the
     * comparison positions whose read site applies no typed boundary
     * (spec §Bounds and nil behavior); a negative index still raises
     * E8002, which LuaJIT emits unconditionally at the read. */
    private String arrayReadBoxedHelper(Type element) {
        return switch (element) {
            case Type.Int ignored -> "__intArrayReadBoxed";
            case Type.Number ignored -> "__numberArrayReadBoxed";
            case Type.String ignored -> "__stringArrayReadBoxed";
            case Type.Boolean ignored -> "__booleanArrayReadBoxed";
            case Type.Bytes ignored -> "__bytesArrayReadBoxed";
            default -> null;
        };
    }

    /** Java reference type of a boxed read temporary for a supported
     * primitive element type (nullable, unlike the storage types). */
    private String arrayBoxedJavaType(Type element) {
        return switch (element) {
            case Type.Int ignored ->
                int32Mode ? "java.lang.Integer" : "java.lang.Long";
            case Type.Number ignored -> "java.lang.Double";
            case Type.String ignored -> "java.lang.String";
            case Type.Boolean ignored -> "java.lang.Boolean";
            case Type.Bytes ignored -> "$DealRt.Bytes";
            default -> null;
        };
    }

    /** The nil-yielding boxed read helper for a supported primitive or
     * local class element type: the primitive boxed helpers (null past
     * the end, E8002 for a negative index), and the per-class
     * {@code $classOrNullArrayRead$<C>} helper for a local class
     * element ({@code C[]} reads in nil-accepting positions — a
     * {@code C | null} target or a comparison operand — yield the DEAL
     * null past the end with the same negative-index E8002). The caller
     * gates class locality before requesting the class helper. */
    private String boxedArrayReadHelper(Type element) {
        String boxed = arrayReadBoxedHelper(element);
        if (boxed != null) return boxed;
        if (element instanceof Type.Class cls) {
            return classOrNullArrayReadName(cls.name());
        }
        return null;
    }

    /** Java reference type of a nil-yielding read temporary for a
     * supported primitive or local class element type (the boxed
     * wrapper types; the generated class reference for a class element
     * — all nullable, unlike the storage types). */
    private String boxedArrayJavaType(Type element, Span span) {
        String boxed = arrayBoxedJavaType(element);
        if (boxed != null) return boxed;
        if (element instanceof Type.Class cls) {
            return localClassJavaType(cls, span);
        }
        return null;
    }

    /** Emitted write-helper method name for a supported primitive element
     * type (same {@code null}-on-unsupported contract as
     * {@link #arrayWrapperName}). */
    private String arrayWriteHelper(Type element) {
        return switch (element) {
            case Type.Int ignored -> "__intArrayWrite";
            case Type.Number ignored -> "__numberArrayWrite";
            case Type.String ignored -> "__stringArrayWrite";
            case Type.Boolean ignored -> "__booleanArrayWrite";
            case Type.Bytes ignored -> "__bytesArrayWrite";
            default -> null;
        };
    }

    /** Emitted write-helper method name for a supported primitive inner
     * type of a nullable element ({@code (T | null)[]} writes accept the
     * DEAL null element — check_nullable permits it); {@code null} for
     * non-primitive inners (class inners route through the per-class
     * {@code $classOrNullArrayWrite$} helper). */
    private String orNullArrayWriteHelper(Type inner) {
        return switch (inner) {
            case Type.Int ignored -> "__intOrNullArrayWrite";
            case Type.Number ignored -> "__numberOrNullArrayWrite";
            case Type.String ignored -> "__stringOrNullArrayWrite";
            case Type.Boolean ignored -> "__booleanOrNullArrayWrite";
            case Type.Bytes ignored -> "__bytesOrNullArrayWrite";
            default -> null;
        };
    }

    /** Emitted read-helper method name for a supported primitive inner
     * type of a nullable element ({@code (T | null)[]} reads yield the
     * DEAL null past the end); {@code null} for non-primitive inners
     * (class inners route through the per-class
     * {@code $classOrNullArrayRead$} helper). */
    private String orNullArrayReadHelper(Type inner) {
        return switch (inner) {
            case Type.Int ignored -> "__intOrNullArrayRead";
            case Type.Number ignored -> "__numberOrNullArrayRead";
            case Type.String ignored -> "__stringOrNullArrayRead";
            case Type.Boolean ignored -> "__booleanOrNullArrayRead";
            case Type.Bytes ignored -> "__bytesOrNullArrayRead";
            default -> null;
        };
    }

    /**
     * Per-shape helper text for nested arrays and function arrays,
     * accumulated on demand and spliced with the wrapper classes at
     * class-body level. Every helper name starts with {@code __} and is
     * keyed by the inner wrapper/signature name, both unreachable from
     * {@link #javaName}.
     */
    private final StringBuilder arrayHelpers = new StringBuilder();
    private final Set<String> emittedArrayHelpers = new LinkedHashSet<>();

    private String refArrayReadHelper(Type element, Span span) {
        if (element instanceof Type.Array inner) {
            Type innerElem = inner.element();
            String innerWrapper = arrayWrapperName(innerElem);
            if (innerWrapper == null) {
                unsupported("nested arrays with element type "
                    + typeName(innerElem), span);
                return null;
            }
            registerRefArrayShape(element);
            String key = "nestedarr$" + arrayWrapperId(innerElem);
            if (emittedArrayHelpers.add(key)) {
                emitNestedArrayHelpers(innerElem, arrayWrapperName(element),
                    arrayWrapperId(innerElem));
            }
            return "__nestedRead$" + arrayWrapperId(innerElem);
        }
        if (element instanceof Type.Nullable ne
                && ne.inner() instanceof Type.Func f) {
            if (registerWrapperShape(f) == null) {
                unsupported("function arrays whose signature contains "
                    + "unsupported carriers (table | null positions and "
                    + "host-class carriers — deferred to the ISSUE-0110 "
                    + "descriptor join)", span);
                return null;
            }
            registerRefArrayShape(element);
            String key = "fnarr$" + refArrayWrapperId(element);
            if (emittedArrayHelpers.add(key)) {
                emitFnArrayHelpers(f, fnShapeId(f), true,
                    arrayWrapperName(element), refArrayWrapperId(element));
            }
            return "__fnOrNullRead$" + fnShapeId(f);
        }
        if (element instanceof Type.Func f) {
            if (registerWrapperShape(f) == null) {
                unsupported("function arrays whose signature contains "
                    + "unsupported carriers (table | null positions and "
                    + "host-class carriers — deferred to the ISSUE-0110 "
                    + "descriptor join)", span);
                return null;
            }
            registerRefArrayShape(element);
            String key = "fnarr$" + refArrayWrapperId(element);
            if (emittedArrayHelpers.add(key)) {
                emitFnArrayHelpers(f, fnShapeId(f), false,
                    arrayWrapperName(element), refArrayWrapperId(element));
            }
            return "__fnRead$" + fnShapeId(f);
        }
        return null;
    }

    /** Write-helper name for a nested-array or function-array element
     * type, or {@code null} (with E6000 recorded) for any other shape. */
    private String refArrayWriteHelper(Type element, Span span) {
        if (element instanceof Type.Array inner) {
            Type innerElem = inner.element();
            String innerWrapper = arrayWrapperName(innerElem);
            if (innerWrapper == null) {
                unsupported("nested arrays with element type "
                    + typeName(innerElem), span);
                return null;
            }
            registerRefArrayShape(element);
            String key = "nestedarr$" + arrayWrapperId(innerElem);
            if (emittedArrayHelpers.add(key)) {
                emitNestedArrayHelpers(innerElem, arrayWrapperName(element),
                    arrayWrapperId(innerElem));
            }
            return "__nestedWrite$" + arrayWrapperId(innerElem);
        }
        if (element instanceof Type.Nullable ne
                && ne.inner() instanceof Type.Func f) {
            if (registerWrapperShape(f) == null) {
                unsupported("function arrays whose signature contains "
                    + "unsupported carriers (table | null positions and "
                    + "host-class carriers — deferred to the ISSUE-0110 "
                    + "descriptor join)", span);
                return null;
            }
            registerRefArrayShape(element);
            String key = "fnarr$" + refArrayWrapperId(element);
            if (emittedArrayHelpers.add(key)) {
                emitFnArrayHelpers(f, fnShapeId(f), true,
                    arrayWrapperName(element), refArrayWrapperId(element));
            }
            return "__fnOrNullWrite$" + fnShapeId(f);
        }
        if (element instanceof Type.Func f) {
            if (registerWrapperShape(f) == null) {
                unsupported("function arrays whose signature contains "
                    + "unsupported carriers (table | null positions and "
                    + "host-class carriers — deferred to the ISSUE-0110 "
                    + "descriptor join)", span);
                return null;
            }
            registerRefArrayShape(element);
            String key = "fnarr$" + refArrayWrapperId(element);
            if (emittedArrayHelpers.add(key)) {
                emitFnArrayHelpers(f, fnShapeId(f), false,
                    arrayWrapperName(element), refArrayWrapperId(element));
            }
            return "__fnWrite$" + fnShapeId(f);
        }
        return null;
    }

    private void registerRefArrayShape(Type element) {
        if (refArrayCheckElements.add(element)) {
            String elemDesc = typeDescriptor(element);
            String ref = "$DealRt." + refArrayWrapperId(element);
            StringBuilder body = new StringBuilder();
            body.append("if (descriptor.equals(")
                .append(quoteJavaString("[" + elemDesc + "]"))
                .append(")) {\n");
            body.append("    if (v instanceof ").append(ref)
                .append(" a) return a;\n");
            body.append("    if (v instanceof $DealRt.Table t && t.$array() != null) {\n");
            body.append("        java.util.ArrayList<java.lang.Object> a = t.$array();\n");
            body.append("        java.lang.Object[] data = new java.lang.Object[a.size()];\n");
            body.append("        for (int i = 0; i < a.size(); i++) {\n");
            body.append("            try { data[i] = $check(")
                .append(quoteJavaString(elemDesc)).append(", a.get(i), oFile, oLine, oCol); }\n");
            body.append("            catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol, ")
                .append(quoteJavaString(elemDesc))
                .append(", $kindOf(a.get(i)), null, null); }\n");
            body.append("        }\n");
            body.append("        return new ").append(ref).append("(data);\n");
            body.append("    }\n");
            body.append("    throw new DealError(\"E8001\", \"expected array\", oFile, oLine, oCol, descriptor, $kindOf(v), null, null);\n");
            body.append("}\n");
            for (String line : body.toString().split("\n", -1)) {
                if (line.isEmpty()) continue;
                refArrayCheckBranches.add("    " + line);
            }

            StringBuilder hostBody = new StringBuilder();
            hostBody.append("if (descriptor.equals(")
                .append(quoteJavaString("[" + elemDesc + "]"))
                .append(")) {\n");
            hostBody.append("    ").append(ref).append(" w = (")
                .append(ref).append(") a;\n");
            hostBody.append("    for (int i = 0; i < w.data.length; i++) {\n");
            hostBody.append("        try { $check(")
                .append(quoteJavaString(elemDesc))
                .append(", w.data[i], oFile, oLine, oCol); }\n");
            hostBody.append("        catch (DealError inner) { throw new DealError(\"E8003\", \"array element \" + (i + 1) + \" type mismatch\", oFile, oLine, oCol); }\n");
            hostBody.append("    }\n");
            hostBody.append("    return a;\n");
            hostBody.append("}\n");
            for (String line : hostBody.toString().split("\n", -1)) {
                if (line.isEmpty()) continue;
                hostRefArrayCheckBranches.add("    " + line);
            }
        }
    }
    /**
     * Emits the read/write helper pair for a nested array
     * ({@code int[][]} etc., any nested element shape): bounds checks
     * (E8002 negative / past-end append rule), the per-element wrapper
     * proof (E8003 on a wrong element shape, mirroring LuaJIT's
     * check_array element mismatch), and the in-place grow-on-append
     * storage inside the shared per-element-shape carrier.
     */
    private void emitNestedArrayHelpers(Type inner, String outerRef,
            String outerId) {
        String desc = "[" + typeDescriptor(inner) + "]";
        String innerRef = arrayWrapperName(inner);
        StringBuilder body = new StringBuilder();
        body.append("// nested array ").append(desc)
            .append(" element helpers (ISSUE-0102; ISSUE-0301 shared carrier)\n");
        body.append("static ").append(innerRef)
            .append(" __nestedRead$").append(outerId)
            .append("(").append(outerRef).append(" a, long i) { return __nestedRead$").append(outerId).append("(a, i, null, -1, -1); }\n");
        body.append("static ").append(innerRef)
            .append(" __nestedRead$").append(outerId)
            .append("(").append(outerRef).append(" a, long i, java.lang.String oFile, int oLine, int oCol) {\n");
        body.append("    if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol);\n");
        body.append("    if (i >= (long) a.data.length) throw new DealError(\"E8001\", \"expected ").append(desc).append(", got null\", oFile, oLine, oCol);\n");
        body.append("    return (").append(innerRef)
            .append(") a.data[(int) i];\n");
        body.append("}\n");
        body.append("static ").append(innerRef)
            .append(" __nestedWrite$").append(outerId)
            .append("(").append(outerRef).append(" a, long i, java.lang.Object v) { return __nestedWrite$").append(outerId).append("(a, i, v, null, -1, -1); }\n");
        body.append("static ").append(innerRef)
            .append(" __nestedWrite$").append(outerId)
            .append("(").append(outerRef).append(" a, long i, java.lang.Object v, java.lang.String oFile, int oLine, int oCol) {\n");
        body.append("    if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\", oFile, oLine, oCol);\n");
        body.append("    if (!(v instanceof ").append(innerRef)
            .append(")) throw new DealError(\"E8003\", \"array element type mismatch: expected ").append(desc).append("\", oFile, oLine, oCol);\n");
        body.append("    if (i == (long) a.data.length) { java.lang.Object[] nd = new java.lang.Object[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; }\n");
        body.append("    return (").append(innerRef).append(") v;\n");
        body.append("}\n");
        for (String line : body.toString().split("\n", -1)) {
            if (line.isEmpty()) continue;
            arrayHelpers.append("    ").append(line).append('\n');
        }
    }

    /**
     * Emits the read/write helper pair for a function array element
     * signature ({@code orNull} accepts the DEAL null element). Reads
     * and writes prove the wrapper signature; the descriptor spelling
     * matches the wrapper's runtime descriptor. The outer storage is
     * the shared per-element-shape {@code $DealRt.__A$...} carrier.
     */
    private void emitFnArrayHelpers(Type.Func f, String shapeId,
            boolean orNull, String outerRef, String outerId) {
        String desc = fnDescriptor(f);
        String shapeRef = "$DealRt." + shapeId;
        StringBuilder body = new StringBuilder();
        body.append("// function array ").append(desc)
            .append(" element helpers (ISSUE-0102)\n");
        String readName = orNull ? "__fnOrNullRead$" + shapeId
            : "__fnRead$" + shapeId;
        body.append("static ").append(shapeRef).append(' ').append(readName)
            .append("(").append(outerRef).append(" a, long i) { return ").append(readName).append("(a, i, null, -1, -1); }\n");
        body.append("static ").append(shapeRef).append(' ').append(readName)
            .append("(").append(outerRef).append(" a, long i, java.lang.String oFile, int oLine, int oCol) {\n");
        body.append("    if (i < 0L) throw new DealError(\"E8002\", \"negative array index\", oFile, oLine, oCol);\n");
        body.append("    if (i >= (long) a.data.length) throw new DealError(\"E8001\", \"expected ").append(desc).append(", got null\", oFile, oLine, oCol);\n");
        body.append("    java.lang.Object v = a.data[(int) i];\n");
        if (orNull) {
            body.append("    if (v == null) return null;\n");
        } else {
            body.append("    if (v == null) throw new DealError(\"E8001\", \"expected ").append(desc).append(", got null\", oFile, oLine, oCol);\n");
        }
        body.append("    if (v instanceof ").append(shapeRef)
            .append(" fv) return fv;\n");
        body.append("    throw new DealError(\"E8001\", \"expected ")
            .append(desc).append(", got \" + $describe(v), oFile, oLine, oCol);\n");
        body.append("}\n");
        String writeName = orNull ? "__fnOrNullWrite$" + shapeId
            : "__fnWrite$" + shapeId;
        body.append("static ").append(shapeRef).append(' ').append(writeName)
            .append("(").append(outerRef).append(" a, long i, java.lang.Object v) { return ").append(writeName).append("(a, i, v, null, -1, -1); }\n");
        body.append("static ").append(shapeRef).append(' ').append(writeName)
            .append("(").append(outerRef).append(" a, long i, java.lang.Object v, java.lang.String oFile, int oLine, int oCol) {\n");
        body.append("    if (i < 0L || i > (long) a.data.length) throw new DealError(\"E8002\", \"array index out of bounds\", oFile, oLine, oCol);\n");
        if (orNull) {
            body.append("    if (v != null && !(v instanceof ").append(shapeRef)
                .append(")) throw new DealError(\"E8001\", \"expected ")
                .append(desc).append(", got \" + $describe(v), oFile, oLine, oCol);\n");
        } else {
            body.append("    if (!(v instanceof ").append(shapeRef)
                .append(")) throw new DealError(\"E8001\", \"expected ")
                .append(desc).append(", got \" + $describe(v), oFile, oLine, oCol);\n");
        }
        body.append("    if (i == (long) a.data.length) { java.lang.Object[] nd = new java.lang.Object[a.data.length + 1]; java.lang.System.arraycopy(a.data, 0, nd, 0, a.data.length); nd[nd.length - 1] = v; a.data = nd; } else { a.data[(int) i] = v; }\n");
        body.append("    return (").append(shapeRef).append(") v;\n");
        body.append("}\n");
        for (String line : body.toString().split("\n", -1)) {
            if (line.isEmpty()) continue;
            arrayHelpers.append("    ").append(line).append('\n');
        }
    }

    /** Java return type for a function. {@code null} when unsupported. */
    private String javaReturnType(Type t, Span span) {
        if (t instanceof Type.Null) return "void";
        return javaLocalType(t, span);
    }

    private static String typeName(Type t) {
        if (t == null) return "<unknown>";
        return switch (t) {
            case Type.Null ignored -> "null";
            case Type.Boolean ignored -> "boolean";
            case Type.Int ignored -> "int";
            case Type.Number ignored -> "number";
            case Type.String ignored -> "string";
            case Type.Table ignored -> "table";
            case Type.Bytes ignored -> "bytes";
            case Type.Error ignored -> "error";
            case Type.Array a -> "array of " + typeName(a.element());
            case Type.Nullable n -> typeName(n.inner()) + " | null";
            case Type.Class c -> c.name();
            case Type.Func f -> "function";
        };
    }

    // =========================================================================
    // Literal rendering
    // =========================================================================

    /** True when e is exactly the null literal (no side effects, no evaluation). */
    private static boolean isBareNullLiteral(ExpressionNode e) {
        return e instanceof LiteralExpr lit
            && lit.value() instanceof LiteralValue.NullLiteral;
    }

    /**
     * Renders a double as a Java double literal. Non-finite values render
     * as the {@code Double} constants: the parser accepts e.g.
     * {@code 1e999} as Infinity ({@code Double.parseDouble} with no range
     * check) and the Lua backend emits {@code (1/0)} for it — Java has no
     * literal spelling for Infinity/NaN, so a bare {@code Infinity}
     * identifier would make javac reject an artifact the CLI reported as
     * successful.
     */
    private static String javaDoubleLiteral(double v) {
        if (Double.isNaN(v)) return "java.lang.Double.NaN";
        if (v == Double.POSITIVE_INFINITY) return "java.lang.Double.POSITIVE_INFINITY";
        if (v == Double.NEGATIVE_INFINITY) return "java.lang.Double.NEGATIVE_INFINITY";
        return Double.toString(v);
    }

    /** The emitted origin argument list (file, line, column) of one
     * authoritative raise-site node: the span start the sidecar pins for
     * the class (jvm-canonical-error-snapshot-convergence D3), emitted
     * as compile-time literals. The caller passes them unchanged into
     * the raising helper, which propagates them into every raise it
     * performs — no thread-local state, no stack-frame derivation. An
     * absent span emits the explicit absent sentinel (null, -1, -1),
     * and a span carrying no file falls back to this module's DEAL
     * source path — the lane normalizes the value to the corpus-relative
     * form through its deployment map. */
    private String originArgs(Span span) {
        if (span == null) {
            // The explicit absent sentinel: file null, line/column -1.
            return "(java.lang.String) null, -1, -1";
        }
        String file = span.file() != null ? span.file() : sourcePath;
        return quoteJavaString(file == null ? "" : file) + ", "
            + span.startLine() + ", " + span.startColumn();
    }

    /** Renders a DEAL string as a Java string literal (UTF-8 source). */
    private static String quoteJavaString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    // Surrogates (paired and unpaired alike) emit as
                    // Java unicode escapes: a raw unpaired UTF-16
                    // surrogate in the artifact source would be
                    // corrupted by the UTF-8 file encoding before
                    // javac ever read it, silently replacing the
                    // boundary-validation input — the escaped form
                    // carries the code unit faithfully so the runtime
                    // string boundary checks ($check E8001 / the
                    // __jsonQuote stringify scan) observe exactly the
                    // source value.
                    if (c < 0x20
                            || java.lang.Character.isSurrogate(c)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // =========================================================================
    // Emission helpers and diagnostics
    // =========================================================================

    private void emitLine() {
        out.append('\n');
    }

    /** Emits every hoisted pre-statement (in evaluation order) and clears
     * the buffer. Called at each statement boundary, after all expression
     * emission for that statement is complete, so hoisted side effects run
     * exactly where Java's left-to-right evaluation would run them. */
    private void flushPreStatements() {
        for (PreLine line : preStatements) {
            if (!line.text().isEmpty()) {
                out.append("    ".repeat(indent + line.extraIndent()));
            }
            out.append(line.text()).append('\n');
        }
        preStatements.clear();
        preStatementsDeclareTemps = false;
    }

    /** Flushes exactly the first {@code n} pending pre-statements,
     * keeping the rest (the transformed for form flushes the
     * initializer's hoisted side effects before the init assignment,
     * the condition's per iteration, and the update's per iteration).
     */
    private void flushNPreStatements(int n) {
        for (int i = 0; i < n; i++) {
            PreLine line = preStatements.get(i);
            if (!line.text().isEmpty()) {
                out.append("    ".repeat(indent + line.extraIndent()));
            }
            out.append(line.text()).append('\n');
        }
        preStatements.subList(0, n).clear();
        if (preStatements.isEmpty()) {
            preStatementsDeclareTemps = false;
        }
    }

    /** A fresh dummy-local name for a forced evaluation ({@code __ignored},
     * {@code __ignored1}, …). The {@code __} prefix is unreachable from
     * {@link #javaName} (underscores escape to {@code $u}), so it can never
     * collide with a translated user identifier. */
    private String nextIgnoredName() {
        String name = ignoredCounter == 0
            ? "__ignored" : "__ignored" + ignoredCounter;
        ignoredCounter++;
        return name;
    }

    private void emitLine(String s) {
        if (!s.isEmpty()) out.append("    ".repeat(indent));
        out.append(s).append('\n');
    }

    /** Records an E6000 backend diagnostic for an out-of-scope construct
     * at a real source span (D5). */
    private void unsupported(String what, Span span) {
        diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E6000,
            "JVM backend (skeleton) does not support " + what + " yet",
            span));
    }

    /** Records an E6000 backend diagnostic with the canonical synthetic
     * shape and a construct-naming anchor note (D6): the jsonable
     * conversion sites hold only the converted class or type, no source
     * span, so the anchor is synthetic with a note naming the construct. */
    private void unsupportedSynthetic(String what, String missingAnchorNote) {
        diagnostics.add(CompilerDiagnostic.syntheticError(DiagnosticCode.E6000,
            "JVM backend (skeleton) does not support " + what + " yet",
            modulePath, missingAnchorNote));
    }
}
