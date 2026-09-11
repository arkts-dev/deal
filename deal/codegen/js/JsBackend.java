package deal.codegen.js;

import deal.ast.ArrayType;
import deal.ast.AssignmentExpr;
import deal.ast.ArrayLiteralExpr;
import deal.ast.AwaitExpression;
import deal.ast.BinaryExpr;
import deal.ast.BinaryOp;
import deal.ast.Block;
import deal.ast.BreakStatement;
import deal.ast.CallExpr;
import deal.ast.ClassDeclaration;
import deal.ast.ClassField;
import deal.ast.ContinueStatement;
import deal.ast.DeleteStatement;
import deal.ast.Either;
import deal.ast.ExportDeclaration;
import deal.ast.ExpressionNode;
import deal.ast.ExpressionStatement;
import deal.ast.ForInit;
import deal.ast.ForOfStatement;
import deal.ast.ForStatement;
import deal.ast.FunctionDeclaration;
import deal.ast.FunctionExpr;
import deal.ast.FunctionType;
import deal.ast.FunctionTypeParam;
import deal.ast.HasExpr;
import deal.ast.IdentifierExpr;
import deal.ast.IfStatement;
import deal.ast.ImportDeclaration;
import deal.ast.IndexExpr;
import deal.ast.LiteralExpr;
import deal.ast.LiteralValue;
import deal.ast.MemberAccessExpr;
import deal.ast.NamedType;
import deal.ast.NullableType;
import deal.ast.ObjectLiteralExpr;
import deal.ast.Parameter;
import deal.ast.ProgramNode;
import deal.ast.Property;
import deal.ast.QualifiedType;
import deal.ast.ReturnStatement;
import deal.ast.Span;
import deal.ast.StatementNode;
import deal.ast.TemplateLiteralExpr;
import deal.ast.ThrowStatement;
import deal.ast.TryStatement;
import deal.ast.TypeNode;
import deal.ast.UnaryExpr;
import deal.ast.UnaryOp;
import deal.ast.VariableDeclaration;
import deal.ast.WhileStatement;
import deal.checker.CheckResult;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.codegen.SourceMapGenerator;
import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalClassIdentityIndex;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.module.CompilerClassDefaultEntry;
import deal.module.CompilerClassDefaultPlan;
import deal.module.PlannedDefaultClass;
import deal.module.RuntimeClassDefaultPlan;
import deal.module.RuntimeDefaultEvaluator;
import deal.module.RuntimeDefaultPlanLowering;
import deal.module.ModuleIdentityResolver;
import deal.module.StdlibModuleResolver;
import deal.semantic.ir.SemanticProfile;
import deal.types.Type;
import deal.types.Types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.function.Function;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * JavaScript backend: the typed-AST-walking CommonJS emitter
 * (js-backend-architecture D2-D8, js-backend-emitter).
 *
 * <p>ISSUE-0247 core slice: the {@link #generate} seam with the
 * {@link JsCodegenResult} shape, the canonical module skeleton (shape
 * steps 1-9, with steps 5-8 structurally empty — later slices populate
 * the import bindings, the predeclared {@code let}s, the declarations,
 * and the export assignments), the {@link #jsName} binding-position
 * translation and the canonical {@link CanonicalRuntimeTypeDescriptor}
 * descriptor foundations, the
 * entry shim with the location-embedding catch body, and the E6004 entry
 * backstop.
 *
 * <p>ISSUE-0248 data slice: the full expression/statement lowering —
 * literals (template literals lower to string concatenation), variables
 * with the checker's inferred types and {@code jsName}-translated
 * binding positions, checked int arithmetic via the {@code $rt} int
 * members (E8001/E8004/E8005/E8006 in the runtime) and
 * {@code number % number} through {@code $rt.numMod}, comparisons with
 * {@code $rt.strCompare} for string ordering and native
 * {@code ===}/{@code !==}, {@code &&}/{@code ||}, if/else/while/C-style
 * for/break/continue, array and string for-of (fresh per-iteration
 * {@code let} bindings; array for-of is index-based and stops before the
 * first {@code $rt.undefined}), arrays (literals, guarded
 * reads/writes/appends, {@code .length}, element delete with the
 * E8002-bounded {@code $rt.undefined} write and no write at
 * {@code i === length}), {@code T | null} boundaries via
 * {@code $rt.checkNullable}, tables (the coordinated {@code $rt.makeTable}
 * runtime member — js-backend-emitter D9 — plus literals via
 * computed-key entry objects and contextual reads/writes/delete via
 * {@code .get}/{@code .set}/{@code .delete}), class declarations (the
 * predeclared artifact {@code let}s, the construction closure with the
 * per-construction defaults thunk, the inline META pair) and contextual
 * class-typed literals (the builtin {@code Error} through the header
 * {@code Error$new} pair), field reads/writes/{@code has()}/delete with
 * the optional three-state ({@code $rt.optRead}/{@code $rt.has}/
 * {@code $rt.MISSING}), the two throw forms ({@code $rt.errorValue} with
 * per-property {@code ""} default filling; the non-literal
 * {@code throw <expr>;} rethrow), try/catch via {@code $rt.reifyError},
 * {@code int()}/{@code number()} conversion calls through the header
 * wrapper values, and Unicode scalar-value strings. Every emitted check
 * and {@code .$f} call carries the literal {@code .deal} file/line/column
 * arguments from the AST span.
 *
 * <p>ISSUE-0249 functions-and-closures slice: function declarations
 * and expressions emit as {@code $rt.function} wrappers with the exact
 * canonical descriptor signature, the entry parameter checks in
 * parameter order with the forwarded {@code $file}/{@code $line}/
 * {@code $column} span (parameter errors report the call site), the
 * return-site exit checks on every {@code return} path plus the
 * validated {@code null} fall-off return for null-typed functions, the
 * trailing {@code $}-prefixed span parameters (duplicate-free for user
 * parameters literally named {@code file}/{@code line}/{@code column}),
 * direct/nested/indirect/member calls through {@code .$f} with the
 * literal call-site span arguments, recursion/forward calls/mutual
 * recursion through the predeclare-then-assign pattern (module shape
 * step 6 plus per-scope {@code let} hoisting for nested functions —
 * collision-free in the emitted JS scope: one {@code let} per distinct
 * name, body/catch statements in the checker's nested block scopes so
 * hoisted {@code let}s legally shadow parameter and catch bindings, and
 * a same-list var-before-function pair sharing the hoisted binding
 * through a plain assignment; {@code require}/{@code module}/
 * {@code exports} translate in binding positions so the module-scope
 * capture stays immune per js-backend-architecture D2),
 * native closure capture, function values crossing function-typed
 * boundaries through the runtime's exact-{@code $sig} E8010 check, and
 * arity-extension adapters (a wider function-typed declaration or
 * assignment context wraps the value in an adapter closure that checks
 * the extended parameters, drops the extras, and calls the inner
 * {@code .$f} with the forwarded span — the {@code deal/runtime.lua}
 * adapter precedent). The header {@code int}/{@code number} wrapper
 * values are first-class function values with no special casing.
 *
 * <p>ISSUE-0250 modules slice: import bindings in import order (shape
 * step 5) — spec-stdlib raw paths ({@code std/<name>} in the
 * {@code StdlibModuleResolver} spec list) emit
 * {@code <relpath>/std/<name>} and project modules emit the relative
 * specifier between the emitting module's artifact directory and the
 * imported module's artifact directory (sibling {@code ./lib}, nested
 * {@code ../sub/util}), the extension omitted; export assignments in
 * declaration order (shape step 8) via the own-property-safe
 * {@code $rt.setProp} with raw keys — the visible class META export
 * plus the hidden compiler-generated {@code <C>$new} export for every
 * exported class (imported construction runs the declaring module's
 * closure, so defaults evaluate in the declaring module's scope);
 * imported class-typed literals construct through
 * {@code <alias>.<C>$new}; imported optional field reads map
 * {@code MISSING} through {@code $rt.optRead}; module-member
 * writes/deletes (checker-accepted mutations of the export table,
 * the LuaJIT plain-assignment/nil-out semantics) route through
 * {@code $rt.setProp} instead of the Map method calls; and the entry
 * shim's {@code $exports.main.$f()} reaches the exported {@code main}.
 *
 * <p>ISSUE-0251 async/await slice: the full D5 lowering — async
 * function declarations and expressions emit as native
 * {@code async function} bodies inside the {@code $rt.function}
 * wrapper with the exact {@code async(...)} descriptor signature
 * (the {@code async} prefix from the canonical descriptor, the T3
 * wrapper mechanics intact), {@code await E} lowers to
 * {@code await <callee>.$f(<args>, <file>, <line>, <column>)} with
 * the literal call-site span arguments (the direct and the indirect
 * function-typed forms; {@link #emitAwait}), the declared-return
 * check runs inside the async body on every return path — the
 * fall-off path of a {@code null}-returning async wrapper included,
 * where the trailing validated {@code null} return closes the body
 * before the Promise resolves — no completion check is added at any
 * await site, errors raised by an awaited operation propagate
 * natively at the await site (Promise rejection), async function
 * values cross function-typed boundaries with the exact
 * {@code async(...)} sig (E8010 on any mismatch, the runtime's
 * function-branch exact compare), and an async arity-extension
 * adapter returns the inner operation untouched — the inner async
 * body's completion check is the boundary crossing, exactly once.
 * Sync wrappers and the entry gate (non-async {@code main(): null},
 * E2010/E2011) are unchanged.
 *
 * <p>ISSUE-0252 rejection pass: the D8 rejection table with exact
 * error-severity diagnostics at their documented sites — an import of
 * an extern-C declaration module is E6003 containing
 * {@code FFI_UNSUPPORTED_BACKEND} at the import statement, keyed on
 * the orchestrator-built extern-C import set
 * (fixed-name-directive-events D9;
 * {@link #rejectUnsupportedImport}, live at this merge,
 * never a dead placeholder, and preceding any host handling);
 * a non-stdlib declaration-file import (a {@code hostModules} entry)
 * emits the {@code $rt.loadHost} binding with the emitter-rendered
 * declared map (ISSUE-0328, js-v12-host-abi-completion D1 — the
 * retired host-ABI E6000 arm);
 * {@code @jsonable} on an exported class emits the two exported
 * wrappers plus the hidden {@code C$fields} descriptor export
 * (js-v12-jsonable-completion D1-D2); and
 * the retired defensive {@code bytes} E6000 arms
 * (js-backend-architecture A1) are gone: the v1.2 bytes lane
 * (js-v12-int32-bytes D3/D4) lowers {@code bytes(n)} to
 * {@code $rt.bytes}, {@code b.length} to {@code $rt.bytesLength},
 * {@code b[i]} to {@code $rt.bytesGet}, {@code b[i] = v} to
 * {@code $rt.bytesSet}, and every bytes-typed boundary to
 * {@code $rt.checkBytes} — no E6000 is reported for any bytes
 * program. Every rejection makes {@code hasErrors()} hold, so the
 * orchestrator's two-pass {@code codegenAllJs()} writes no artifact
 * for the rejected module — a clean sibling's artifact is unaffected —
 * and the compilation fails with the standard diagnostic report.
 *
 * <p>ISSUE-0328 host-ABI slice (js-v12-host-abi-completion D1/D4):
 * the host-ABI E6000 arm retires — a non-stdlib declaration-file
 * import emits {@code const &lt;alias&gt; = $rt.loadHost($require("<relpath>/<raw
 * specifier>"), <declared map>);} with the raw specifier verbatim,
 * prefixed by the same relative-path rule every project import uses
 * (the host file lives at {@code <outputRoot>/<raw specifier>.js}),
 * and the declared map is emitter-rendered from the orchestrator's
 * host declaration records (canonical function descriptors; canonical
 * {@code @$external/<specifier>/<ClassName>} identities plus the
 * declared field-descriptor array for class exports). Host-call
 * argument positions emit the raw expression value — a typed table
 * read materializing a host-call argument never pre-raises its own
 * E8001; the runtime host wrapper's boundary raises E8010 (D4
 * read-site deferral). Let/assignment/return-boundary typed reads
 * keep their pinned read-site checks. The E6003 {@code @extern-c} arm
 * precedes any host handling.
 *
 * <p>ISSUE-0318 nested-class slice: the former D6 nested-class E6000
 * arm (below-module-level {@link ClassDeclaration}) is retired — a
 * nested class declaration emits the same scope-local
 * {@code C$new}/{@code C$meta} artifact pair as a module-level class,
 * predeclared at the top of the enclosing block and assigned at the
 * declaration site (js-v12-completion-architecture D4).
 *
 * <p>js-v12-source-maps: the optional {@link SourceMapGenerator}
 * parameter on {@link #generate} enables mapping recording at every
 * generated statement-group boundary (D2) — the wrapper, check, and
 * entry-shim emission sites included — and the orchestrator's pass 2
 * serializes the per-module {@code .deal.map.json} sidecars from the
 * returned recorder (D1), retiring the explicit {@code --source-map}
 * warning.
 *
 * <p>The backend consumes the checked AST exactly like
 * {@code JvmBackend} ({@code CheckResult.typeMap()}/{@code symbolTable()})
 * and never re-checks frontend decisions; it never writes files — the
 * orchestrator's two-pass {@code codegenAllJs()} owns the artifact writes
 * and the runtime/stdlib deployment copies (js-backend-emitter D2/D3).
 * Emission is deterministic and synchronous, with per-generate state
 * only.
 */
public final class JsBackend {

    /**
     * Result of JavaScript code generation: the dotted module path (the
     * artifact path is {@code modulePath with '/' for '.' + ".js"}), the
     * generated CommonJS source, any backend diagnostics, and the
     * optional source-map recorder ({@code null} when mapping recording
     * was not requested — the caller passes a {@link SourceMapGenerator}
     * to {@link #generate} and serializes the sidecar afterwards).
     * {@link #hasErrors()} gates the artifact (the
     * {@code JvmCodegenResult} shape,
     * deal/codegen/jvm/JvmBackend.java:605-618, with {@code modulePath} in
     * place of {@code className}).
     */
    public record JsCodegenResult(String modulePath, String source,
                                  List<CompilerDiagnostic> diagnostics,
                                  SourceMapGenerator sourceMap,
                                  List<RuntimeClassDefaultPlan> runtimePlans) {

        /** Backward-compatible four-component constructor: the runtime
         * plan realization list is empty (no published plan consumed —
         * the standalone entry points). */
        public JsCodegenResult(String modulePath, String source,
                               List<CompilerDiagnostic> diagnostics,
                               SourceMapGenerator sourceMap) {
            this(modulePath, source, diagnostics, sourceMap, List.of());
        }

        public JsCodegenResult {
            java.util.Objects.requireNonNull(modulePath, "modulePath must not be null");
            java.util.Objects.requireNonNull(source, "source must not be null");
            diagnostics = List.copyOf(diagnostics);
            runtimePlans = List.copyOf(runtimePlans);
        }

        /** True when at least one error-level diagnostic was recorded. */
        public boolean hasErrors() {
            return diagnostics.stream()
                .anyMatch(d -> "error".equals(d.severity()));
        }
    }

    // =========================================================================
    // Name translation and type descriptors
    // =========================================================================

    /**
     * The ECMAScript reserved words that are not DEAL keywords, the
     * strict-mode binding-restricted names {@code eval} and
     * {@code arguments}, and the CommonJS wrapper parameters
     * {@code require}/{@code module}/{@code exports}
     * (js-backend-architecture D2/D4):
     * {@link #jsName} appends {@code $} exactly for these spellings in
     * binding positions. Every JS reserved word that is also a DEAL
     * keyword ({@code let class function async await return if else while
     * for break continue null true false import export delete try catch
     * throw}) is unreachable as an identifier; {@code from}/{@code has}/
     * {@code of} are DEAL keywords but not JS reserved words.
     */
    private static final Set<String> JS_RESERVED_BINDINGS = Set.of(
        "case", "const", "debugger", "default", "do", "enum", "extends",
        "finally", "in", "instanceof", "new", "super", "switch", "this",
        "typeof", "var", "void", "with", "yield", "implements",
        "interface", "package", "private", "protected", "public",
        "static", "eval", "arguments",
        "require", "module", "exports");

    /**
     * Translates a user identifier for a JS binding position: appends
     * {@code $} exactly when the identifier cannot appear as a strict-mode
     * binding name (the fixed word list above); every other identifier
     * passes through unchanged. Binding positions are {@code let}/
     * {@code const} locals, function parameters, function names, and
     * catch parameters; property/export/table keys keep the raw name
     * (js-backend-architecture D4). The three CommonJS wrapper
     * parameters {@code require}/{@code module}/{@code exports} join the
     * fixed set (js-backend-architecture D2 capture immunity): a
     * checker-accepted module-level function named any of the three must
     * never bind in the module scope — a predeclared
     * {@code let require;} there would place the Node-injected wrapper
     * parameter in the temporal dead zone, so the step-2 capture line
     * {@code const $require = require;} would throw at module load
     * instead of reading the wrapper parameter. Translating the binding
     * (and every reference, which {@link #emitIdentifier} shares through
     * {@code jsName}) keeps the capture immune while
     * property/export/table keys stay raw. Package-visible so a
     * same-package harness can assert the pins directly.
     */
    static String jsName(String identifier) {
        return JS_RESERVED_BINDINGS.contains(identifier)
            ? identifier + "$" : identifier;
    }

    // =========================================================================
    // State
    // =========================================================================

    // The checker state the seam consumes (CheckResult.typeMap()/
    // symbolTable()): the type of every expression, the module-level
    // symbol table, the source/module paths, and the import
    // classification maps supplied by the orchestrator.
    private final Map<ExpressionNode, Type> typeMap;
    private final SymbolTable symbols;
    private final String sourcePath;
    private final String modulePath;
    /**
     * The per-compilation canonical descriptor service
     * ({@link CanonicalRuntimeTypeDescriptor#encode(Type)}) — the one
     * {@code Type}&rarr;text producer this emitter consumes, constructed
     * over the compilation's identity index and module-path
     * classification (js-v12-completion-architecture D3).  No legacy
     * descriptor spelling is ever emitted.
     */
    private final CanonicalRuntimeTypeDescriptor descriptors;

    // v1.2 identity carriage (descriptor-identity-propagation D1): the
    // identity index supplies class descriptor text via
    // index.descriptorTextFor(identity) and the module-path
    // classification (the module-identity layer's own surface) builds
    // local class identities.
    private final CanonicalClassIdentityIndex identityIndex;
    private final Function<String, CanonicalModuleIdentity> moduleIdentities;
    // Import classification (raw import path → imported module path /
    // declared export map): consumed by the import-binding and rejection
    // slices (T4/T6); retained here for the module shape.
    private final Map<String, String> importResolutions;
    private final Map<String, HostModuleDeclarations> hostModules;
    /**
     * Raw import paths whose resolved module is an extern-C declaration
     * file (fixed-name-directive-events D9): the re-keyed E6003
     * {@code FFI_UNSUPPORTED_BACKEND} arm keys on this set.
     */
    private final Set<String> externCImports;
    private final boolean isEntry;
    /**
     * The backend-wide int mode derived from the invocation's
     * project-wide semantic profile (ISSUE-0374 profile plumb,
     * js-v12-int32-bytes D2): true exactly when the profile passed to
     * {@link #generate} was {@link SemanticProfile#DEAL_V1_2_INT32}.
     * One derivation per backend instance — no static/global flag, no
     * system property, and no source, CLI, or environment selection
     * surface exists. Under the int32 mode every emitted module calls
     * {@code $rt.setInt32Mode(true)} immediately after the runtime
     * {@code $require} (module shape step 3); under
     * {@code LEGACY_SAFE_INT} the selector is absent and the retained
     * ±(2^53-1) emission stays byte-identical to the legacy artifacts.
     */
    private final boolean int32Mode;

    /** Shape step 8: the export assignments collected during the
     * declaration walk in declaration order, emitted by
     * {@link #emitExportsSection} after the walk. */
    private final List<String> exportAssignments = new ArrayList<>();
    /** Backend diagnostics; all error-severity by construction (the
     * T12-native ranged {@link CompilerDiagnostic} list — the backend
     * emits ranged entries directly, so the orchestrator merge needs no
     * boundary conversion). */
    private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();
    /** The generated CommonJS source accumulator. */
    private StringBuilder out = new StringBuilder();
    /** Statement-level emission indent (two spaces per level). */
    private int indent = 0;
    /** The declared return type of the innermost function body being
     * emitted; {@code return} statements check their expression against
     * it. */
    private Type currentReturnType = null;
    /** Optional source-map recording (js-v12-source-maps D2): null
     * disables mapping collection (the
     * {@code LuaBackend.sourceMapGenerator} precedent); a non-null
     * generator receives one {@link SourceMapGenerator#emitStatement}
     * per generated statement-group boundary from
     * {@link #recordMapping}. */
    private final SourceMapGenerator sourceMapGenerator;
    /** While {@link #captureOutput} is active, the 1-based artifact
     * line on which the captured buffer's first line will land (the
     * captured prefix ends with a newline, so captured line k lands on
     * {@code captureLineOffset + k}); 0 when not capturing. */
    private int captureLineOffset = 0;
    /** The number of newlines already present in expression text that
     * is assembled in memory but not yet appended to the current
     * {@link #out} buffer (js-v12-source-maps D2 exactness): a
     * statement's expression text is built in full before {@link #line}
     * splices it into the buffer, so a second function expression on
     * the same artifact line must rebase its wrapper and captured-body
     * mappings past the first expression's assembled body lines.
     * Scoped per emission level: {@link #captureOutput} saves and
     * resets the enclosing value, and {@link #line} clears the current
     * level's count once the assembled text lands in the buffer. */
    private int inFlightNewlines = 0;

    /**
     * True while the walk sits at module level; false inside a
     * function-body walk. Nested class declarations no longer reject
     * below module level (ISSUE-0318 retired the arm): the flag now
     * only distinguishes module-level type resolution (the hoisted
     * symbol table) from AST-derived nested types
     * ({@link #visit(FunctionDeclaration)}).
     */
    private boolean atModuleLevel = true;


    /**
     * Stack of user-declared local names visible at the current walk
     * position (function parameters, {@code let} declarations, loop and
     * catch bindings), mirroring the checker's lexical scoping
     * ({@code NameResolver.walkFunctionExpr/walkBlock/walkFor/walkForOf/
     * walkTry}: a declaration defines its name before the initializer
     * walk, and every block/loop body opens its own scope).
     * {@link #emitIdentifier} consults it to decide whether a reference
     * is a shadowing local variable or a module-level class-symbol
     * reference that must lower to the class META artifact.
     */
    private final List<Set<String>> localScopes = new ArrayList<>();

    /**
     * Parallel stack of nested class names visible at the current walk
     * position (one frame per {@link #localScopes} frame): a class
     * declaration hoisted in a statement list registers its name here
     * (js-v12-completion-architecture D4), so a class-META reference
     * inside the declaring scope lowers to the scope-local
     * {@code <C>$meta} artifact even though the nested ClassSymbol is
     * invisible to the module-level symbol table. The frame-index
     * comparison against {@link #localScopes} mirrors the checker's
     * lexical resolution: the innermost binding wins.
     */
    private final List<Set<String>> localClassScopes = new ArrayList<>();

    /**
     * Statement-list nesting depth: 0 while the module statement list
     * walks (generateProgram), +1 per open {@link #walkStatements}
     * (blocks, branch/loop/try bodies, function bodies). A nested
     * exported class writes its export assignments inline at the
     * declaration site — its scope-local artifacts are not visible at
     * the module-end export section (js-v12-completion-architecture
     * D4).
     */
    private int statementDepth = 0;

    /**
     * Stack of hoisted function-name sets for the statement lists
     * currently being walked (one frame per open
     * {@link #walkStatements} call, innermost last): a variable
     * declaration whose name was hoisted in the same statement list
     * emits a plain assignment into the predeclared binding instead of
     * a second {@code let} (strict-mode redeclaration would be a
     * SyntaxError) — the checker accepts the var-before-function pair
     * in one scope (its walkFuncDecl skips the second define, one
     * symbol), and the Lua reference emits the same local-then-assign
     * shape.
     */
    private final Deque<Set<String>> hoistedFunctionNames =
        new ArrayDeque<>();

    // ISSUE-0544 (the lowering epic): module path → the module's
    // completed PlannedDefaultClass list (the graph-published plans),
    // passed by the orchestrator. The emitter consumes the published
    // CompilerClassDefaultPlan of every class declaration for the
    // per-construction defaults-thunk projection (entry order,
    // canonical descriptors, optional flags, evaluators only on
    // required-present entries) plus the labelled per-entry evaluator
    // expressions. Empty on the standalone entry points (the
    // synthesized fallback path stays).
    private Map<String, List<PlannedDefaultClass>> plansByModulePath =
        Map.of();

    // ISSUE-0544: the realized RuntimeClassDefaultPlans of this module,
    // in class source order — the carrier-side realization data (the
    // evaluator invocation seams execute inside the generated artifact,
    // never in-process; see artifactInvocationSeam). Attached to the
    // JsCodegenResult for the compiler-to-lowerer verification battery
    // and the later FFI identity consumption.
    private final List<RuntimeClassDefaultPlan> runtimePlans =
        new ArrayList<>();

    private JsBackend(Map<ExpressionNode, Type> typeMap, SymbolTable symbols,
                      String sourcePath, String modulePath,
                      Map<String, String> importResolutions,
                      Map<String, HostModuleDeclarations> hostModules,
                      Set<String> externCImports,
                      boolean isEntry,
                      CanonicalClassIdentityIndex identityIndex,
                      Function<String, CanonicalModuleIdentity> moduleIdentities,
                      SourceMapGenerator sourceMapGenerator,
                      SemanticProfile semanticProfile) {
        this.typeMap = typeMap;
        this.symbols = symbols;
        this.sourcePath = sourcePath;
        this.modulePath = modulePath;
        this.importResolutions = importResolutions;
        this.hostModules = hostModules;
        this.externCImports = externCImports;
        this.isEntry = isEntry;
        this.identityIndex = identityIndex;
        this.moduleIdentities = moduleIdentities;
        this.descriptors = new CanonicalRuntimeTypeDescriptor(identityIndex);
        this.sourceMapGenerator = sourceMapGenerator;
        this.int32Mode = semanticProfile == SemanticProfile.DEAL_V1_2_INT32;
        localScopes.add(new HashSet<>());
    }

    // =========================================================================
    // Seam
    // =========================================================================

    /**
     * Generates one CommonJS artifact for a checked DEAL module. Never
     * writes files: the returned {@link JsCodegenResult} carries the
     * source and any backend diagnostics, and the orchestrator's pass 2
     * writes artifacts only for clean modules (js-backend-emitter D2/D3).
     *
     * @param program           the checked module AST
     * @param result            the checker's type map and symbol table
     * @param sourcePath        the emitting module's source file
     * @param modulePath        the dotted module path ({@code main},
     *                          {@code app.main}); the artifact path is
     *                          {@code modulePath.replace('.', '/') + ".js"}
     * @param importResolutions raw import path → imported module path
     * @param hostModules       raw import path → declared export map
     *                          (non-spec-stdlib declaration imports)
     * @param isEntry           true when this module is the selected
     *                          entry module (emits the entry shim, D7)
     */
    public static JsCodegenResult generate(ProgramNode program, CheckResult result,
                                           String sourcePath, String modulePath,
                                           Map<String, String> importResolutions,
                                           Map<String, HostModuleDeclarations> hostModules,
                                           boolean isEntry) {
        // Recorder-less convenience overload (js-v12-source-maps D2):
        // mapping recording is disabled and the profile defaults to
        // LEGACY_SAFE_INT (no selector emission); the standalone
        // identity surface is built by the source-map variant below.
        return generate(program, result, sourcePath, modulePath,
            importResolutions, hostModules, isEntry,
            SemanticProfile.LEGACY_SAFE_INT);
    }

    /**
     * Profile-plumbed standalone overload (ISSUE-0374 profile plumb,
     * js-v12-int32-bytes D2): the same recorder-less standalone surface
     * as {@link #generate(ProgramNode, CheckResult, String, String, Map,
     * Map, boolean)} with an explicit project-wide semantic profile —
     * {@link SemanticProfile#DEAL_V1_2_INT32} derives the int32 mode and
     * every emitted module calls {@code $rt.setInt32Mode(true)}
     * immediately after the runtime {@code $require};
     * {@link SemanticProfile#LEGACY_SAFE_INT} emits no selector and is
     * byte-identical to the legacy artifacts. The profile is derived
     * from the release-owned compiler invocation in the real pipeline;
     * no source, CLI, or environment surface selects it.
     *
     * @param semanticProfile   the project-wide semantic profile
     */
    public static JsCodegenResult generate(ProgramNode program, CheckResult result,
                                           String sourcePath, String modulePath,
                                           Map<String, String> importResolutions,
                                           Map<String, HostModuleDeclarations> hostModules,
                                           boolean isEntry,
                                           SemanticProfile semanticProfile) {
        return generate(program, result, sourcePath, modulePath,
            importResolutions, hostModules, isEntry, null, semanticProfile);
    }

    /**
     * Standalone-surface variant (js-v12-source-maps D2): no
     * compilation-wide identity index is supplied, so the module path
     * itself is the configured root text with no relative components
     * (the single-module adapter convention — e.g. module path "Main"
     * projects "@Main/<Name>") plus the intrinsic builtin Error
     * classification.  The real pipeline calls the production seam
     * below with the orchestrator-built per-compilation surface.
     * {@code sourceMap} may be {@code null} to disable mapping
     * recording, otherwise the emitter records one mapping at each
     * generated statement-group boundary — the current output
     * line/column at the emission site mapped to the AST statement's
     * {@link Span} start (the Lua
     * {@code LuaBackend.generateResult} optional-generator precedent) —
     * and the returned result carries the recorder so the caller
     * serializes the sidecar after codegen. The profile defaults to
     * {@link SemanticProfile#LEGACY_SAFE_INT} (no selector emission).
     *
     * @param sourceMap         the mapping recorder (or {@code null})
     */
    public static JsCodegenResult generate(ProgramNode program, CheckResult result,
                                           String sourcePath, String modulePath,
                                           Map<String, String> importResolutions,
                                           Map<String, HostModuleDeclarations> hostModules,
                                           boolean isEntry,
                                           SourceMapGenerator sourceMap) {
        return generate(program, result, sourcePath, modulePath,
            importResolutions, hostModules, isEntry, sourceMap,
            SemanticProfile.LEGACY_SAFE_INT);
    }

    /**
     * Profile-plumbed standalone variant (ISSUE-0374 profile plumb,
     * js-v12-int32-bytes D2): the {@code sourceMap} standalone surface
     * above with an explicit project-wide semantic profile — the same
     * standalone identity-index construction (the single-module adapter
     * convention), then the production seam with the derived int mode.
     *
     * @param sourceMap         the mapping recorder (or {@code null})
     * @param semanticProfile   the project-wide semantic profile
     */
    public static JsCodegenResult generate(ProgramNode program, CheckResult result,
                                           String sourcePath, String modulePath,
                                           Map<String, String> importResolutions,
                                           Map<String, HostModuleDeclarations> hostModules,
                                           boolean isEntry,
                                           SourceMapGenerator sourceMap,
                                           SemanticProfile semanticProfile) {
        Map<String, CanonicalModuleIdentity> byPath = new LinkedHashMap<>();
        byPath.put("", CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        String effectiveModulePath = modulePath == null ? "" : modulePath;
        if (!effectiveModulePath.isEmpty()) {
            byPath.put(effectiveModulePath,
                new CanonicalModuleIdentity.ProjectModule(
                    new ProjectModuleIdentity(effectiveModulePath,
                        effectiveModulePath, List.of())));
        }
        ModuleIdentityResolver.IdentityIndex standalone =
            ModuleIdentityResolver.buildIndex(byPath);
        return generate(program, result, sourcePath, modulePath,
            importResolutions, hostModules, Set.<String>of(), isEntry,
            standalone, standalone.moduleIdentityLookup(), sourceMap,
            semanticProfile);
    }

    /**
     * The production seam: generates one CommonJS artifact for a checked
     * DEAL module over the compilation's canonical identity surface —
     * the per-compilation identity index and the module-path
     * classification (js-v12-completion-architecture D3).  Every
     * {@code Type}&rarr;text production site consumes
     * {@link CanonicalRuntimeTypeDescriptor#encode(Type)} built from
     * that surface; no legacy descriptor spelling is emitted.  The
     * {@code sourceMap} recorder attaches optional mapping recording
     * (js-v12-source-maps D2); {@code null} disables it.
     *
     * <p>ISSUE-0374 profile plumb (js-v12-int32-bytes D2): the
     * {@code semanticProfile} — the orchestrator passes
     * {@code invocation.semanticProfile()} — derives the backend-wide
     * int mode exactly once per backend instance:
     * {@link SemanticProfile#DEAL_V1_2_INT32} makes every emitted
     * module call {@code $rt.setInt32Mode(true)} immediately after the
     * runtime {@code $require}; {@link SemanticProfile#LEGACY_SAFE_INT}
     * emits no selector (the retained ±(2^53-1) emission). All modules
     * in one output root share one profile (F1), so one runtime
     * instance and one flag are sound (the runtime is deployed once per
     * output root).
     *
     * @param identityIndex    the compilation's canonical class-identity
     *                         index (the orchestrator-built
     *                         {@code ModuleIdentityResolver.IdentityIndex})
     * @param moduleIdentities the compilation's dotted-module-path &rarr;
     *                         module-identity classification
     * @param externCImports   raw import paths whose resolved module is
     *                         an extern-C declaration file
     *                         (fixed-name-directive-events D9) — the
     *                         re-keyed E6003 arm keys on this set
     * @param sourceMap        the mapping recorder (or {@code null})
     * @param semanticProfile  the project-wide semantic profile
     */
    public static JsCodegenResult generate(ProgramNode program, CheckResult result,
                                           String sourcePath, String modulePath,
                                           Map<String, String> importResolutions,
                                           Map<String, HostModuleDeclarations> hostModules,
                                           Set<String> externCImports,
                                           boolean isEntry,
                                           CanonicalClassIdentityIndex identityIndex,
                                           Function<String, CanonicalModuleIdentity> moduleIdentities,
                                           SourceMapGenerator sourceMap,
                                           SemanticProfile semanticProfile) {
        java.util.Objects.requireNonNull(program, "program must not be null");
        java.util.Objects.requireNonNull(result, "result must not be null");
        java.util.Objects.requireNonNull(importResolutions,
            "importResolutions must not be null");
        java.util.Objects.requireNonNull(hostModules,
            "hostModules must not be null");
        java.util.Objects.requireNonNull(externCImports,
            "externCImports must not be null");
        java.util.Objects.requireNonNull(identityIndex,
            "identityIndex must not be null");
        java.util.Objects.requireNonNull(moduleIdentities,
            "moduleIdentities must not be null");
        return generate(program, result, sourcePath, modulePath,
            importResolutions, hostModules, externCImports, isEntry,
            identityIndex, moduleIdentities, sourceMap, semanticProfile,
            Map.of());
    }

    /**
     * Plan-carrying production seam (ISSUE-0544): the same contract as
     * the overload above with the compilation's completed default plans
     * keyed by module path
     * ({@code CompilationOrchestrator#completedPlansByModulePath}) —
     * the published-plan consumption surface of the lowering epic. The
     * emitter consumes this module's plans for the per-construction
     * defaults-thunk projection (js-backend-runtime D5: the thunk is
     * created at load, never invoked there, and {@code $rt.makeClass}/
     * {@code $jsonFromDocument} invoke it once per construction) plus
     * the labelled per-entry evaluator expressions. The standalone
     * entry points pass {@code Map.of()} and keep the synthesized
     * fallback emission.
     */
    public static JsCodegenResult generate(ProgramNode program, CheckResult result,
                                           String sourcePath, String modulePath,
                                           Map<String, String> importResolutions,
                                           Map<String, HostModuleDeclarations> hostModules,
                                           Set<String> externCImports,
                                           boolean isEntry,
                                           CanonicalClassIdentityIndex identityIndex,
                                           Function<String, CanonicalModuleIdentity> moduleIdentities,
                                           SourceMapGenerator sourceMap,
                                           SemanticProfile semanticProfile,
                                           Map<String, List<PlannedDefaultClass>>
                                               plansByModulePath) {
        java.util.Objects.requireNonNull(program, "program must not be null");
        java.util.Objects.requireNonNull(result, "result must not be null");
        java.util.Objects.requireNonNull(importResolutions,
            "importResolutions must not be null");
        java.util.Objects.requireNonNull(hostModules,
            "hostModules must not be null");
        java.util.Objects.requireNonNull(externCImports,
            "externCImports must not be null");
        java.util.Objects.requireNonNull(identityIndex,
            "identityIndex must not be null");
        java.util.Objects.requireNonNull(moduleIdentities,
            "moduleIdentities must not be null");
        java.util.Objects.requireNonNull(semanticProfile,
            "semanticProfile must not be null");
        java.util.Objects.requireNonNull(plansByModulePath,
            "plansByModulePath must not be null");
        JsBackend backend = new JsBackend(result.typeMap(), result.symbolTable(),
            sourcePath, modulePath, importResolutions, hostModules,
            externCImports, isEntry,
            identityIndex, moduleIdentities, sourceMap, semanticProfile);
        backend.plansByModulePath = Map.copyOf(plansByModulePath);
        return backend.generateProgram(program);
    }

    private JsCodegenResult generateProgram(ProgramNode program) {
        // The E6004 entry backstop (D7) scans before emission: a rejected
        // entry module emits no shim, and the orchestrator writes no
        // artifact for any module carrying an error diagnostic.
        FunctionDeclaration entryMain = isEntry
            ? scanEntryMain(program) : null;

        // Shape step 6: predeclared function and class-artifact
        // bindings in declaration order (the LuaBackend
        // predeclare-then-assign pattern): the binding exists before any
        // declaration assignment runs, so recursion, forward calls,
        // mutual recursion, and function bodies referencing
        // later-declared classes all resolve.
        List<String> predeclares = new ArrayList<>();
        for (StatementNode stmt : program.statements()) {
            switch (stmt) {
                case ClassDeclaration cd -> predeclares.add(
                    "let " + cd.name() + "$new; let " + cd.name() + "$meta;"
                        + (publishedPlanFor(cd) != null
                            ? " let " + cd.name() + "$plan;"
                            : ""));
                case ExportDeclaration ed -> {
                    switch (ed.declaration()) {
                        case ClassDeclaration cd -> predeclares.add(
                            "let " + cd.name() + "$new; let "
                                + cd.name() + "$meta;"
                                + (publishedPlanFor(cd) != null
                                    ? " let " + cd.name() + "$plan;"
                                    : "")
                                + (cd.isJsonable()
                                    ? " let " + cd.name() + "$fromJson; let "
                                        + cd.name() + "$toJson; let "
                                        + cd.name() + "$fields;"
                                    : ""));
                        case FunctionDeclaration fd -> predeclares.add(
                            "let " + jsName(fd.name()) + ";");
                        default -> {
                            // Unreachable: ExportDeclaration wraps only
                            // function/class declarations.
                        }
                    }
                }
                case FunctionDeclaration fd ->
                    predeclares.add("let " + jsName(fd.name()) + ";");
                default -> { }
            }
        }

        // Shape step 5: import bindings in import order — a
        // spec-stdlib raw path emits <relpath>/std/<name>, an
        // importResolutions entry a project-module relative require,
        // and a hostModules entry the loadHost binding (D1 below). The
        // rejection pass runs first: an extern-C import (E6003)
        // emits its diagnostic and no binding — a rejected import never
        // reaches the require path (js-backend-emitter D8). The E6003
        // arm precedes any host handling, so an extern-C host
        // path rejects before the loadHost binding is consulted.
        List<String> importBindings = new ArrayList<>();
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ImportDeclaration imp) {
                if (rejectUnsupportedImport(imp)) {
                    continue;
                }
                HostModuleDeclarations hostDecls =
                    hostModules.get(imp.modulePath());
                if (hostDecls != null) {
                    importBindings.add("const " + jsName(imp.alias())
                        + " = $rt.loadHost($require("
                        + jsStringLiteral(relativeSpecifier(
                            imp.modulePath()))
                        + "), "
                        + renderHostDeclaredMap(hostDecls) + ");");
                    continue;
                }
                String specifier = importRequireSpecifier(imp);
                if (specifier != null) {
                    importBindings.add("const " + jsName(imp.alias())
                        + " = $require(" + jsStringLiteral(specifier)
                        + ");");
                }
            }
        }

        emitHeader(predeclares, importBindings);
        for (StatementNode stmt : program.statements()) {
            visitStatement(stmt);
        }
        // Shape step 8: the export-assignment section follows the
        // declarations in the artifact (T4 populates it).
        emitExportsSection();
        if (isEntry && entryMain != null) {
            emitEntryShim(entryMain);
        }

        return new JsCodegenResult(modulePath, out.toString(), diagnostics,
            sourceMapGenerator, List.copyOf(runtimePlans));
    }

    // =========================================================================
    // Module shape (js-backend-architecture D2, js-backend-emitter D4)
    // =========================================================================

    /**
     * Emits the canonical CommonJS module shape, steps 1-7 in exact order.
     * Step 5 carries the import bindings built in declaration order by the
     * caller; step 6 carries the predeclared function and class-artifact
     * {@code let}s; step 8's section is emitted by
     * {@link #emitExportsSection()} after the declaration walk. Source
     * comments precede generated statement groups (js-backend-architecture
     * D8).
     *
     * <p>Host-global hygiene holds: after the capture line generated code
     * never spells bare {@code require}/{@code module}/{@code exports},
     * and never spells a bare host-global name anywhere; the nil-equivalent
     * value is the runtime member {@code $rt.undefined}, and table values
     * are constructed only through {@code $rt.makeTable}
     * (js-backend-architecture D2, js-backend-emitter D9).
     */
    private void emitHeader(List<String> predeclares,
                           List<String> importBindings) {
        out.append("// Generated by DEAL compiler — JavaScript backend. DO NOT EDIT.\n");
        out.append("// Source: ").append(sourcePath == null ? "" : sourcePath)
            .append("\n");
        // 1. Strict-mode header.
        out.append("\"use strict\";\n");
        // 2. CommonJS capture: $ -prefixed names cannot collide with any
        // user identifier, so the environment is immune to user bindings
        // named require/module/exports (all legal DEAL identifiers).
        out.append("const $require = require; const $module = module; "
            + "const $exports = exports;\n");
        // 3. The shared runtime: a relative specifier computed from the
        // emitting module's artifact directory (./deal/runtime for a root
        // module, ../deal/runtime for a nested one).
        out.append("const $rt = $require(\"")
            .append(runtimeRequireSpecifier()).append("\");\n");
        if (int32Mode) {
            // 3b. Profile selector (js-v12-int32-bytes D2): emitted only
            // under DEAL_V1_2_INT32, immediately after the runtime
            // $require, in every module of the output root. The call is
            // idempotent and one-way; the runtime keeps a module-private
            // flag, sound because one output root shares one profile
            // (F1) and the runtime is deployed once per root.
            out.append("$rt.setInt32Mode(true);\n");
        }
        out.append("\n");
        // 4. Intrinsic header seeds: int/number/bytes are first-class
        // function values with the seeded static signatures, plus the
        // module-private builtin-Error defaults/constructor pair
        // (neither is exported).
        out.append("// Intrinsic header wrappers: int/number are first-class function\n");
        out.append("// values with the seeded static signatures (number)->int and\n");
        out.append("// (int)->number; bytes seeds (int)->bytes (direct calls\n");
        out.append("// lower to $rt.bytes; first-class bytes values use the wrapper);\n");
        out.append("// the builtin-Error defaults/constructor/META artifacts stay\n");
        out.append("// private (never reach the export surface).\n");
        out.append("const int = $rt.function(\"(number)->int\", "
            + "(v, $file, $line, $column) => $rt.intConvert(v, $file, $line, $column));\n");
        out.append("const number = $rt.function(\"(int)->number\", "
            + "(v, $file, $line, $column) => $rt.numberConvert(v, $file, $line, $column));\n");
        // The bytes intrinsic seed (js-v12-int32-bytes D3): the wrapper's
        // $f routes to the same allocation member direct calls use, so a
        // function-typed bytes value calls bytes(n) through the identical
        // runtime boundary. The seed is emitted only when the module
        // declares no module-level user binding named `bytes`: a
        // checker-accepted module-level class/function/let/import named
        // `bytes` shadows the lowest-tier intrinsic (spec-v1.2.md Name
        // resolution — module-level declarations at step 3 and imports
        // at step 4 win over the compiler intrinsics at step 5), and
        // its emitted predeclare (`let bytes;` / `const bytes =
        // $require(...)`) would collide with the seed's module-scope
        // `const bytes`. The shadowing declaration's root symbol
        // replaces the IntrinsicSymbol, so resolveLocal tells the two
        // cases apart.
        if (symbols.resolveLocal("bytes") instanceof Symbol.IntrinsicSymbol) {
            out.append("const bytes = $rt.function(\"(int)->bytes\", "
                + "(v, $file, $line, $column) => $rt.bytes(v, $file, $line, $column));\n");
        }
        out.append("const $ErrorDefaults = () => ({ [\"code\"]: \"\", "
            + "[\"message\"]: \"\" });\n");
        // The builtin Error identity is the canonical @$builtin/Error
        // atom, produced through the compilation's descriptor service
        // (js-v12-completion-architecture D3 — the supersession of the
        // v1.1 bare-`Error` spelling).
        String $errorIdentity =
            descriptors.encode(Types.classType("Error",
                new CanonicalClassIdentity(
                    CanonicalModuleIdentity.BuiltinModule.INSTANCE,
                    "Error")));
        out.append("const Error$new = (provided, $file, $line, $column) => "
            + "$rt.makeClass(\"Error\", " + jsStringLiteral($errorIdentity)
            + ", $ErrorDefaults, provided, "
            + "$file, $line, $column);\n");
        // The builtin-Error META pair member: `Error` as a value is
        // checker-accepted class metadata exactly like any declared
        // class's — module-private, identity @$builtin/Error, the inline
        // META shape of js-backend-emitter D4 step 7.
        out.append("const Error$meta = { $kind: \"class\", "
            + "$classname: " + jsStringLiteral($errorIdentity) + " };\n");
        out.append("\n");
        // 5. Import bindings in import order: spec-stdlib raw paths
        // require <relpath>/std/<name>, project modules the relative
        // artifact path (extension omitted). The bindings precede the
        // predeclares, so every imported module initializes
        // (depth-first) before any declaration initializer runs.
        out.append("// Import bindings (import order).\n");
        for (String binding : importBindings) {
            out.append(binding).append("\n");
        }
        out.append("\n");
        // 6. Predeclared lets for module-level functions and class
        // artifacts in declaration order — recursion, forward calls,
        // mutual recursion, and function bodies referencing
        // later-declared classes all resolve through these bindings.
        out.append("// Predeclared function and class-artifact bindings "
            + "(declaration order).\n");
        for (String predeclare : predeclares) {
            // The class-artifact names carry the compiler-generated "$"
            // suffix and function names pass through jsName, so every
            // raw user name — a JS reserved word included — yields a
            // valid, collision-free strict-mode binding.
            out.append(predeclare).append("\n");
        }
        out.append("\n");
        // 7. Declarations in source order (the statement walk follows).
        out.append("// Declarations (source order).\n");
        out.append("\n");
    }

    /**
     * Shape step 8: the export assignments follow the declaration walk
     * in the artifact, in declaration order, every write
     * own-property-safe via {@code $rt.setProp} with the raw export key
     * (a module exporting {@code __proto__} works exactly like any other
     * name; property/export keys keep raw names — no {@code jsName}
     * translation). An exported class additionally exports the hidden
     * compiler-generated {@code <C>$new} closure, so imported
     * construction runs the declaring module's closure; a
     * non-exported class's {@code <C>$new} stays module-private. No
     * bare {@code module.exports} spelling appears.
     */
    private void emitExportsSection() {
        out.append("// Export assignments (declaration order).\n");
        for (String assignment : exportAssignments) {
            out.append(assignment).append("\n");
        }
    }

    /**
     * The emitted require specifier for the shared runtime, computed from
     * the emitting module's artifact directory (js-backend-architecture
     * D2): {@code ./deal/runtime} for a root-emitted module (modulePath
     * {@code main}), {@code ../deal/runtime} for a nested one
     * ({@code app.main}), {@code ../../deal/runtime} for
     * {@code app.sub.main}, and so on.
     */
    private String runtimeRequireSpecifier() {
        return relativeSpecifier("deal/runtime");
    }

    /**
     * The relative CommonJS require specifier from the emitting module's
     * artifact directory to a target artifact (js-backend-architecture
     * D2): sibling artifacts emit {@code ./<name>}, a nested target
     * {@code ./<dir>/<name>}, a target outside the emitting directory
     * {@code ../.../<name>}, the extension omitted (Node resolution).
     * The dotted target path carries the artifact path with {@code '/'}
     * for {@code '.'} (e.g. {@code deal/runtime}, {@code app/util},
     * {@code lib}).
     */
    private String relativeSpecifier(String dottedTargetPath) {
        // An already-relative target — a host import whose raw
        // specifier is written with a ./ or ../ prefix — is the
        // relative require form verbatim (Node resolves it against the
        // emitting module's artifact directory, exactly the rule's
        // intent; js-v12-host-abi-completion D1 keeps the raw
        // specifier byte-for-byte).
        if (dottedTargetPath.startsWith("./")
                || dottedTargetPath.startsWith("../")) {
            return dottedTargetPath;
        }
        String moduleArtifact = (modulePath == null ? "" : modulePath)
            .replace('.', '/');
        String targetArtifact = dottedTargetPath.replace('.', '/');
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
            if (specifier.length() > 0) specifier.append('/');
            specifier.append("..");
        }
        for (int i = common; i < targetSegs.size(); i++) {
            if (specifier.length() > 0) specifier.append('/');
            specifier.append(targetSegs.get(i));
        }
        if (specifier.length() > 0) specifier.append('/');
        specifier.append(targetName);
        String relative = specifier.toString();
        return relative.startsWith(".") ? relative : "./" + relative;
    }

    /**
     * The emitted require specifier for one import declaration
     * (js-backend-emitter D4 step 5, D8 import classification): a
     * spec-stdlib raw path (bare {@code std/<name>}, the
     * {@code StdlibModuleResolver} spec list) emits
     * {@code <relpath>/std/<name>} — {@code ./std/console} for a root
     * module, {@code ../std/string} for a nested one; an
     * {@code importResolutions} entry is a project module whose
     * relative specifier runs between the emitting module's artifact
     * directory and the imported module's artifact directory (sibling
     * {@code main} importing {@code lib} → {@code ./lib}; root
     * {@code main} importing {@code app.util} → {@code ./app/util};
     * nested {@code app.main} importing {@code sub.util} →
     * {@code ../sub/util}). A non-stdlib declaration file (a
     * {@code hostModules} entry — also present in
     * {@code importResolutions}) returns {@code null} here: the
     * caller emits the {@code $rt.loadHost} binding with the declared
     * map instead (the host branch precedes this method in the import
     * loop), and no raw require of a declaration file can ever resolve.
     * An unresolved path returns {@code null}: no binding is emitted.
     * The {@code @extern-c} E6003 diagnostic fires earlier in
     * {@link #rejectUnsupportedImport}, before this method is
     * consulted; the host check here stays as the defensive guard for
     * classification-driven emission (a {@code @extern-c}-marked
     * import never reaches it). Declaration files emit no artifact, so
     * a require of one can never resolve at runtime; a rejected module
     * writes no artifact at all (js-backend-emitter D8).
     */
    private String importRequireSpecifier(ImportDeclaration imp) {
        String raw = imp.modulePath();
        if (StdlibModuleResolver.isSpecStdlibModule(raw)) {
            return relativeSpecifier(raw);
        }
        if (hostModules.containsKey(raw)) {
            return null;
        }
        String resolved = importResolutions.get(raw);
        if (resolved != null) {
            return relativeSpecifier(resolved);
        }
        return null;
    }

    /**
     * The emitter-rendered host declared map (ISSUE-0328,
     * js-v12-host-abi-completion D1) — the second {@code $rt.loadHost}
     * argument: one entry per declared export name in sorted order
     * (deterministic emission independent of the caller-supplied map
     * implementation, the LuaBackend TreeMap precedent):
     *
     * <pre>
     * "&lt;name&gt;": { $k: "function", $d: "&lt;canonical declared descriptor&gt;" }
     *           | { $k: "class", $d: "&lt;canonical identity&gt;",
     *               $fields: [ { name, $d, optional, nullable, hasDefault }, ... ] }
     * </pre>
     *
     * A declared function export renders its canonical function
     * descriptor; a declared class export renders the canonical
     * externals identity ({@code @$external/&lt;specifier&gt;/&lt;ClassName&gt;})
     * plus the declared field-descriptor array. Every descriptor text
     * comes from {@link #descriptors} — the single Type&rarr;text
     * authority; no legacy spelling is emitted. Extra host exports are
     * dropped structurally by the runtime loader; a missing declared
     * export is the loader's load-time E8011.
     */
    private String renderHostDeclaredMap(HostModuleDeclarations decls) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Type> e
                : new TreeMap<>(decls.exports()).entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(jsStringLiteral(e.getKey())).append(": ");
            Type t = e.getValue();
            if (t instanceof Type.Func) {
                sb.append("{ $k: ").append(jsStringLiteral("function")).append(", $d: ")
                    .append(jsStringLiteral(descriptors.encode(t)))
                    .append(" }");
            } else if (t instanceof Type.Class cls) {
                sb.append("{ $k: ").append(jsStringLiteral("class")).append(", $d: ")
                    .append(jsStringLiteral(descriptors.encode(cls)))
                    .append(", $fields: ")
                    .append(renderHostClassFields(
                        decls.classFields().getOrDefault(e.getKey(),
                            List.of())))
                    .append(" }");
            } else {
                // Unreachable for checker-accepted host declarations
                // (declaration files export only functions and classes);
                // the defensive function-form render makes the loader
                // raise its pinned E8011 unsupported-descriptor at load.
                sb.append("{ $k: ").append(jsStringLiteral("function")).append(", $d: ")
                    .append(jsStringLiteral(descriptors.encode(t)))
                    .append(" }");
            }
        }
        return sb.append("}").toString();
    }

    /**
     * The declared field-descriptor array of one host class export
     * (js-v12-host-abi-completion D1): one entry per declared field in
     * declaration order — the AST record's {@code name}/
     * {@code optional}/{@code nullable}/{@code hasDefault} and the
     * canonical {@code $d} descriptor of the orchestrator-resolved
     * field type (a same-module class field type projects the
     * declaring module's canonical identity).
     */
    private String renderHostClassFields(
            List<HostModuleDeclarations.HostField> fields) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            HostModuleDeclarations.HostField f = fields.get(i);
            ClassField cf = f.declaration();
            sb.append("{ name: ").append(jsStringLiteral(cf.name()))
                .append(", $d: ")
                .append(jsStringLiteral(descriptors.encode(f.type())))
                .append(", optional: ").append(cf.optional())
                .append(", nullable: ").append(cf.nullable())
                .append(", hasDefault: ")
                .append(cf.defaultExpr().isPresent())
                .append(" }");
        }
        return sb.append("]").toString();
    }

    /**
     * The D8 import-classification rejections, fired before any import
     * binding is computed (js-backend-emitter D8; re-keyed by
     * fixed-name-directive-events D9). An import whose raw path is
     * marked in the orchestrator-built extern-C import set — the
     * resolved module is a declaration file with effective
     * {@code FileDirectives.externC} — is E6003 containing
     * {@code FFI_UNSUPPORTED_BACKEND} at the import statement
     * (deal-v1.2-directives-and-c-ffi-declarations D8: an incapable
     * backend rejects {@code @extern-c} before any artifact write).
     * The former host-ABI E6000 arm retired with ISSUE-0328: a
     * non-stdlib declaration-file import (a {@code hostModules} entry)
     * now emits the {@code $rt.loadHost} binding with the declared map
     * (js-v12-host-abi-completion D1). The E6003 arm precedes any host
     * handling, so an extern-C host path rejects before the loadHost
     * binding is consulted. Spec-stdlib raw paths and
     * {@code importResolutions} entries are never rejected here.
     */
    private boolean rejectUnsupportedImport(ImportDeclaration imp) {
        if (externCImports.contains(imp.modulePath())) {
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E6003,
                "JavaScript backend: @extern-c imports are not supported "
                    + "(FFI_UNSUPPORTED_BACKEND, ISSUE-0169 skeleton)",
                imp.span()));
            return true;
        }
        return false;
    }

    /**
     * True when the identifier expression is the module-alias binding
     * of an import declaration visible at the current walk position:
     * the module symbol table resolves the name to a
     * {@link Symbol.ModuleSymbol} and no local declaration shadows it
     * (a checker-accepted function-local {@code let} of the alias name
     * carries its own type — reads/writes must follow that local, never
     * the module-export surface).
     */
    private boolean isModuleAliasRef(IdentifierExpr id) {
        if (isLocalName(id.name())) return false;
        return symbols.resolve(id.name()) instanceof Symbol.ModuleSymbol;
    }

    /**
     * Entry shim (js-backend-emitter D7): the {@code $require.main ===
     * $module} guard runs {@code main} exactly once when this artifact is
     * the node entry, and the location-embedding catch body composes a
     * located error's file/line/column into the message before
     * {@code $rt.reportUncaught} — the nil-equivalence comparison spells
     * the runtime member {@code $rt.undefined}, never the bare
     * host-global. A location-less error prints the bare
     * {@code DEAL_ERROR_CODE} line.
     */
    private void emitEntryShim(FunctionDeclaration entryMain) {
        // js-v12-source-maps D2: the entry-shim emission records its
        // mapping at the shim's source-comment site (the emission sits
        // outside the statement walk, so no visitStatement recording
        // covers it) — after the separating blank line, so the recorded
        // generated position is the comment line itself.
        out.append("\n");
        recordMapping(entryMain.span());
        out.append("// Entry invocation: runs the exported main exactly once when this\n");
        out.append("// artifact is the node entry; an uncaught error prints the\n");
        out.append("// DEAL_ERROR_CODE line with the source-location suffix and sets\n");
        out.append("// the exit code.\n");
        out.append("if ($require.main === $module) {\n");
        out.append("  try {\n");
        out.append("    $exports.main.$f();\n");
        out.append("  } catch (e) {\n");
        out.append("    const $err = $rt.reifyError(e);\n");
        out.append("    $rt.reportUncaught($err.file !== $rt.undefined ? "
            + "$rt.errorValue($err.code, $err.message + \" at \" + $err.file "
            + "+ \":\" + $err.line + \":\" + $err.column, $err.file, "
            + "$err.line, $err.column) : $err);\n");
        out.append("  }\n");
        out.append("}\n");
    }

    // =========================================================================
    // Entry backstop (js-backend-emitter D7)
    // =========================================================================

    /**
     * E6004 entry backstop: the defensive scan that keeps the artifact
     * valid when the backend is driven without the orchestrator's
     * E2010/E2011 entry gate (the {@code JvmBackend.emitEntryPoint} scan
     * precedent, deal/codegen/jvm/JvmBackend.java:1599-1652). The selected
     * entry module must export a non-async {@code main} with signature
     * {@code (): null}; a missing or mismatched {@code main} is an E6004
     * diagnostic at the main declaration (or the program span) and no
     * shim is emitted.
     */
    private FunctionDeclaration scanEntryMain(ProgramNode program) {
        FunctionDeclaration match = null;
        boolean foundAnyMain = false;
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ExportDeclaration ed
                    && ed.declaration() instanceof FunctionDeclaration fd) {
                if (fd.name().equals("main")) {
                    foundAnyMain = true;
                    if (fd.params().isEmpty() && !fd.isAsync()
                            && resolveTypeNode(fd.returnType()) instanceof Type.Null) {
                        match = fd;
                    }
                }
            }
        }
        if (match == null) {
            // T12-native ranged factory: the diagnostic is anchored at the
            // program span (the JvmBackend.emitEntryPoint E6004 pattern,
            // SOURCE-exact via the program-span range).
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E6004,
                "entry module must export non-async main(): null; found "
                    + (foundAnyMain
                        ? "main with a different signature or an async marker"
                        : "no main export"),
                program.span()));
        }
        return match;
    }

    /**
     * Resolves an AST type node to the internal {@link Type} without
     * re-checking (the {@code LuaBackend.resolveTypeNode} mirror,
     * deal/codegen/lua/LuaBackend.java:2934-2985): primitive names
     * (the builtin {@code Error} included, module path empty), local
     * classes through the module symbol table, qualified cross-module
     * class references through the module exports, and the array/
     * nullable/function compositions. Unresolvable nodes resolve to
     * {@link Type.Error#INSTANCE} — the checker has already rejected
     * those programs.
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
                case "Error" -> Types.classType("Error",
                    new CanonicalClassIdentity(
                        CanonicalModuleIdentity.BuiltinModule.INSTANCE,
                        "Error"));
                // v1.2 bytes (js-v12-int32-bytes D3): a bytes-spelled
                // named type resolves to the canonical Type.Bytes
                // primitive. bytes is not a DEAL keyword, so a
                // checker-accepted user class named bytes resolves to
                // its ClassSymbol and wins over the primitive — the
                // retired defensive rejection arm used the same guard.
                // The checker resolves the annotation in the lexical
                // scope that contains it; the emitter's root table
                // cannot see a nested class declaration, so the visible
                // nested-class scope frames carry the same
                // class-symbol-first decision here (a nested user class
                // named bytes types its annotation as the class, never
                // as the primitive — the checker accepted the program
                // on that resolution).
                case "bytes" -> {
                    Symbol sym = symbols.resolve(nt.name());
                    if (sym instanceof Symbol.ClassSymbol cs) {
                        yield Types.classType(nt.name(), cs.identity());
                    }
                    if (innermostFrame(localClassScopes, nt.name()) >= 0) {
                        yield localClassType(nt.name());
                    }
                    yield Type.Bytes.INSTANCE;
                }
                default -> {
                    Symbol sym = symbols.resolve(nt.name());
                    if (sym instanceof Symbol.ClassSymbol cs) {
                        yield Types.classType(nt.name(), cs.identity());
                    }
                    yield Type.Error.INSTANCE;
                }
            };
            case QualifiedType qt -> {
                Symbol sym = symbols.resolve(qt.moduleName());
                if (sym instanceof Symbol.ModuleSymbol ms) {
                    Type exportType = ms.exports().get(qt.typeName());
                    if (exportType != null) yield exportType;
                }
                yield classTypeFor(qt.typeName(), qt.moduleName());
            }
            case ArrayType at -> {
                Type elem = resolveTypeNode(at.elementType());
                if (elem == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                yield new Type.Array(elem);
            }
            case NullableType nt2 -> {
                Type inner = resolveTypeNode(nt2.innerType());
                if (inner == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                yield new Type.Nullable(inner);
            }
            case FunctionType ft -> {
                List<Type> paramTypes = new ArrayList<>();
                for (FunctionTypeParam ftp : ft.params()) {
                    Type pt = resolveTypeNode(ftp.type());
                    if (pt == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                    paramTypes.add(pt);
                }
                Type ret = resolveTypeNode(ft.returnType());
                if (ret == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                yield new Type.Func(paramTypes, ret, ft.isAsync());
            }
        };
    }

    // =========================================================================
    // Statement walking
    // =========================================================================

    /**
     * Statement dispatch. Import declarations (T4) and export
     * declarations (T4 assignments; a wrapped function/class
     * declaration emits its artifacts here) remain tolerated
     * structurally at this slice (js-backend-emitter D1); every other
     * statement kind lowers fully.
     */
    private void visitStatement(StatementNode stmt) {
        // js-v12-source-maps D2: one mapping per generated
        // statement-group boundary — recorded at the current output
        // position (the source-comment emission site for wrapper,
        // class, and entry-shim groups) mapped to the AST statement's
        // span start (the LuaBackend.visitStatement precedent,
        // deal/codegen/lua/LuaBackend.java:1051-1054).
        recordMapping(stmt.span());

        switch (stmt) {
            case ImportDeclaration imp -> {
                // Shape step 5 emitted the binding in the header (a
                // rejected @extern-c E6003 import was already diagnosed
                // there and emitted no binding); the walk itself emits
                // no statement.
            }
            case FunctionDeclaration fd -> visit(fd);
            case ClassDeclaration cd -> visit(cd);
            case ExportDeclaration ed -> {
                // Shape step 8: the wrapped declaration emits its
                // artifacts here; the export assignments (raw keys,
                // own-property-safe writes) collect in declaration
                // order for the section emitted after the walk. An
                // exported class additionally exports the hidden
                // compiler-generated <C>$new closure (imported
                // construction runs the declaring module's closure).
                // A nested (below-module-statement-list) exported class
                // writes its two assignments inline at the declaration
                // site instead (js-v12-completion-architecture D4): its
                // scope-local <C>$meta/<C>$new bindings are not visible
                // at the module-end section, and the block executes at
                // declaration position — the ordinary export path with
                // the same raw keys.
                switch (ed.declaration()) {
                    case ClassDeclaration cd -> {
                        // js-v12-jsonable-completion D1: an exported
                        // @jsonable class additionally exports the two
                        // generated wrappers under their raw $-sigil
                        // keys plus the hidden C$fields descriptor
                        // export — collision-free by construction (user
                        // DEAL identifiers cannot contain $, while JS
                        // identifiers may — no translation table is
                        // needed, unlike the Lua __deal namespace).
                        // @jsonable stays module-level-export-class-only
                        // (checker-owned): nested classes are never
                        // jsonable, so the wrapper/fields export
                        // assignments emit for module-level declarations
                        // only — a nested site's scope-local artifacts
                        // are invisible at the module-end section
                        // (js-v12-completion-architecture D4).
                        visit(cd);
                        if (statementDepth == 0) {
                            exportAssignments.add("$rt.setProp($exports, "
                                + jsStringLiteral(cd.name()) + ", "
                                + cd.name() + "$meta);");
                            exportAssignments.add("$rt.setProp($exports, "
                                + jsStringLiteral(cd.name() + "$new") + ", "
                                + cd.name() + "$new);");
                        } else {
                            line("$rt.setProp($exports, "
                                + jsStringLiteral(cd.name()) + ", "
                                + cd.name() + "$meta);");
                            line("$rt.setProp($exports, "
                                + jsStringLiteral(cd.name() + "$new") + ", "
                                + cd.name() + "$new);");
                        }
                        if (cd.isJsonable() && statementDepth == 0) {
                            exportAssignments.add("$rt.setProp($exports, "
                                + jsStringLiteral(cd.name() + "$fromJson")
                                + ", " + cd.name() + "$fromJson);");
                            exportAssignments.add("$rt.setProp($exports, "
                                + jsStringLiteral(cd.name() + "$toJson")
                                + ", " + cd.name() + "$toJson);");
                            exportAssignments.add("$rt.setProp($exports, "
                                + jsStringLiteral(cd.name() + "$fields")
                                + ", " + cd.name() + "$fields);");
                        }
                    }
                    case FunctionDeclaration fd -> {
                        visit(fd);
                        exportAssignments.add("$rt.setProp($exports, "
                            + jsStringLiteral(fd.name()) + ", "
                            + jsName(fd.name()) + ");");
                    }
                    default -> {
                        // Unreachable: ExportDeclaration wraps only
                        // function/class declarations.
                    }
                }
            }
            case VariableDeclaration vd -> visit(vd);
            case ReturnStatement rs -> visit(rs);
            case IfStatement is -> visit(is);
            case WhileStatement ws -> visit(ws);
            case ForStatement fs -> visit(fs);
            case ForOfStatement fos -> visit(fos);
            case BreakStatement bs -> line("break;");
            case ContinueStatement cs -> line("continue;");
            case ExpressionStatement es ->
                line(emitExpression(es.expr()) + ";");
            case DeleteStatement ds -> visit(ds);
            case TryStatement ts -> visit(ts);
            case ThrowStatement th -> visit(th);
            case Block b -> visit(b);
        }
    }

    /**
     * Function declaration (js-backend-emitter D4 step 7, D6): the
     * predeclared {@code let} carries the header (step 6 at module
     * level) or the enclosing statement scope's hoisting loop (nested
     * functions); the declaration site assigns the wrapper —
     * {@code $rt.function} with the exact descriptor signature, the
     * jsName-translated inner function name {@code <name>$f} (a binding
     * position), the jsName-translated parameter list plus the trailing
     * {@code $}-prefixed span parameters (duplicate-free for user
     * parameters literally named {@code file}/{@code line}/
     * {@code column} — the {@code LuaBackend.visit(FunctionDeclaration)}
     * wrapper shape with the js-backend-architecture D5 span-parameter
     * extension), the entry parameter checks in parameter order with
     * the forwarded span, the body walk through the T2 lowering, and
     * the return-site exit checks. The declared return type drives
     * {@link #currentReturnType} for the body's {@code return}
     * statements; a null-typed function appends the validated
     * {@code null} fall-off return after the body (control falling off
     * the end returns the DEAL null). Async declarations emit the
     * native {@code async function} keyword, the declared-return check
     * runs inside the async body on every return path (the fall-off
     * {@code null} completion included), and the body's awaits lower
     * through {@link #emitAwait} — the completion value crosses its
     * typed boundary exactly once, before the Promise resolves
     * (js-backend-architecture D5).
     */
    private void visit(FunctionDeclaration fd) {
        String name = fd.name();
        // Module-level declarations resolve the checker's hoisted
        // symbol; nested declarations derive the type from the AST —
        // a nested scope may shadow a module-level function of the same
        // name with a different signature, which the module table
        // lookup would silently misreport.
        Type.Func funcType = atModuleLevel ? getFunctionType(name) : null;
        if (funcType == null) {
            funcType = functionTypeFromAst(fd);
        }
        String sig = funcType != null ? descriptors.encode(funcType) : "()";
        Type returnType = funcType != null ? funcType.returnType() : null;
        boolean isAsync = funcType != null && funcType.isAsync();

        out.append("// Function: ").append(name)
            .append(" — wrapper with entry parameter checks and")
            .append(" return-site exit checks.\n");
        line(jsName(name) + " = $rt.function(" + jsStringLiteral(sig) + ", "
            + (isAsync ? "async function " : "function ") + jsName(name)
            + "$f(" + buildParamList(fd.params()) + ") {");
        indent++;
        Type savedReturn = currentReturnType;
        currentReturnType = returnType;
        try {
            emitWrappedBody(fd.params(), fd.body(), returnType,
                fd.returnType().span());
        } finally {
            currentReturnType = savedReturn;
            indent--;
        }
        line("});");
        out.append("\n");
    }

    /**
     * The checker's module-level function type, resolved from the
     * module symbol table (the {@code LuaBackend.getFunctionType}
     * mirror) — consulted only at module level, where the hoisted
     * symbol carries the checker's exact type.
     */
    private Type.Func getFunctionType(String name) {
        Symbol sym = symbols.resolve(name);
        if (sym instanceof Symbol.FunctionSymbol fs) {
            return fs.funcType();
        }
        return null;
    }

    /**
     * The checker's {@code walkFuncDecl} type construction
     * (deal/checker/NameResolver.java, the nested-function branch):
     * the parameter types and the declared return type compose the
     * function type — used for every nested function declaration, whose
     * symbols live in function-local scopes the module table cannot see
     * (and whose name may shadow a module-level function with a
     * different signature).
     */
    private Type.Func functionTypeFromAst(FunctionDeclaration fd) {
        List<Type> paramTypes = new ArrayList<>();
        for (Parameter p : fd.params()) {
            Type pt = resolveTypeNode(p.type());
            if (pt == Type.Error.INSTANCE) {
                return null;
            }
            paramTypes.add(pt);
        }
        Type ret = resolveTypeNode(fd.returnType());
        if (ret == Type.Error.INSTANCE) {
            return null;
        }
        return new Type.Func(paramTypes, ret, fd.isAsync());
    }

    /**
     * The wrapper parameter list both declaration and expression forms
     * share: jsName-translated user parameters (binding positions) plus
     * the trailing span parameters {@code $file}/{@code $line}/
     * {@code $column}. The span triple joins the user list only when
     * user parameters exist — a zero-parameter wrapper emits
     * {@code ($file, $line, $column)}, never the invalid leading-comma
     * form. The {@code $}-prefix keeps the span parameters
     * duplicate-free for user parameters literally named {@code file}/
     * {@code line}/{@code column} (all legal DEAL identifiers, while
     * user identifiers cannot contain {@code $}).
     */
    private String buildParamList(List<Parameter> params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(jsName(params.get(i).name()));
        }
        if (sb.length() > 0) sb.append(", ");
        sb.append("$file, $line, $column");
        return sb.toString();
    }

    /**
     * The shared wrapper body emission: entry parameter checks in
     * parameter order against the declared parameter types with the
     * forwarded {@code $file}/{@code $line}/{@code $column} span (so
     * parameter errors report the call site, js-backend-runtime D6),
     * the body statement walk inside an additional block scope with
     * the parameters declared above it (mirroring the checker's
     * function scope and body Block scope, so per-scope hoisted
     * {@code let}s legally shadow the parameter bindings), and — for a
     * function whose declared return type is {@code null} — the
     * validated fall-off return after the block (control falling off
     * the end returns the DEAL null). For an async wrapper the
     * trailing fall-off return sits inside the {@code async function}
     * body, so the implicit completion crosses the declared-return
     * boundary before the Promise resolves (js-backend-architecture
     * D5) — without it the Promise would resolve with the nil
     * equivalent {@code undefined} and the awaiter's {@code null}
     * boundary would raise E8001, where the reference maps the
     * coroutine's nil fall-off to DEAL null. A nested class declaration
     * in the body emits its scope-local artifact pair through
     * {@link #walkStatements} (ISSUE-0318: the D6 E6000 arm retired).
     * Return statements inside the body check against
     * {@link #currentReturnType} at the return site.
     */
    private void emitWrappedBody(List<Parameter> params, Block body,
                                 Type returnType, Span fallOffSpan) {
        // js-v12-source-maps D2: the wrapper's entry parameter-check
        // group records its mapping at the emission site too (the first
        // parameter's type span — the span the emitted checks forward) —
        // wrapper, check, and entry-shim emissions record the same way.
        if (!params.isEmpty()) {
            recordMapping(params.get(0).type().span());
        }
        for (Parameter param : params) {
            Type paramType = resolveTypeNode(param.type());
            if (paramType != null && !(paramType instanceof Type.Error)
                    && !(paramType instanceof Type.Null)) {
                line(emitCheckExprForwarded(jsName(param.name()), paramType)
                    + ";");
            }
        }
        // The body statements emit inside an additional block scope:
        // the checker gives every function body its own Block scope
        // below the parameter scope (walkFuncDecl/walkFunctionExpr then
        // walkBlock), and the emitted block makes the per-scope hoisted
        // `let`s legally shadow the wrapper's parameter bindings — a
        // checker-accepted nested function named like a parameter is a
        // shadow in a child scope, never a strict-mode duplicate of the
        // parameter in the same scope.
        line("{");
        indent++;
        pushLocalScope();
        try {
            for (Parameter param : params) {
                declareLocal(param.name());
            }
            boolean savedAtModuleLevel = atModuleLevel;
            atModuleLevel = false;
            try {
                walkStatements(body.statements());
            } finally {
                atModuleLevel = savedAtModuleLevel;
            }
        } finally {
            popLocalScope();
        }
        indent--;
        line("}");
        if (returnType instanceof Type.Null) {
            line("return $rt.checkNull(null, " + spanArgs(fallOffSpan) + ");");
        }
    }

    /**
     * Class declaration (js-backend-emitter D4 step 7, extended by
     * js-v12-completion-architecture D4): the artifact {@code let}s are
     * predeclared at the top of the enclosing block — the header (shape
     * step 6) at module level, the per-scope hoisting loop of
     * {@link #walkStatements} for a nested (below-module-level)
     * declaration — and the declaration site assigns the construction
     * closure plus the inline META pair in both positions. The
     * assignment shape is identical at every level: a zero-arg defaults
     * thunk emitted inline at the declaration site (the arrow closes
     * over the declaring scope, so a scope-local default function
     * captures the declaring scope's bindings and re-evaluates on every
     * construction — {@code $rt.makeClass} invokes the thunk once per
     * construction, js-backend-runtime D5) with computed keys and
     * {@code $rt.MISSING} for absent optional fields. The identity is
     * the module-qualified canonical projection
     * {@code @<configuredRootText>/<relativeModuleComponents>/<Name>}
     * through the one descriptor service (js-v12-completion-architecture
     * D3/D4): nested classes share the declaring module's identity
     * space. The former D6 nested-class E6000 arm is retired — a
     * checker-accepted nested class declaration is an ordinary feature,
     * never a rejection (js-v12-completion-architecture D4). The
     * {@code @jsonable} generation stays module-level-export-class-only
     * (checker-owned, js-v12-completion-architecture D4): a nested
     * class emits no {@code C$fromJson}/{@code C$toJson}/{@code C$fields}
     * artifacts and no jsonable exports (the module-end export section
     * cannot see a nested site's scope-local bindings).
     */
    private void visit(ClassDeclaration cd) {
        // The pinned evaluator labels (ISSUE-0544, runtime page D2):
        // one (classIdentity, fieldName, semanticDigest) triple per
        // required-present entry, as JS comments directly above the
        // construction closure — the artifact carries the label of
        // every evaluator it creates.
        for (String label : defaultEvaluatorLabels(cd)) {
            line("// default evaluator " + label);
        }
        // The class comment lands BEFORE the plan/thunk text is
        // assembled: the captured expression mappings rebase against
        // the already-appended comment line, exactly like the pre-plan
        // emission (js-v12-source-maps D2 exactness).
        out.append("// Class: ").append(cd.name())
            .append(" — construction closure, defaults plan, and metadata.\n");
        String identity = qualifiedClassName(cd.name());
        CompilerClassDefaultPlan plan = publishedPlanFor(cd);
        if (plan != null) {
            // ISSUE-0545 (the construction-consumption epic, runtime
            // page D4/D6): a plan-bearing class constructs through
            // $rt.classPlan over its published runtime plan list — the
            // per-entry shape {name, descriptor, optional, evaluator}
            // with labelled zero-argument evaluator closures created at
            // load and never invoked there. $rt.classPlan realizes the
            // four pinned phases: extra-key E8007 before any default,
            // omitted required defaults exactly once per attempt in
            // plan order, per-field canonical validation, tag/publish.
            String planList = publishedPlanList(plan);
            line(cd.name() + "$plan = " + planList + ";");
            line(cd.name() + "$new = (provided, $file, $line, $column) => "
                + "$rt.classPlan(" + jsStringLiteral(identity) + ", "
                + cd.name() + "$plan, provided, $file, $line, $column);");
            line(cd.name() + "$meta = { $kind: \"class\", $classname: "
                + jsStringLiteral(identity) + " };");
            if (cd.isJsonable() && statementDepth == 0) {
                emitJsonableArtifacts(cd, plan);
            }
        } else {
            String thunk = classDefaultsThunk(cd);
            line(cd.name() + "$new = (provided, $file, $line, $column) => "
                + "$rt.makeClass(" + jsStringLiteral(cd.name()) + ", "
                + jsStringLiteral(identity) + ", "
                + thunk + ", provided, $file, $line, $column);");
            line(cd.name() + "$meta = { $kind: \"class\", $classname: "
                + jsStringLiteral(identity) + " };");
            if (cd.isJsonable() && statementDepth == 0) {
                emitJsonableArtifacts(cd, thunk);
            }
        }
        out.append("\n");
    }

    /**
     * The ordered evaluator labels of this class's published plan —
     * empty when no plan was published (host declarations and the
     * standalone entry points).
     */
    private List<String> defaultEvaluatorLabels(ClassDeclaration cd) {
        CompilerClassDefaultPlan plan = publishedPlanFor(cd);
        if (plan == null) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (CompilerClassDefaultEntry entry : plan.orderedFields()) {
            if (!entry.optional()) {
                labels.add(RuntimeDefaultPlanLowering.labelOf(
                    identityText(plan.classIdentity()), entry.name(),
                    entry.defaultExpression().semanticDigest()));
            }
        }
        return labels;
    }

    /**
     * JSONable artifact emission (js-v12-jsonable-completion D1-D2): the
     * two exported wrappers plus the hidden field-descriptor array, in
     * the pinned order — {@code C$fromJson}, {@code C$toJson},
     * {@code C$fields} — over the canonical identity and the
     * per-construction defaults thunk the construction closure shares.
     * The wrapper bodies are the pinned walker calls: the parameter
     * checks and the declared-return shape live inside the runtime
     * walkers ({@code $rt.jsonFromJson} collapses every failure to the
     * DEAL null — the public export never throws; {@code $rt.jsonToJson}
     * is the defensive identity backstop and raises E8001 on
     * non-JSON-shaped values), so the emitted arrow bodies carry no
     * extra checks. The {@code $} sigil makes every generated name
     * collision-free (user DEAL identifiers cannot contain {@code $}).
     */
    private void emitJsonableArtifacts(ClassDeclaration cd, String thunk) {
        String identity = qualifiedClassName(cd.name());
        out.append("// Jsonable: ").append(cd.name())
            .append(" — C$fromJson/C$toJson wrappers and the hidden")
            .append(" C$fields descriptor export.\n");
        line(cd.name() + "$fromJson = $rt.function("
            + jsStringLiteral("(string)->?" + identity) + ", "
            + "(s, $file, $line, $column) => $rt.jsonFromJson("
            + jsStringLiteral(identity) + ", " + cd.name() + "$fields, "
            + thunk + ", s, $file, $line, $column));");
        line(cd.name() + "$toJson = $rt.function("
            + jsStringLiteral("(" + identity + ")->string") + ", "
            + "(v, $file, $line, $column) => $rt.jsonToJson("
            + jsStringLiteral(identity) + ", v, " + cd.name() + "$fields, "
            + "$file, $line, $column));");
        line(cd.name() + "$fields = "
            + jsonFieldsArray(cd.fields(), null, null) + ";");
    }

    /**
     * The plan-bearing JSONable variant (ISSUE-0545, runtime page D5):
     * the {@code C$fromJson} wrapper passes the published plan's
     * per-entry evaluator carriers through the descriptor array (the
     * walker consumes exactly the omitted required entries, once per
     * attempt — no thunk, so no eager default evaluation) while the
     * {@code C$toJson} wrapper and the exported descriptor shape stay
     * byte-identical to the thunk form.
     */
    private void emitJsonableArtifacts(ClassDeclaration cd,
                                       CompilerClassDefaultPlan plan) {
        String identity = qualifiedClassName(cd.name());
        out.append("// Jsonable: ").append(cd.name())
            .append(" — C$fromJson/C$toJson wrappers and the hidden")
            .append(" C$fields descriptor export.\n");
        line(cd.name() + "$fromJson = $rt.function("
            + jsStringLiteral("(string)->?" + identity) + ", "
            + "(s, $file, $line, $column) => $rt.jsonFromJson("
            + jsStringLiteral(identity) + ", " + cd.name() + "$fields, "
            + "null, s, $file, $line, $column));");
        line(cd.name() + "$toJson = $rt.function("
            + jsStringLiteral("(" + identity + ")->string") + ", "
            + "(v, $file, $line, $column) => $rt.jsonToJson("
            + jsStringLiteral(identity) + ", v, " + cd.name() + "$fields, "
            + "$file, $line, $column));");
        line(cd.name() + "$fields = "
            + jsonFieldsArray(cd.fields(), plan,
                cd.name() + "$plan") + ";");
    }

    /**
     * The pinned field-descriptor array (js-v12-jsonable-completion D2):
     * one entry per declared field in declaration order, one role per
     * key — {@code name}, {@code jtype}, the class/array sub-shapes
     * ({@code className}+{@code fields} / {@code element}), and the
     * declaration flags {@code optional}/{@code nullable}/
     * {@code hasDefault}.
     */
    private String jsonFieldsArray(List<ClassField> fields,
                                   CompilerClassDefaultPlan plan,
                                   String planBinding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(", ");
            CompilerClassDefaultEntry planEntry = plan == null
                ? null : plan.orderedFields().get(i);
            sb.append(jsonFieldEntry(fields.get(i), planEntry,
                planBinding, i, plan == null ? null
                    : identityText(plan.classIdentity())));
        }
        return sb.append("]").toString();
    }

    /**
     * One field-descriptor entry. The {@code nullable} flag is the
     * declaration flag ({@code ClassField.nullable()}); the
     * {@code jtype} derives from the declared type node with a
     * {@code T | null} unwrapped, the {@code className}/{@code fields}
     * pair carries the canonical identity text and the nested
     * descriptor array for class-typed fields, and {@code element}
     * carries the element entry for array-typed fields.
     *
     * <p>Plan-bearing entries additionally carry the published plan's
     * labelled zero-argument evaluator closure under {@code evaluator}
     * — a reference to the class's module-local {@code <C>$plan[i]}
     * entry, created at load and never invoked there (ISSUE-0545,
     * runtime page D1/D5): {@code $jsonFromDocument} invokes exactly
     * the omitted required entries once per attempt, in descriptor
     * order. Optional entries carry no evaluator key (a declared
     * default on an optional field never evaluates).</p>
     */
    private String jsonFieldEntry(ClassField cf,
                                  CompilerClassDefaultEntry planEntry,
                                  String planBinding, int planIndex,
                                  String planIdentityText) {
        TypeNode base = cf.type();
        if (base instanceof NullableType nt) {
            base = nt.innerType();
        }
        String jtype = jsonJType(base);
        StringBuilder sb = new StringBuilder("{ name: ")
            .append(jsStringLiteral(cf.name()))
            .append(", jtype: ").append(jsStringLiteral(jtype));
        appendJsonSubShape(sb, jtype, base);
        sb.append(", optional: ").append(cf.optional())
            .append(", nullable: ").append(cf.nullable())
            .append(", hasDefault: ").append(cf.defaultExpr().isPresent());
        if (planEntry != null && !planEntry.optional()) {
            String label = RuntimeDefaultPlanLowering.labelOf(
                planIdentityText, cf.name(),
                planEntry.defaultExpression().semanticDigest());
            sb.append(", evaluator: /* default evaluator ")
                .append(label).append(" */ ").append(planBinding)
                .append("[").append(planIndex).append("].evaluator");
        }
        return sb.append(" }").toString();
    }

    /**
     * The class/array sub-shape of one entry: a {@code class} jtype
     * carries {@code className} (the canonical identity text of the
     * resolved {@link Type.Class}) and {@code fields} (the nested
     * descriptor array emitted inline for same-module classes, the
     * imported module's {@code Lib.C$fields} export for cross-module
     * classes — js-v12-jsonable-completion D1); an {@code array} jtype
     * carries {@code element}.
     */
    private void appendJsonSubShape(StringBuilder sb, String jtype,
                                    TypeNode base) {
        switch (jtype) {
            case "class" -> {
                Type resolved = resolveTypeNode(base);
                if (resolved instanceof Type.Class cls) {
                    sb.append(", className: ")
                        .append(jsStringLiteral(descriptors.encode(cls)))
                        .append(", fields: ").append(jsonFieldsRef(cls));
                }
            }
            case "array" -> {
                if (base instanceof ArrayType at) {
                    sb.append(", element: ")
                        .append(jsonElementEntry(at.elementType()));
                }
            }
            default -> { }
        }
    }

    /**
     * One array-element entry (the Lua
     * {@code emitElementDescriptor} mirror): element entries carry no
     * {@code name}/{@code hasDefault} — the walkers read only
     * {@code jtype}/{@code element}/{@code className}/{@code fields}/
     * {@code optional}/{@code nullable} at element positions.
     */
    private String jsonElementEntry(TypeNode elementType) {
        boolean nullable = elementType instanceof NullableType;
        TypeNode inner = elementType;
        if (nullable) {
            inner = ((NullableType) elementType).innerType();
        }
        String jtype = jsonJType(inner);
        StringBuilder sb = new StringBuilder("{ jtype: ")
            .append(jsStringLiteral(jtype));
        appendJsonSubShape(sb, jtype, inner);
        sb.append(", optional: false, nullable: ").append(nullable);
        return sb.append(" }").toString();
    }

    /**
     * The jtype string for a type node after {@code T | null}
     * unwrapping (the {@code LuaBackend.jtypeForTypeNode} mirror,
     * deal/codegen/lua/LuaBackend.java:2737-2758): the JSON kind of a
     * field — primitives by name, user-defined classes (bare or
     * qualified) as {@code class}, arrays as {@code array}. The
     * {@code function} arm is defensive-only (the checker's E4007
     * rejects non-jsonable field types before the backend runs).
     */
    private String jsonJType(TypeNode typeNode) {
        return switch (typeNode) {
            case NamedType nt -> switch (nt.name()) {
                case "null" -> "null";
                case "boolean" -> "boolean";
                case "int" -> "int";
                case "number" -> "number";
                case "string" -> "string";
                case "table" -> "table";
                default -> "class";
            };
            case QualifiedType qt -> "class";
            case ArrayType at -> "array";
            case NullableType nt -> jsonJType(nt.innerType());
            case FunctionType ft -> "function";
        };
    }

    /**
     * The descriptor-array reference for a class-typed field: the
     * nested descriptor array emitted inline for a same-module class
     * (built from the class symbol's declared fields in declaration
     * order), the declaring module's exported {@code <alias>.<C>$fields}
     * for a cross-module class (js-v12-jsonable-completion D1 — the
     * imported module's {@code C$fields} export read at decode time).
     */
    private String jsonFieldsRef(Type.Class cls) {
        // The import alias whose export carries this exact identity wins
        // first: two files in one directory share the module identity,
        // so alias presence — never identity locality — distinguishes an
        // imported class.
        String alias = findImportAliasForClass(cls.name(), cls.identity());
        if (alias != null) {
            return jsName(alias) + "." + cls.name() + "$fields";
        }
        if (isIntrinsicError(cls) || isDeclaredInThisModule(cls)) {
            Symbol sym = symbols.resolve(cls.name());
            if (sym instanceof Symbol.ClassSymbol cs) {
                return jsonFieldsArray(cs.fields(), null, null);
            }
            return "[]";
        }
        return cls.name() + "$fields";
    }

    /**
     * The canonical runtime class identity for a class declared in this
     * module: the compilation's identity-index projection for this
     * module's identity and the class name, produced through the one
     * descriptor service (js-v12-completion-architecture D3) —
     * {@code @<configuredRootText>/<relativeModuleComponents>/<Name>} in
     * the real pipeline, {@code @<modulePath>/<Name>} under the
     * standalone single-module surface.
     */
    private String qualifiedClassName(String name) {
        return identityIndex.descriptorTextFor(localClassIdentity(name));
    }

    /**
     * The canonical class identity of a class declared in THIS module:
     * the module-identity layer's classification of the backend-held
     * module path plus the class name (v1.2 identity carriage) — never
     * a locally derived dotted spelling.
     */
    private CanonicalClassIdentity localClassIdentity(String name) {
        String mp = modulePath != null ? modulePath : sourcePath;
        CanonicalModuleIdentity moduleIdentity = moduleIdentities.apply(mp);
        if (moduleIdentity == null) {
            throw new IllegalStateException(
                "no canonical public module identity for module path '" + mp
                    + "': a class there can never be represented "
                    + "(internal invariant violation)");
        }
        return new CanonicalClassIdentity(moduleIdentity, name);
    }

    /** A Class type for a class declared in this module. */
    private Type.Class localClassType(String name) {
        return Types.classType(name, localClassIdentity(name));
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
     * True when the class type's identity declares in THIS module
     * (v1.2 identity carriage): same-module detection replaces the
     * retired dotted-path comparison.
     */
    private boolean isDeclaredInThisModule(Type.Class cls) {
        String mp = modulePath != null ? modulePath : sourcePath;
        CanonicalModuleIdentity mine = moduleIdentities.apply(mp);
        return mine != null
            && cls.identity().moduleIdentity().equals(mine);
    }

    /** True for the intrinsic builtin Error class type. */
    private boolean isIntrinsicError(Type.Class cls) {
        return cls.identity().moduleIdentity()
            .equals(CanonicalModuleIdentity.BuiltinModule.INSTANCE);
    }

    /**
     * The per-construction defaults thunk: a zero-arg closure returning a
     * computed-key object literal — every user-named key computed, so a
     * {@code __proto__} field name creates an own property and never
     * invokes the inherited accessor (js-backend-architecture D4) — with
     * every declared field present. Absent optional fields store
     * {@code $rt.MISSING}; default expressions evaluate fresh on every
     * construction (spec §Construction); the remaining required fields
     * carry their zero-value placeholders (dead entries — the checker's
     * E4001 requires every literal to provide them). Reached only when
     * the graph published no plan for the visited declaration (the
     * standalone entry points and checker-error programs): plan-bearing
     * classes construct through {@code $rt.classPlan} over the
     * published plan list instead (ISSUE-0545, {@link #visit(ClassDeclaration)}).
     */
    private String classDefaultsThunk(ClassDeclaration cd) {
        StringBuilder sb = new StringBuilder("() => ({");
        boolean first = true;
        for (ClassField field : cd.fields()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(" [").append(jsStringLiteral(field.name()))
                .append("]: ").append(fieldDefault(field));
        }
        sb.append(" })");
        return sb.toString();
    }

    /**
     * The published plan's runtime plan-list projection (ISSUE-0545,
     * runtime page D1/D2/D6): one entry per plan entry in class source
     * order with the pinned entry shape
     * {@code {name, descriptor, optional, evaluator?}} — the declared-
     * name set and the canonical descriptors {@code $rt.classPlan}
     * validates against. A required-present entry carries its labelled
     * zero-argument evaluator closure — created here at load, closing
     * over the declaring module's scope, never invoked during lowering
     * or load; {@code $rt.classPlan} invokes it exactly once per
     * omitted required entry per construction attempt (fresh mutable
     * literals, re-executed calls, function results retained by
     * reference). An optional entry carries no evaluator and NEVER
     * evaluates (a declared default on an optional field is
     * checker-validated metadata only, D1). Each evaluator label is a
     * JS block comment directly before the closure. The realized
     * {@link RuntimeClassDefaultPlan} carrier (digests, labels,
     * presence) is appended exactly once here; the @jsonable wrappers
     * reference the same per-entry closures through the descriptor
     * array's {@code evaluator} keys.
     */
    private String publishedPlanList(CompilerClassDefaultPlan plan) {
        String identityText = identityText(plan.classIdentity());
        List<RuntimeDefaultPlanLowering.EvaluatorRealization>
            realizations = new ArrayList<>();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < plan.orderedFields().size(); i++) {
            CompilerClassDefaultEntry entry = plan.orderedFields().get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{ name: ").append(jsStringLiteral(entry.name()))
                .append(", descriptor: ")
                .append(jsStringLiteral(entry.runtimeTypeDescriptor()))
                .append(", optional: ").append(entry.optional());
            if (!entry.optional()) {
                String label = RuntimeDefaultPlanLowering.labelOf(
                    identityText, entry.name(),
                    entry.defaultExpression().semanticDigest());
                String evaluator = "() => " + emitExpression(
                    entry.defaultExpression().expressionAst());
                sb.append(", evaluator: /* default evaluator ")
                    .append(label).append(" */ ").append(evaluator);
                realizations.add(new RuntimeDefaultPlanLowering
                    .EvaluatorRealization(label, evaluator,
                        artifactInvocationSeam(label)));
            }
            sb.append(" }");
        }
        sb.append("]");
        runtimePlans.add(RuntimeDefaultPlanLowering.realize(plan,
            identityText, realizations).plan());
        return sb.toString();
    }

    /**
     * The carrier-side zero-argument invocation seam of a generated JS
     * evaluator (ISSUE-0544/0545): the real evaluator is the generated
     * plan-entry closure — it executes inside the artifact exactly once
     * per omitted required entry per construction attempt — so an
     * in-process {@code invoke()} is a lowering-contract misuse and
     * raises. Nothing in the production pipeline invokes the seam
     * in-process; it carries the label so the misuse names its
     * evaluator.
     */
    private RuntimeDefaultEvaluator.Invocation artifactInvocationSeam(
            String label) {
        return () -> {
            throw new UnsupportedOperationException(
                "the JavaScript default evaluator " + label
                    + " executes inside the generated artifact; the"
                    + " carrier-side invocation seam is never invoked"
                    + " in-process");
        };
    }

    /**
     * The published compiler plan of this exact class declaration, or
     * {@code null} when the graph published no plan for it (host
     * declarations live outside plan space; the standalone entry points
     * pass no plan surface). Reference identity first — the planner
     * consumed the same AST the emitter walks — with the name/position
     * pair as the defensive fallback.
     */
    private CompilerClassDefaultPlan publishedPlanFor(
            ClassDeclaration cd) {
        List<PlannedDefaultClass> plans = plansByModulePath.get(modulePath);
        if (plans == null) {
            return null;
        }
        for (PlannedDefaultClass planned : plans) {
            ClassDeclaration declaration = planned.declaration();
            if (declaration == cd
                    || (declaration.name().equals(cd.name())
                        && declaration.span().startLine()
                            == cd.span().startLine()
                        && declaration.span().startColumn()
                            == cd.span().startColumn())) {
                return planned.plan();
            }
        }
        return null;
    }

    /**
     * The canonical descriptor text of a published plan's class
     * identity, through the compilation's one descriptor service.
     * Defensive: the planner only publishes plans for identities the
     * index registered.
     */
    private String identityText(CanonicalClassIdentity identity) {
        String text = identityIndex.descriptorTextFor(identity);
        if (text == null) {
            throw new IllegalStateException(
                "published default plan has no canonical identity text: "
                    + identity);
        }
        return text;
    }

    /**
     * One field's defaults-thunk entry (the
     * {@code LuaBackend.visit(ClassDeclaration)} order,
     * deal/codegen/lua/LuaBackend.java:1076-1096): absent optional →
     * {@code $rt.MISSING}; default expression → the expression verbatim
     * (its own checks travel inside — {@code $rt.intAdd}, the conversions,
     * the table/array literals); required nullable without default →
     * {@code null}; otherwise the type-node zero value.
     */
    private String fieldDefault(ClassField field) {
        // A declared default on an optional field is checker-validated
        // metadata that NEVER evaluates (provider-versioned-default-plans
        // D2, runtime page D1): an omitted optional field stays absent,
        // so every optional entry stores $rt.MISSING — declared default
        // or not.
        if (field.optional()) {
            return "$rt.MISSING";
        }
        if (field.defaultExpr().isPresent()) {
            return emitExpression(field.defaultExpr().get());
        }
        if (field.nullable()) {
            return "null";
        }
        return defaultValueForTypeNode(field.type());
    }

    /**
     * The JS zero value for a type node with no default expression (the
     * {@code LuaBackend.defaultValueForTypeNode} mirror): {@code 0},
     * {@code 0.0}, {@code false}, {@code ""}, {@code null}, a fresh empty
     * Map via {@code $rt.makeTable({})} for tables, a fresh {@code []}
     * for arrays, and {@code {}} for the remaining dead-entry shapes
     * (class-typed and function-typed required fields are provided at
     * every literal — checker E4001 — so their defaults entries are never
     * read).
     */
    private String defaultValueForTypeNode(TypeNode typeNode) {
        return switch (typeNode) {
            case NamedType nt -> switch (nt.name()) {
                case "int" -> "0";
                case "number" -> "0.0";
                case "boolean" -> "false";
                case "string" -> "\"\"";
                case "null" -> "null";
                case "table" -> "$rt.makeTable({})";
                default -> "{}";
            };
            case NullableType ignored -> "null";
            case ArrayType ignored -> "[]";
            case FunctionType ignored -> "{}";
            default -> "{}";
        };
    }

    /**
     * Variable declaration (the
     * {@code LuaBackend.visit(VariableDeclaration)} mirror): the binding
     * position translates through {@link #jsName} (shadowing is native
     * per-block {@code let}); an annotated declaration crosses the
     * initializer's typed boundary — a function-typed target whose
     * declared signature is assignably wider than the value's emits the
     * arity-extension adapter ({@link #boundaryValue}), every other
     * target the runtime check — an inferred literal skips the check,
     * and every other inferred initializer is checked against its
     * checker-inferred type at the declaration span.
     */
    private void visit(VariableDeclaration node) {
        // NameResolver.walkVarDecl defines the name in the current scope
        // BEFORE walking the initializer — the emitted JS `let` binding
        // scopes identically, so the tracking mirrors it.
        declareLocal(node.name());
        String name = jsName(node.name());
        // A checker-accepted var-before-function pair in this statement
        // list shares the hoisted function's predeclared binding (the
        // checker's walkFuncDecl skips the second define — one symbol):
        // the variable emits a plain assignment into that binding, the
        // Lua local-then-assign shape — a second `let` for the name
        // would be a strict-mode SyntaxError. Only the current list's
        // hoisted set counts: a same-named variable in a nested block
        // is a legal shadow and keeps its own `let`.
        boolean plainAssign = !hoistedFunctionNames.isEmpty()
            && hoistedFunctionNames.peek().contains(node.name());
        String keyword = plainAssign ? "" : "let ";
        boolean hasAnnotation = node.typeAnnotation().isPresent();
        Type targetType = hasAnnotation
            ? resolveTypeNode(node.typeAnnotation().get()) : null;
        Type exprType = typeOf(node.initializer());

        Span span = hasAnnotation
            ? node.typeAnnotation().get().span()
            : node.initializer().span();

        if (hasAnnotation && targetType != null
                && !(targetType instanceof Type.Error)) {
            // The annotated target crosses the initializer's typed
            // boundary; boundaryValue emits the initializer itself, so
            // an arity-extension adapter can splice the value text at
            // its exact generated position (js-v12-source-maps D2).
            line(keyword + name + " = "
                + boundaryValue(node.initializer(), targetType,
                    exprType, span) + ";");
        } else if (!hasAnnotation && exprType != null
                && node.initializer() instanceof LiteralExpr
                && (exprType instanceof Type.Int
                    || exprType instanceof Type.Boolean
                    || exprType instanceof Type.String
                    || exprType instanceof Type.Number
                    || exprType instanceof Type.Null)) {
            line(keyword + name + " = "
                + emitExpression(node.initializer()) + ";");
        } else {
            String init = emitExpression(node.initializer());
            Type checkType = targetType != null ? targetType : exprType;
            if (checkType != null
                    && !(checkType instanceof Type.Error)
                    && !(checkType instanceof Type.Null)) {
                line(keyword + name + " = "
                    + emitCheckExpr(init, checkType, span) + ";");
            } else {
                line(keyword + name + " = " + init + ";");
            }
        }
    }

    /**
     * Return statement: the expression crosses the declared return type
     * boundary at the return site (the wrapper's exit check); a bare
     * return in a {@code null}-typed function returns the DEAL null
     * ({@code null}), every other bare return is the defensive plain
     * form (checker E5002 already rejected missing returns elsewhere).
     */
    private void visit(ReturnStatement node) {
        if (node.expr().isPresent()) {
            String expr = emitExpression(node.expr().get());
            if (currentReturnType != null
                    && !(currentReturnType instanceof Type.Error)
                    && !(currentReturnType instanceof Type.Null)) {
                expr = emitCheckExpr(expr, currentReturnType,
                    node.expr().get().span());
            }
            line("return " + expr + ";");
        } else {
            line(currentReturnType instanceof Type.Null
                ? "return null;" : "return;");
        }
    }

    /**
     * If/else: the condition crosses a boolean boundary via
     * {@code $rt.checkBoolean} (the reference's condition check,
     * deal/codegen/lua/LuaBackend.java:1305-1346); every branch is a
     * brace scope, so branch-level shadowing is native.
     */
    private void visit(IfStatement node) {
        line("if ($rt.checkBoolean(" + emitExpression(node.condition())
            + ", " + spanArgs(node.condition().span()) + ")) {");
        indent++;
        walkScopedBlock(node.thenBlock().statements());
        indent--;
        if (node.elseBranch().isPresent()) {
            emitElseChain(node);
        } else {
            line("}");
        }
    }

    /**
     * The else-if / else chain: each {@code else if} condition crosses
     * the boolean boundary; the chain closes exactly once.
     */
    private void emitElseChain(IfStatement node) {
        switch (node.elseBranch().get()) {
            case Either.Left<IfStatement, Block> left -> {
                IfStatement elseIf = left.value();
                line("} else if ($rt.checkBoolean("
                    + emitExpression(elseIf.condition()) + ", "
                    + spanArgs(elseIf.condition().span()) + ")) {");
                indent++;
                walkScopedBlock(elseIf.thenBlock().statements());
                indent--;
                emitElseChain(elseIf);
            }
            case Either.Right<IfStatement, Block> right -> {
                line("} else {");
                indent++;
                walkScopedBlock(right.value().statements());
                indent--;
                line("}");
            }
        }
    }

    /**
     * While loop: the per-iteration condition crosses the boolean
     * boundary (mirroring the reference's condition check).
     */
    private void visit(WhileStatement node) {
        line("while ($rt.checkBoolean(" + emitExpression(node.condition())
            + ", " + spanArgs(node.condition().span()) + ")) {");
        indent++;
        walkScopedBlock(node.body().statements());
        indent--;
        line("}");
    }

    /**
     * C-style for: native JS semantics with a {@code let} head binding —
     * per-iteration fresh for closures, the condition/update see the
     * current iteration's binding, and the body scope can shadow the
     * loop variable exactly like the checker's scope model. A
     * let-declared init checks its initializer; the condition crosses
     * the boolean boundary; an absent condition is {@code true}.
     */
    private void visit(ForStatement node) {
        pushLocalScope();
        String initPart = "";
        if (node.init().isPresent()) {
            switch (node.init().get()) {
                case ForInit.VarDecl vd -> {
                    VariableDeclaration decl = vd.decl();
                    declareLocal(decl.name());
                    Type varType = decl.typeAnnotation().isPresent()
                        ? resolveTypeNode(decl.typeAnnotation().get()) : null;
                    String initExpr = emitExpression(decl.initializer());
                    Span initSpan = decl.typeAnnotation().isPresent()
                        ? decl.typeAnnotation().get().span()
                        : decl.initializer().span();
                    if (varType != null
                            && !(varType instanceof Type.Error)) {
                        initPart = "let " + jsName(decl.name()) + " = "
                            + emitCheckExpr(initExpr, varType, initSpan);
                    } else {
                        initPart = "let " + jsName(decl.name())
                            + " = " + initExpr;
                    }
                }
                case ForInit.AssignExpr ae ->
                    initPart = emitAssignment(ae.expr());
            }
        }
        String condPart = node.condition().isPresent()
            ? "$rt.checkBoolean("
                + emitExpression(node.condition().get()) + ", "
                + spanArgs(node.condition().get().span()) + ")"
            : "true";
        String updatePart = node.update().isPresent()
            ? emitExpression(node.update().get()) : "";
        line("for (" + initPart + "; " + condPart + "; " + updatePart
            + ") {");
        indent++;
        walkScopedBlock(node.body().statements());
        indent--;
        line("}");
        popLocalScope();
    }

    /**
     * For-of. Array iteration (js-backend-architecture D6): index-based
     * over {@code 0..length-1} via the generated {@code $i} loop
     * variable, the iterable hoisted to a generated {@code $iter} local
     * for single evaluation, the loop breaking before the first element
     * equal to the runtime nil-equivalent {@code $rt.undefined} (the
     * LuaJIT {@code ipairs} stop-at-first-nil semantics — a {@code null}
     * element is a value and iterates), and the fresh per-iteration
     * element binding as a {@code let} declared inside the loop body
     * (never {@code const} — a checker-accepted mutation of the element
     * variable must not throw). The body statements sit in a nested
     * block scope so a checker-accepted shadowing of the loop variable
     * stays a legal strict-mode redeclaration in a child scope. String
     * iteration walks one Unicode scalar value per step through
     * {@code $rt.scalars}.
     */
    private void visit(ForOfStatement node) {
        Type iterableType = typeOf(node.iterable());
        String varName = jsName(node.varName());
        line("{");
        indent++;
        pushLocalScope();
        line("const $iter = " + emitExpression(node.iterable()) + ";");
        declareLocal(node.varName());
        if (iterableType instanceof Type.Array) {
            line("for (let $i = 0; $i < $iter.length; $i++) {");
            indent++;
            line("if ($iter[$i] === $rt.undefined) { break; }");
            line("let " + varName + " = $iter[$i];");
            line("{");
            indent++;
            walkScopedBlock(node.body().statements());
            indent--;
            line("}");
            indent--;
            line("}");
        } else {
            line("for (let " + varName + " of $rt.scalars($iter)) {");
            indent++;
            walkScopedBlock(node.body().statements());
            indent--;
            line("}");
        }
        popLocalScope();
        indent--;
        line("}");
    }

    /**
     * Delete statement. Array element delete (js-backend-architecture
     * D6, the {@code LuaBackend.visit(DeleteStatement)} mirror,
     * deal/codegen/lua/LuaBackend.java:1696-1715): the array and the
     * {@code $rt.checkInt}-validated index hoist to generated locals, a
     * negative or beyond-length index raises E8002 "array index out of
     * bounds", an index equal to the length performs no write (a JS
     * write at index {@code length} would append and grow the array —
     * the reference's nil-write to a non-existent key is a no-op), and
     * any other index writes the runtime-captured nil-equivalent
     * {@code $rt.undefined}. Class field delete writes
     * {@code $rt.MISSING} via the own-property-safe {@code $rt.setProp};
     * table delete is the Map {@code .delete} call.
     */
    private void visit(DeleteStatement node) {
        if (node.target() instanceof IndexExpr idx) {
            Type containerType = typeOf(idx.array());
            if (containerType instanceof Type.Array) {
                line("{");
                indent++;
                line("const $arr = " + emitExpression(idx.array()) + ";");
                line("const $idx = $rt.checkInt("
                    + emitExpression(idx.index()) + ", "
                    + spanArgs(idx.span()) + ");");
                line("if ($idx < 0 || $idx > $arr.length) { "
                    + "$rt.fail(\"E8002\", \"array index out of bounds\", "
                    + spanArgs(idx.span()) + "); }");
                line("if ($idx !== $arr.length) { $arr[$idx] = $rt.undefined; }");
                indent--;
                line("}");
                return;
            }
            if (containerType instanceof Type.Table) {
                // Module-alias index delete (checker F5 accepts the
                // target): nil-out the export-table entry via
                // $rt.setProp with the nil-equivalent — the LuaJIT
                // lib[k] = nil semantics — never the Map .delete call
                // the plain object does not carry.
                if (idx.array() instanceof IdentifierExpr id
                        && isModuleAliasRef(id)) {
                    line("$rt.setProp(" + emitExpression(idx.array())
                        + ", " + emitExpression(idx.index())
                        + ", $rt.undefined);");
                    return;
                }
                line(emitExpression(idx.array()) + ".delete("
                    + emitExpression(idx.index()) + ");");
                return;
            }
        }
        if (node.target() instanceof MemberAccessExpr mae) {
            Type objType = typeOf(mae.object());
            if (mae.object() instanceof IdentifierExpr id
                    && isModuleAliasRef(id)) {
                // Module-member delete: nil-out the export entry via
                // $rt.setProp with the nil-equivalent (the LuaJIT
                // lib.x = nil semantics); a later read yields the
                // nil-equivalent exactly like the reference.
                line("$rt.setProp(" + emitExpression(mae.object()) + ", "
                    + jsStringLiteral(mae.field()) + ", $rt.undefined);");
                return;
            }
            if (objType instanceof Type.Class) {
                line("$rt.setProp(" + emitExpression(mae.object()) + ", "
                    + jsStringLiteral(mae.field()) + ", $rt.MISSING);");
                return;
            }
            if (objType instanceof Type.Table) {
                line(emitExpression(mae.object()) + ".delete("
                    + jsStringLiteral(mae.field()) + ");");
                return;
            }
        }
        // Defensive arm: unreachable for checker-accepted programs
        // (E3017 rejects array .length deletes, E4004 required class
        // fields); fails loudly rather than silently miscompiling.
        line("$rt.fail(\"E6000\", \"unsupported delete target\", "
            + spanArgs(node.span()) + ");");
    }

    /**
     * Try/catch (js-backend-runtime D7): native try/catch with a
     * generated {@code $e} raw catch parameter and the user binding
     * (jsName-translated) initialized from the total
     * {@code $rt.reifyError} conversion. Native JS control flow makes
     * the reference's return/break/continue-through-try machinery
     * unnecessary. Flow continues after the catch block natively.
     */
    private void visit(TryStatement node) {
        line("try {");
        indent++;
        walkScopedBlock(node.tryBlock().statements());
        indent--;
        line("} catch ($e) {");
        indent++;
        pushLocalScope();
        declareLocal(node.catchVar());
        line("const " + jsName(node.catchVar()) + " = $rt.reifyError($e);");
        // The catch body statements emit inside an additional block:
        // the checker's catch-block scope sits below the catch-binding
        // scope (walkTry), and the emitted block lets the per-scope
        // hoisted `let`s legally shadow the catch binding — a
        // checker-accepted nested function named like the catch
        // variable is a shadow in a child scope, never a strict-mode
        // duplicate of the `const` above.
        line("{");
        indent++;
        walkScopedBlock(node.catchBlock().statements());
        indent--;
        line("}");
        popLocalScope();
        indent--;
        line("}");
    }

    /**
     * Throw statement (js-backend-runtime D7, the
     * {@code LuaBackend.visit(ThrowStatement)} mirror,
     * deal/codegen/lua/LuaBackend.java:1852-1880): an Error literal emits
     * {@code $rt.errorValue} with the per-property {@code ""} default
     * filling (an omitted {@code code}/{@code message} passes the empty
     * string — the checker's E4002 already excludes extra fields), plus
     * the throw-site location arguments; every other Error-typed
     * expression — a rethrow {@code throw e;} included — emits the
     * non-literal {@code throw <expr>;} form verbatim (the thrown value
     * is already a tagged Error instance that {@code $rt.reifyError}
     * passes through unchanged).
     */
    private void visit(ThrowStatement node) {
        Span throwSpan = node.span();
        if (node.expr() instanceof ObjectLiteralExpr objLit) {
            String codeExpr = "\"\"";
            String messageExpr = "\"\"";
            for (Property prop : objLit.properties()) {
                if (prop.name().equals("code")) {
                    codeExpr = emitExpression(prop.value());
                } else if (prop.name().equals("message")) {
                    messageExpr = emitExpression(prop.value());
                }
            }
            line("throw $rt.errorValue(" + codeExpr + ", " + messageExpr
                + ", " + spanArgs(throwSpan) + ");");
        } else {
            line("throw " + emitExpression(node.expr()) + ";");
        }
    }

    /**
     * Bare block: a brace scope of its own, so per-block {@code let}
     * shadowing (the checker's Block scopes) stays legal strict-mode
     * code. Branch/loop/catch bodies walk their statements directly —
     * their braces already form the scope.
     */
    private void visit(Block node) {
        line("{");
        indent++;
        walkScopedBlock(node.statements());
        indent--;
        line("}");
    }

    /**
     * Walks a statement list directly (no braces): the caller owns the
     * enclosing scope. Every function declaration in the list binds a
     * predeclared {@code let} before any statement emits — the
     * {@code LuaBackend.walkStatements} hoisting loop
     * (deal/codegen/lua/LuaBackend.java:1005-1019), which supports
     * recursion, forward calls, and mutual recursion for nested
     * functions while retaining lexical scope (module-level predeclares
     * carry the header, shape step 6). The hoisting is collision-free
     * in the emitted JS scope: one {@code let} per distinct name (two
     * checker-accepted same-named functions in one list share the
     * binding — a duplicated {@code let} would be a strict-mode
     * SyntaxError), and the emitting caller owns the scope, so a
     * parameter/catch-binding shadow emits in a nested block (the
     * checker's block scopes) while a same-list var-before-function
     * pair shares the binding through a plain assignment
     * ({@link #hoistedFunctionNames}).
     *
     * <p>Every class declaration in the list (bare or export-wrapped)
     * predeclares its artifact pair {@code let <C>$new; let <C>$meta;}
     * at the top of this scope before any statement emits
     * (js-v12-completion-architecture D4 — the module-shape
     * predeclare-then-assign pattern applied per scope): construction
     * sites in the same list and later-declared functions resolve
     * through the hoisted bindings, and the declaration-site assignment
     * of {@link #visit(ClassDeclaration)} runs in source order. The
     * first-declaration-kind record mirrors the checker's define rule
     * (walkFuncDecl/walkClassDecl skip an already-defined name): when a
     * function or variable precedes a same-named class in one list, the
     * checker binds the earlier declaration, so the class name is not
     * registered for class-META resolution (the class artifacts still
     * emit); when the class comes first, its name registers and the
     * function name skips the variable-frame registration.
     */
    private void walkStatements(List<StatementNode> statements) {
        Set<String> hoisted = new LinkedHashSet<>();
        Set<String> hoistedClasses = new LinkedHashSet<>();
        Map<String, String> firstKind = new LinkedHashMap<>();
        Map<String, ClassDeclaration> classDeclarations =
            new LinkedHashMap<>();
        for (StatementNode stmt : statements) {
            switch (stmt) {
                case FunctionDeclaration fd -> {
                    hoisted.add(fd.name());
                    firstKind.putIfAbsent(fd.name(), "function");
                }
                case ClassDeclaration cd -> {
                    hoistedClasses.add(cd.name());
                    firstKind.putIfAbsent(cd.name(), "class");
                    classDeclarations.putIfAbsent(cd.name(), cd);
                }
                case ExportDeclaration ed -> {
                    switch (ed.declaration()) {
                        case FunctionDeclaration fd -> {
                            hoisted.add(fd.name());
                            firstKind.putIfAbsent(fd.name(), "function");
                        }
                        case ClassDeclaration cd -> {
                            hoistedClasses.add(cd.name());
                            firstKind.putIfAbsent(cd.name(), "class");
                            classDeclarations.putIfAbsent(cd.name(), cd);
                        }
                        default -> {
                            // Unreachable: ExportDeclaration wraps only
                            // function/class declarations.
                        }
                    }
                }
                case VariableDeclaration vd ->
                    firstKind.putIfAbsent(vd.name(), "variable");
                default -> { }
            }
        }
        for (String name : hoisted) {
            line("let " + jsName(name) + ";");
            if (!"class".equals(firstKind.get(name))) {
                declareLocal(name);
            }
        }
        for (String name : hoistedClasses) {
            // A plan-bearing class additionally predeclares its
            // scope-local plan binding (ISSUE-0545): the declaration
            // site assigns the plan list, and $rt.classPlan construction
            // sites in the same list resolve through the hoisted
            // binding, exactly like the $new/$meta pair.
            line("let " + name + "$new; let " + name + "$meta;"
                + (publishedPlanFor(classDeclarations.get(name)) != null
                    ? " let " + name + "$plan;"
                    : ""));
            if ("class".equals(firstKind.get(name))) {
                declareLocalClass(name);
            }
        }
        hoistedFunctionNames.push(hoisted);
        statementDepth++;
        try {
            for (StatementNode stmt : statements) {
                visitStatement(stmt);
            }
        } finally {
            statementDepth--;
            hoistedFunctionNames.pop();
        }
    }

    // =========================================================================
    // Lexical scope tracking (class-META disambiguation)
    // =========================================================================

    /**
     * Walks a statement list inside one fresh lexical scope, restoring
     * the scope stack afterwards — the checker gives every block, loop
     * body, and try/catch arm its own scope
     * ({@code NameResolver.walkBlock/walkFor/walkForOf/walkTry}), and
     * the emitted braces are JS block scopes, so a {@code let} declared
     * inside a branch must not shadow a module-level class reference
     * after the branch closes.
     */
    private void walkScopedBlock(List<StatementNode> statements) {
        pushLocalScope();
        try {
            walkStatements(statements);
        } finally {
            popLocalScope();
        }
    }

    private void pushLocalScope() {
        localScopes.add(new HashSet<>());
        localClassScopes.add(new HashSet<>());
    }

    private void popLocalScope() {
        localScopes.remove(localScopes.size() - 1);
        localClassScopes.remove(localClassScopes.size() - 1);
    }

    private void declareLocal(String name) {
        localScopes.get(localScopes.size() - 1).add(name);
    }

    /**
     * Registers a nested class name in the current scope frame
     * (js-v12-completion-architecture D4): a class-META reference in
     * expression position lowers to the scope-local {@code <C>$meta}
     * artifact. Only the checker-resolved first declaration of the name
     * in a statement list registers ({@link #walkStatements} gates the
     * call on the first-declaration kind).
     */
    private void declareLocalClass(String name) {
        localClassScopes.get(localClassScopes.size() - 1).add(name);
    }

    /**
     * The innermost frame index of {@code scopes} that binds
     * {@code name}, or -1 when no frame binds it (innermost frames are
     * last).
     */
    private static int innermostFrame(List<Set<String>> scopes,
                                      String name) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            if (scopes.get(i).contains(name)) {
                return i;
            }
        }
        return -1;
    }

    /** True when the identifier is bound by a declaration visible at
     * the current walk position (any frame of the scope stack). */
    private boolean isLocalName(String name) {
        for (int i = localScopes.size() - 1; i >= 0; i--) {
            if (localScopes.get(i).contains(name)) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Expression emission
    // =========================================================================

    private String emitExpression(ExpressionNode expr) {
        return switch (expr) {
            case LiteralExpr lit -> emitLiteral(lit);
            case IdentifierExpr id -> emitIdentifier(id);
            case BinaryExpr bin -> emitBinary(bin);
            case UnaryExpr un -> emitUnary(un);
            case CallExpr call -> emitCall(call);
            case MemberAccessExpr mae -> emitMemberAccess(mae);
            case IndexExpr idx -> emitIndex(idx);
            case ArrayLiteralExpr arr -> emitArrayLiteral(arr);
            case ObjectLiteralExpr obj -> emitObjectLiteral(obj);
            case FunctionExpr fe -> emitFunctionExpr(fe);
            case HasExpr has -> emitHas(has);
            case AssignmentExpr assign -> emitAssignment(assign);
            case AwaitExpression await -> emitAwait(await);
            case TemplateLiteralExpr tl -> emitTemplateLiteral(tl);
        };
    }

    /**
     * Await lowering (js-backend-architecture D5, js-backend-emitter
     * D6): {@code await E} emits the parenthesized awaited result
     * {@code (await <emitted callee>)}. The awaited callee is a
     * {@link CallExpr} (the parser enforces E1042, the checker E3013
     * against non-async callees), so its emission is the wrapper call
     * {@code <callee>.$f(<args>, <file>, <line>, <column>)} with the
     * literal call-site span arguments — a direct awaited call lowers
     * to {@code (await base.$f(<file>, <line>, <column>))} and an
     * awaited indirect function-typed value to {@code (await
     * g.$f(...))}. The parentheses make the emission position-aware:
     * {@code await} binds at unary precedence, so an unparenthesized
     * awaited result composed into a postfix position binds the
     * postfix to the Promise instead of the awaited value —
     * {@code (await getU()).name} must emit {@code (await
     * getU.$f(...)).name}, never {@code await getU.$f(...).name}
     * (which reads {@code .name} off the Promise before awaiting).
     * No completion check is emitted at the await site: the async
     * wrapper checks its declared return inside its own {@code async
     * function} body before the Promise resolves, so the completion
     * value crosses its typed boundary exactly once
     * (js-backend-runtime D6). Errors raised by the awaited operation
     * propagate natively at the await site (Promise rejection), and
     * evaluation before an await precedes evaluation after it — native
     * async semantics. The awaited value is not a source-language
     * value: the wrapper shape stays the only source-visible form.
     */
    private String emitAwait(AwaitExpression await) {
        return "(await " + emitExpression(await.callee()) + ")";
    }

    /**
     * True when the expression subtree contains an await outside every
     * nested {@link FunctionExpr} body — the awaited call must then be
     * evaluated at the enclosing async function's level, never inside a
     * generated non-async closure (node rejects the {@code await}
     * keyword inside non-async function bodies at load). Awaits inside
     * a function expression are lexically inside that expression's own
     * (async) body and are safe in non-async positions.
     */
    private boolean containsAwait(ExpressionNode expr) {
        return switch (expr) {
            case AwaitExpression a -> true;
            case FunctionExpr fe -> false;
            case BinaryExpr b ->
                containsAwait(b.left()) || containsAwait(b.right());
            case UnaryExpr u -> containsAwait(u.expr());
            case CallExpr c ->
                containsAwait(c.callee())
                    || c.args().stream().anyMatch(this::containsAwait);
            case MemberAccessExpr m -> containsAwait(m.object());
            case IndexExpr ix ->
                containsAwait(ix.array()) || containsAwait(ix.index());
            case ArrayLiteralExpr al ->
                al.elements().stream().anyMatch(this::containsAwait);
            case ObjectLiteralExpr ol -> ol.properties().stream()
                .anyMatch(p -> containsAwait(p.value()));
            case HasExpr h -> containsAwait(h.object());
            case AssignmentExpr as ->
                containsAwait(as.target()) || containsAwait(as.value());
            case TemplateLiteralExpr tl ->
                tl.parts().stream().anyMatch(this::containsAwait);
            default -> false;
        };
    }

    private String emitLiteral(LiteralExpr lit) {
        return switch (lit.value()) {
            case LiteralValue.NullLiteral n -> "null";
            case LiteralValue.BooleanLiteral b -> b.value() ? "true" : "false";
            case LiteralValue.IntLiteral i -> Long.toString(i.value());
            case LiteralValue.NumberLiteral n -> {
                double v = n.value();
                // No bare host-global spelling: NaN/Infinity emit as
                // IEEE-arithmetic expressions (the LuaBackend (0/0) /
                // (1/0) precedent).
                if (Double.isNaN(v)) yield "(0/0)";
                if (Double.isInfinite(v)) yield v > 0 ? "(1/0)" : "(-1/0)";
                yield Double.toString(v);
            }
            case LiteralValue.StringLiteral s -> jsStringLiteral(s.value());
        };
    }

    /**
     * Identifier reference: the binding-position translation (a variable
     * named {@code static}/{@code eval} binds and references as
     * {@code static$}/{@code eval$}). A class-symbol reference in
     * expression position (a class META as a value — checker-accepted,
     * spec §Export forms: exporting a class exports class metadata)
     * lowers to the {@code <C>$meta} artifact (the header-seeded
     * {@code Error$meta} for the builtin Error) — never a bare unbound
     * identifier, because the artifact binds only
     * {@code <C>$new}/{@code <C>$meta}. The innermost-frame comparison
     * between the variable scopes and the nested-class scopes
     * (js-v12-completion-architecture D4) mirrors the checker's lexical
     * resolution: a shadowing local (a function parameter or {@code let}
     * named like a class) wins in its own frame, a nested class wins
     * over an outer frame's binding, and a module-level class symbol
     * carries the fallback.
     */
    private String emitIdentifier(IdentifierExpr id) {
        String name = id.name();
        int variableFrame = innermostFrame(localScopes, name);
        int classFrame = innermostFrame(localClassScopes, name);
        if (classFrame >= 0
                && (variableFrame < 0 || classFrame >= variableFrame)) {
            return name + "$meta";
        }
        if (variableFrame >= 0) {
            return jsName(name);
        }
        if (symbols.resolve(name) instanceof Symbol.ClassSymbol) {
            return name + "$meta";
        }
        return jsName(name);
    }

    /**
     * Binary expressions. Checked int arithmetic routes through the
     * {@code $rt.intAdd}/{@code intSub}/{@code intMul}/{@code intDiv}/
     * {@code intMod}/{@code intPow} members with the operator-span
     * location (E8001/E8004/E8005/E8006 in the runtime, the check_int
     * ±Infinity-before-range order included); {@code number % number}
     * routes through the floored {@code $rt.numMod} while the remaining
     * number ops stay native IEEE; string ordering compares Unicode
     * scalar values via {@code $rt.strCompare}; equality and inequality
     * are native {@code ===}/{@code !==}; {@code &&}/{@code ||} are
     * native short-circuit. The two nullable special forms mirror the
     * reference's nil-aware equality byte-for-byte
     * (deal/codegen/lua/LuaBackend.java:2007-2030): a nullable operand
     * compared against a {@code null} literal, and two nullable operands
     * compared against each other, map the nil-equivalent values
     * ({@code undefined}/{@code null}/{@code MISSING}) through
     * {@code $rt.isNilEquivalent} so an out-of-bounds or deleted-element
     * read compares equal to DEAL null exactly like LuaJIT's nil.
     */
    private String emitBinary(BinaryExpr bin) {
        Type leftType = typeOf(bin.left());
        Type rightType = typeOf(bin.right());
        String left = emitExpression(bin.left());
        String right = emitExpression(bin.right());
        BinaryOp op = bin.op();
        String spanParam = spanArgs(bin.span());

        if (op == BinaryOp.ADD && leftType instanceof Type.String
                && rightType instanceof Type.String) {
            return "(" + left + " + " + right + ")";
        }

        if (leftType instanceof Type.Int && rightType instanceof Type.Int) {
            return switch (op) {
                case ADD -> "$rt.intAdd(" + left + ", " + right + ", "
                    + spanParam + ")";
                case SUB -> "$rt.intSub(" + left + ", " + right + ", "
                    + spanParam + ")";
                case MUL -> "$rt.intMul(" + left + ", " + right + ", "
                    + spanParam + ")";
                case DIV -> "$rt.intDiv(" + left + ", " + right + ", "
                    + spanParam + ")";
                case MOD -> "$rt.intMod(" + left + ", " + right + ", "
                    + spanParam + ")";
                case POW -> "$rt.intPow(" + left + ", " + right + ", "
                    + spanParam + ")";
                case EQ -> "(" + left + " === " + right + ")";
                case NEQ -> "(" + left + " !== " + right + ")";
                case LT -> "(" + left + " < " + right + ")";
                case LTE -> "(" + left + " <= " + right + ")";
                case GT -> "(" + left + " > " + right + ")";
                case GTE -> "(" + left + " >= " + right + ")";
                case AND -> "(" + left + " && " + right + ")";
                case OR -> "(" + left + " || " + right + ")";
            };
        }

        if (leftType instanceof Type.Number
                || rightType instanceof Type.Number) {
            return switch (op) {
                case ADD -> "(" + left + " + " + right + ")";
                case SUB -> "(" + left + " - " + right + ")";
                case MUL -> "(" + left + " * " + right + ")";
                case DIV -> "(" + left + " / " + right + ")";
                case MOD -> "$rt.numMod(" + left + ", " + right + ")";
                case POW -> "(" + left + " ** " + right + ")";
                case EQ -> "(" + left + " === " + right + ")";
                case NEQ -> "(" + left + " !== " + right + ")";
                case LT -> "(" + left + " < " + right + ")";
                case LTE -> "(" + left + " <= " + right + ")";
                case GT -> "(" + left + " > " + right + ")";
                case GTE -> "(" + left + " >= " + right + ")";
                case AND -> "(" + left + " && " + right + ")";
                case OR -> "(" + left + " || " + right + ")";
            };
        }

        // Nullable-vs-nullable equality: nil-equivalent pairs compare
        // equal (the reference's nil-aware form).
        if (leftType instanceof Type.Nullable
                && rightType instanceof Type.Nullable) {
            if (op == BinaryOp.EQ) {
                return "((" + left + " === " + right + ") || ("
                    + "$rt.isNilEquivalent(" + left + ") && "
                    + "$rt.isNilEquivalent(" + right + ")))";
            }
            if (op == BinaryOp.NEQ) {
                return "!((" + left + " === " + right + ") || ("
                    + "$rt.isNilEquivalent(" + left + ") && "
                    + "$rt.isNilEquivalent(" + right + ")))";
            }
        }

        // Nullable-vs-null equality: the null literal compares equal to
        // every nil-equivalent value.
        if (op == BinaryOp.EQ && isNullLiteral(bin.right())
                && leftType instanceof Type.Nullable) {
            return "$rt.isNilEquivalent(" + left + ")";
        }
        if (op == BinaryOp.EQ && isNullLiteral(bin.left())
                && rightType instanceof Type.Nullable) {
            return "$rt.isNilEquivalent(" + right + ")";
        }
        if (op == BinaryOp.NEQ && isNullLiteral(bin.right())
                && leftType instanceof Type.Nullable) {
            return "!$rt.isNilEquivalent(" + left + ")";
        }
        if (op == BinaryOp.NEQ && isNullLiteral(bin.left())
                && rightType instanceof Type.Nullable) {
            return "!$rt.isNilEquivalent(" + right + ")";
        }

        // String ordering: scalar-value order via the runtime helper (JS
        // relational operators order UTF-16 code units, which diverges
        // for supplementary characters).
        if (leftType instanceof Type.String
                && rightType instanceof Type.String) {
            return switch (op) {
                case LT -> "($rt.strCompare(" + left + ", " + right + ") < 0)";
                case LTE -> "($rt.strCompare(" + left + ", " + right + ") <= 0)";
                case GT -> "($rt.strCompare(" + left + ", " + right + ") > 0)";
                case GTE -> "($rt.strCompare(" + left + ", " + right + ") >= 0)";
                default -> "(" + left + " " + opSymbolJs(op) + " " + right + ")";
            };
        }

        return "(" + left + " " + opSymbolJs(op) + " " + right + ")";
    }

    private String opSymbolJs(BinaryOp op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*";
            case DIV -> "/"; case MOD -> "%"; case POW -> "**";
            case EQ -> "==="; case NEQ -> "!=="; case LT -> "<";
            case LTE -> "<="; case GT -> ">"; case GTE -> ">=";
            case AND -> "&&"; case OR -> "||";
        };
    }

    private boolean isNullLiteral(ExpressionNode expr) {
        return expr instanceof LiteralExpr lit
            && lit.value() instanceof LiteralValue.NullLiteral;
    }

    /**
     * Unary expressions: boolean {@code !} native; int negation routes
     * through the checked {@code $rt.intNeg}; number negation stays
     * native IEEE ({@code -x}, parenthesized only when the operand's
     * emission itself starts with {@code -} so no {@code --} token can
     * form).
     */
    private String emitUnary(UnaryExpr un) {
        String expr = emitExpression(un.expr());
        return switch (un.op()) {
            case NOT -> "(!" + expr + ")";
            case NEG -> {
                Type operandType = typeOf(un.expr());
                if (operandType instanceof Type.Int) {
                    yield "$rt.intNeg(" + expr + ", "
                        + spanArgs(un.span()) + ")";
                }
                // Never form the "--" token: the inner expression is
                // parenthesized after the negation sign, so a negated
                // negated operand emits "-(-x)" / "-(-5.0)" — plain
                // IEEE double negation, not a pre-decrement (or a
                // SyntaxError on literals).
                yield expr.startsWith("-") ? "-(" + expr + ")" : "-" + expr;
            }
        };
    }

    /**
     * Calls. The {@code int}/{@code number} conversion intrinsics route
     * through the header wrapper values ({@code int.$f(x, <file>, <line>,
     * <column>)} — the nullable-input overloads included: the runtime
     * raises E8001 "cannot convert null to int/number" on nil-equivalent
     * inputs inside {@code intConvert}/{@code numberConvert}); every
     * other function-typed callee — direct, indirect, or member — is a
     * wrapper whose {@code .$f} entry receives the call-site span
     * arguments (js-backend-emitter D6).
     */
    private String emitCall(CallExpr call) {
        Type calleeType = typeOf(call.callee());
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < call.args().size(); i++) {
            if (i > 0) args.append(", ");
            args.append(emitExpression(call.args().get(i)));
        }
        // The span triple joins the user arguments with a separator only
        // when user arguments exist: a zero-argument call must emit
        // ".$f(<file>, <line>, <column>)" — never the invalid
        // ".$f(, <file>, ...)" leading-comma form.
        StringBuilder callArgs = new StringBuilder();
        callArgs.append(args);
        if (args.length() > 0) {
            callArgs.append(", ");
        }
        callArgs.append(spanArgs(call.span()));
        // v1.2 bytes intrinsic (js-v12-int32-bytes D3): bytes(n) lowers
        // to $rt.bytes(n, <file>, <line>, <column>) directly — not
        // through the header wrapper's .$f entry (the task pins the
        // $rt.bytes call form; the header seed only serves first-class
        // bytes values). The guard mirrors the retired rejection's:
        // a checker-accepted shadowing local named bytes never routes
        // here.
        if (call.callee() instanceof IdentifierExpr id
                && id.name().equals("bytes")
                && !isLocalName(id.name())
                && symbols.resolve(id.name()) instanceof Symbol.IntrinsicSymbol) {
            return "$rt.bytes(" + callArgs + ")";
        }
        if (call.callee() instanceof IdentifierExpr id) {
            Symbol sym = symbols.resolve(id.name());
            if (sym instanceof Symbol.IntrinsicSymbol) {
                return emitExpression(call.callee()) + ".$f(" + callArgs
                    + ")";
            }
        }
        if (calleeType instanceof Type.Func) {
            return emitExpression(call.callee()) + ".$f(" + callArgs + ")";
        }
        // Defensive plain call: the checker rejects non-callable callees
        // (E3008), so only checker-error programs reach this form.
        return emitExpression(call.callee()) + "(" + args + ")";
    }

    /**
     * Member access. Array {@code .length} reads the native length;
     * class field reads are plain own-property reads (every declared
     * field is materialized as an own property at construction), with
     * optional-field reads through {@code $rt.optRead} (MISSING → null);
     * table field reads are Map {@code .get} calls (a missing key yields
     * the nil-equivalent {@code undefined} — the contextual target check
     * decides); module members (the import bindings, shape step 5) stay
     * plain property accesses. Property positions keep the raw name — a
     * field spelled {@code static}/{@code eval}/{@code __proto__} reads
     * as written.
     */
    private String emitMemberAccess(MemberAccessExpr mae) {
        String obj = emitExpression(mae.object());
        Type objType = typeOf(mae.object());
        String field = mae.field();
        if (field.equals("length") && objType instanceof Type.Array) {
            return obj + ".length";
        }
        // v1.2 bytes length (js-v12-int32-bytes D3/D4): b.length lowers
        // to $rt.bytesLength(b) of static type int — compiler-resolved,
        // never a member lookup, never dispatchable (the checker's E3017
        // rejects .length assignment/deletion on bytes before any
        // backend).
        if (field.equals("length") && objType instanceof Type.Bytes) {
            return "$rt.bytesLength(" + obj + ")";
        }
        // Module members: the alias identifier types as Table
        // (getDeclaredType of a ModuleSymbol) but its members are plain
        // export-table properties, never Map keys — the guard excludes
        // a checker-accepted local shadowing the alias (that local's
        // own type drives the read).
        if (mae.object() instanceof IdentifierExpr id
                && isModuleAliasRef(id)) {
            return obj + "." + field;
        }
        // A member access through a nullable class reference reads the
        // declared field of the inner class (the checker's cross-module
        // unwrap, TypeChecker.checkMemberAccess).
        Type classTarget = objType;
        if (objType instanceof Type.Nullable nullable
                && nullable.inner() instanceof Type.Class) {
            classTarget = nullable.inner();
        }
        if (classTarget instanceof Type.Class cls) {
            ClassField cf = findClassField(cls, field);
            if (cf != null) {
                return cf.optional()
                    ? "$rt.optRead(" + obj + "." + field + ")"
                    : obj + "." + field;
            }
            // Imported class field: the ClassSymbol of a cross-module
            // class is unreachable through CheckResult, but a
            // nullable-typed read may surface the MISSING sentinel of
            // an absent optional field — $rt.optRead maps it to DEAL
            // null, and is identity for required fields (they never
            // store MISSING), so the optional three-state holds for
            // imported classes exactly like local ones.
            if (typeOf(mae) instanceof Type.Nullable) {
                return "$rt.optRead(" + obj + "." + field + ")";
            }
            return obj + "." + field;
        }
        if (objType instanceof Type.Table) {
            return obj + ".get(" + jsStringLiteral(field) + ")";
        }
        return obj + "." + field;
    }

    /**
     * Looks up a class field declaration for the class type's symbol
     * (local classes and the seeded builtin Error). Imported classes
     * return {@code null}: their ClassSymbol lives in the declaring
     * module's symbol table and is unreachable through CheckResult, so
     * {@link #emitMemberAccess} derives the optional three-state from
     * the checker-recorded read type instead.
     */
    private ClassField findClassField(Type.Class cls, String field) {
        Symbol sym = symbols.resolve(cls.name());
        if (sym instanceof Symbol.ClassSymbol cs) {
            for (ClassField cf : cs.fields()) {
                if (cf.name().equals(field)) {
                    return cf;
                }
            }
        }
        return null;
    }

    /**
     * Index expression. Array reads emit the {@code $rt.checkInt} index
     * check, the E8002 "negative array index" fail for negative indexes,
     * and the raw element read — an out-of-bounds read yields the
     * nil-equivalent {@code undefined}, which the contextual target check
     * decides (nullable → DEAL null via {@code $rt.checkNullable};
     * non-nullable → E8001) (js-backend-architecture D3/D6). Table
     * reads are Map {@code .get} calls. The arrow IIFE evaluates the
     * index (checked) before the container — the exact
     * {@code LuaBackend.emitIndex} pinned sequence: evaluate the index,
     * run {@code checkInt}, raise E8002 for a negative index, and only
     * then evaluate the container and read
     * (deal/codegen/lua/LuaBackend.java:2116-2121). An await-bearing
     * container or index cannot land inside a non-async closure (node
     * rejects {@code await} in non-async function bodies at load), so
     * the await-bearing form keeps the operands inline inside an async
     * arrow IIFE that the enclosing level awaits — the body re-runs the
     * pinned sequence, so the awaited index runs and the E8002 gate
     * fires before an await-bearing container evaluates (the reference
     * raises E8002 before the container's side effects run), never
     * with hoisted IIFE arguments that would evaluate the container
     * before the gate.
     */
    private String emitIndex(IndexExpr idx) {
        Type containerType = typeOf(idx.array());
        String arr = emitExpression(idx.array());
        String index = emitExpression(idx.index());
        Span span = idx.span();
        if (containerType instanceof Type.Array) {
            boolean hasAwait = containsAwait(idx.array())
                || containsAwait(idx.index());
            if (!hasAwait) {
                return "(() => { const $idx = $rt.checkInt(" + index + ", "
                    + spanArgs(span) + "); if ($idx < 0) { "
                    + "$rt.fail(\"E8002\", \"negative array index\", "
                    + spanArgs(span) + "); } return " + arr + "[$idx]; })()";
            }
            return "(await (async () => { const $idx = $rt.checkInt("
                + index + ", " + spanArgs(span) + "); if ($idx < 0) { "
                + "$rt.fail(\"E8002\", \"negative array index\", "
                + spanArgs(span) + "); } return " + arr + "[$idx]; })())";
        }
        if (containerType instanceof Type.Table) {
            return arr + ".get(" + index + ")";
        }
        // v1.2 bytes read (js-v12-int32-bytes D4): b[i] lowers to
        // $rt.bytesGet(b, i, <file>, <line>, <column>) — JS evaluates
        // the receiver and the index (with their side effects) left to
        // right before the helper's internal checkBytes/checkInt/E8012
        // validation runs; the returned unsigned byte (int 0..255)
        // crosses the contextual target check like every read.
        if (containerType instanceof Type.Bytes) {
            return "$rt.bytesGet(" + arr + ", " + index + ", "
                + spanArgs(span) + ")";
        }
        return arr + "[" + index + "]";
    }

    private String emitArrayLiteral(ArrayLiteralExpr arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.elements().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(emitExpression(arr.elements().get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Object literal: a class-typed literal constructs through the
     * class's {@code $new} closure (the builtin {@code Error} through the
     * header {@code Error$new}); every other object literal is a table
     * literal constructed through the coordinated {@code $rt.makeTable}
     * member (js-backend-emitter D9) with computed keys — a
     * {@code __proto__} key creates an own property on the entries
     * object, never the inherited accessor; values evaluate in source
     * order, duplicate keys last-win.
     */
    private String emitObjectLiteral(ObjectLiteralExpr obj) {
        Type expectedType = typeOf(obj);
        if (expectedType instanceof Type.Class cls) {
            return emitClassConstruction(cls, obj);
        }
        return "$rt.makeTable(" + providedObject(obj) + ")";
    }

    /**
     * Class-typed literal construction (the
     * {@code LuaBackend.emitClassConstruction} mirror): the computed-key
     * provided object plus the literal-span location arguments to the
     * construction closure. Provided values carry their own
     * expression-site boundary checks (js-backend-runtime D5).
     */
    private String emitClassConstruction(Type.Class cls, ObjectLiteralExpr obj) {
        return constructionRef(cls) + "(" + providedObject(obj) + ", "
            + spanArgs(obj.span()) + ")";
    }

    /**
     * The construction closure reference for a class type: the header
     * {@code Error$new} for the builtin Error (module path empty), the
     * module-private {@code <C>$new} binding for a local class, and the
     * declaring module's exported {@code <alias>.<C>$new} for an
     * imported class (T4 materializes the alias binding).
     */
    private String constructionRef(Type.Class cls) {
        // The import alias whose export carries this exact identity wins
        // first (two files in one directory share the module identity).
        String alias = findImportAliasForClass(cls.name(), cls.identity());
        if (alias != null) {
            return jsName(alias) + "." + cls.name() + "$new";
        }
        if (isIntrinsicError(cls)) {
            return "Error$new";
        }
        if (isDeclaredInThisModule(cls)) {
            return cls.name() + "$new";
        }
        // Defensive fallback (the alias scan covers every
        // checker-accepted import; only checker-error programs reach
        // this form): the reference remains the declaring module's
        // exported closure.
        return cls.name() + "$new";
    }

    /**
     * Searches the module symbol table for a ModuleSymbol whose exports
     * include the class with the exact canonical class identity; returns
     * the import alias or {@code null} (the
     * {@code LuaBackend.findImportAliasForClass} mirror — v1.2 identity
     * carriage).
     */
    private String findImportAliasForClass(String className,
                                           CanonicalClassIdentity identity) {
        for (Map.Entry<String, Symbol> entry : symbols.symbols().entrySet()) {
            Symbol sym = entry.getValue();
            if (sym instanceof Symbol.ModuleSymbol ms) {
                Type exportType = ms.exports().get(className);
                if (exportType instanceof Type.Class tc
                        && tc.identity().equals(identity)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * The computed-key user-named object literal both construction forms
     * share: provided-field objects for class literals and entry objects
     * for table literals (the canonical user-keyed literal shape,
     * js-backend-emitter D5/D9).
     */
    private String providedObject(ObjectLiteralExpr obj) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Property prop : obj.properties()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(" [").append(jsStringLiteral(prop.name())).append("]: ")
                .append(emitExpression(prop.value()));
        }
        sb.append(" }");
        return sb.toString();
    }

    /**
     * Function expression (js-backend-emitter D6): the inline wrapper
     * value — {@code $rt.function} with the exact descriptor signature,
     * the jsName-translated parameter list plus the trailing
     * {@code $}-prefixed span parameters (duplicate-free for user
     * parameters literally named {@code file}/{@code line}/
     * {@code column}), the entry parameter checks with the forwarded
     * span, the body walk, the return-site exit checks, and the
     * validated {@code null} fall-off return for null-typed functions —
     * the identical wrapper shape of a function declaration. Closures
     * capture natively. Async function expressions emit the native
     * {@code async function} keyword with the declared-return check
     * inside the async body on every return path (the fall-off
     * {@code null} completion included) and the body's awaits lowered
     * through {@link #emitAwait} — the completion value crosses its
     * typed boundary exactly once, before the Promise resolves
     * (js-backend-architecture D5).
     */
    private String emitFunctionExpr(FunctionExpr fe) {
        Type funcType = typeOf(fe);
        String sig = funcType instanceof Type.Func f
            ? descriptors.encode(f) : "()";
        Type returnType = funcType instanceof Type.Func f
            ? f.returnType() : null;
        boolean isAsync = funcType instanceof Type.Func f && f.isAsync();

        // js-v12-source-maps D2: the inline wrapper emission records
        // one mapping at the wrapper site (the expression position in
        // the main buffer); the captured body's statement mappings
        // rebase through captureOutput.
        recordMapping(fe.span());

        Type savedReturn = currentReturnType;
        currentReturnType = returnType;

        String body = captureOutput(() -> {
            indent++;
            try {
                emitWrappedBody(fe.params(), fe.body(), returnType,
                    fe.returnType().span());
            } finally {
                indent--;
            }
        });

        currentReturnType = savedReturn;

        String keyword = isAsync ? "async function" : "function";
        String text = "$rt.function(\"" + sig + "\", " + keyword + "("
            + buildParamList(fe.params()) + ") {\n" + body
            + "  ".repeat(indent) + "})";
        // The wrapper text carries the captured body's lines into the
        // enclosing statement's in-memory assembly: count them all so
        // a later sibling on the same artifact line rebases past them
        // (the capture's own counter was discarded with the captured
        // buffer, so the body's newlines are counted here).
        inFlightNewlines += newlineCount(text);
        return text;
    }

    private String emitHas(HasExpr has) {
        return "$rt.has(" + emitExpression(has.object()) + ", "
            + jsStringLiteral(has.field()) + ")";
    }

    /**
     * Assignment expression. Array element writes emit the
     * {@code $rt.checkInt} index check, the E8002 bounds fail for
     * negative or beyond-length indexes, the element-value check against
     * the element descriptor, and the write (an index equal to the
     * length appends natively); class field writes route through the
     * own-property-safe {@code $rt.setProp}; table writes are Map
     * {@code .set} calls (unchecked, the checker's F5 write-context
     * rule); plain targets assign natively. The IIFE preserves the
     * reference's evaluation order (container, checked index, bounds,
     * checked value, write) in expression position. An await-bearing
     * container, index, or value cannot land inside a non-async
     * closure (node rejects {@code await} in non-async function bodies
     * at load), so the await-bearing form keeps the operands inline
     * inside an async arrow IIFE that the enclosing level awaits — the
     * body re-runs the pinned
     * {@code LuaBackend.emitAssignment} sequence
     * (deal/codegen/lua/LuaBackend.java:2358-2378): evaluate the
     * container, evaluate and {@code checkInt} the index, raise E8002
     * on the bounds gate, and only then evaluate and check the value —
     * the reference raises E8002 before an await-bearing value's side
     * effects run, never with hoisted IIFE arguments that would
     * evaluate the value before the gate.
     * Identifier targets and class field writes re-validate the
     * assigned value against the target's declared type at the
     * assignment site (the value's own boundary check for that
     * transition), mirroring the declaration-site boundary checks.
     */
    private String emitAssignment(AssignmentExpr assign) {
        Span span = assign.span();

        if (assign.target() instanceof IndexExpr idx) {
            Type containerType = typeOf(idx.array());
            if (containerType instanceof Type.Array arrT) {
                String arr = emitExpression(idx.array());
                String index = emitExpression(idx.index());
                String value = emitExpression(assign.value());
                boolean hasAwait = containsAwait(idx.array())
                    || containsAwait(idx.index())
                    || containsAwait(assign.value());
                if (!hasAwait) {
                    return "(() => { const $arr = " + arr
                        + "; const $idx = $rt.checkInt(" + index + ", "
                        + spanArgs(idx.span()) + "); "
                        + "if ($idx < 0 || $idx > $arr.length) { "
                        + "$rt.fail(\"E8002\", \"array index out of bounds\", "
                        + spanArgs(idx.span()) + "); } "
                        + "return $arr[$idx] = "
                        + emitCheckExpr(value, arrT.element(), span)
                        + "; })()";
                }
                return "(await (async () => { const $arr = " + arr
                    + "; const $idx = $rt.checkInt(" + index + ", "
                    + spanArgs(idx.span()) + "); "
                    + "if ($idx < 0 || $idx > $arr.length) { "
                    + "$rt.fail(\"E8002\", \"array index out of bounds\", "
                    + spanArgs(idx.span()) + "); } "
                    + "return $arr[$idx] = "
                    + emitCheckExpr(value, arrT.element(), span)
                    + "; })())";
            }
            if (containerType instanceof Type.Bytes) {
                // v1.2 bytes write (js-v12-int32-bytes D4): b[i] = v
                // lowers to $rt.bytesSet(b, i, v, <file>, <line>,
                // <column>). JS argument evaluation realizes the pinned
                // order exactly: the receiver and the index (with their
                // side effects) evaluate before the RHS, and the RHS
                // (with its side effects) evaluates before any write
                // validation — the helper's internal checkBytes/
                // checkInt(i)/E8012/checkInt(v)/E8013 gates run only
                // after all three operands completed. The call returns
                // the written unsigned value, so the assignment keeps
                // its expression value.
                return "$rt.bytesSet(" + emitExpression(idx.array())
                    + ", " + emitExpression(idx.index()) + ", "
                    + emitExpression(assign.value()) + ", "
                    + spanArgs(idx.span()) + ")";
            }
            if (containerType instanceof Type.Table) {
                // Module-alias index write (checker F5 accepts any
                // table-typed index target in write context, module
                // aliases included): the export-table mutation via
                // $rt.setProp, never the Map .set call the plain
                // object does not carry.
                if (idx.array() instanceof IdentifierExpr id
                        && isModuleAliasRef(id)) {
                    return "$rt.setProp(" + emitExpression(idx.array())
                        + ", " + emitExpression(idx.index()) + ", "
                        + emitExpression(assign.value()) + ")";
                }
                return emitExpression(idx.array()) + ".set("
                    + emitExpression(idx.index()) + ", "
                    + emitExpression(assign.value()) + ")";
            }
        }
        if (assign.target() instanceof MemberAccessExpr mae) {
            Type objType = typeOf(mae.object());
            // Module-member write: a checker-accepted mutation of the
            // export table (the LuaJIT plain-assignment semantics) —
            // own-property-safe via $rt.setProp with the raw key, the
            // assigned value re-validated against the declared export
            // type at the assignment site (the established
            // typed-boundary pattern).
            if (mae.object() instanceof IdentifierExpr id
                    && isModuleAliasRef(id)) {
                return "$rt.setProp(" + emitExpression(mae.object())
                    + ", " + jsStringLiteral(mae.field()) + ", "
                    + checkedAssignmentValue(assign.value(),
                        typeOf(assign.target()), typeOf(assign.value()),
                        span) + ")";
            }
            if (objType instanceof Type.Class) {
                return "$rt.setProp(" + emitExpression(mae.object()) + ", "
                    + jsStringLiteral(mae.field()) + ", "
                    + checkedAssignmentValue(assign.value(),
                        typeOf(assign.target()), typeOf(assign.value()),
                        span) + ")";
            }
            if (objType instanceof Type.Table) {
                return emitExpression(mae.object()) + ".set("
                    + jsStringLiteral(mae.field()) + ", "
                    + emitExpression(assign.value()) + ")";
            }
            return emitExpression(mae.object()) + "." + mae.field()
                + " = " + emitExpression(assign.value());
        }
        if (assign.target() instanceof IdentifierExpr id) {
            return jsName(id.name()) + " = "
                + checkedAssignmentValue(assign.value(),
                    typeOf(assign.target()), typeOf(assign.value()), span);
        }
        // Defensive plain form: unreachable for checker-accepted
        // programs (E3017 rejects array .length targets, the checker
        // rejects every other unsupported target shape).
        return emitExpression(assign.target()) + " = "
            + emitExpression(assign.value());
    }

    /**
     * The assignment-site re-validation of the assigned value against
     * the target's declared type (the checker records the target type in
     * the type map, so a dynamic value like a table read crossing into a
     * typed binding or field is checked exactly where the transition
     * happens): a {@code Type.Error}/{@code null} type and the unchecked
     * table target emit the value verbatim; every other target type
     * crosses the runtime typed boundary with the assignment-span
     * location — function targets via the exact-{@code $sig}
     * {@code checkType} E8010 check, with the arity-extension adapter
     * replacing it when the value's declared signature is assignably
     * narrower ({@link #boundaryValue}, the
     * {@code LuaBackend.emitArityAdapter} mirror).
     */
    private String checkedAssignmentValue(ExpressionNode valueNode,
                                          Type targetType, Type valueType,
                                          Span span) {
        if (targetType == null || targetType instanceof Type.Error
                || targetType instanceof Type.Table) {
            return emitExpression(valueNode);
        }
        return boundaryValue(valueNode, targetType, valueType, span);
    }

    /**
     * The value emission for a typed transition (declaration-site,
     * assignment-site): the node is emitted here — never pre-emitted by
     * the caller — so the arity-extension adapter can place the value
     * at its exact splice position (js-v12-source-maps D2). An
     * arity-extension pair — a function value whose declared signature
     * is assignable to the target but declares fewer parameters —
     * emits the adapter closure (js-backend-emitter D6); every other
     * value crosses the runtime typed-boundary check —
     * function targets compare the wrapper's {@code $sig} against the
     * expected descriptor exactly, so a wrong-signature wrapper surfaces
     * the E8010 mismatch from the runtime {@code checkType} function
     * branch (js-backend-runtime D3).
     */
    private String boundaryValue(ExpressionNode valueNode,
                                 Type targetType, Type valueType,
                                 Span span) {
        if (targetType instanceof Type.Func tf
                && valueType instanceof Type.Func vf
                && isArityExtension(vf, tf)) {
            return emitArityAdapter(tf, vf, valueNode,
                containsAwait(valueNode), span);
        }
        return emitCheckExpr(emitExpression(valueNode), targetType, span);
    }

    /**
     * Arity-extension detection (the
     * {@code LuaBackend.isArityExtension} mirror): a function value whose
     * checker-accepted transition to a wider function target needs an
     * adapter — assignable but not equal, with fewer declared parameters
     * than the target.
     */
    private boolean isArityExtension(Type valueType, Type targetType) {
        if (valueType instanceof Type.Func vf
                && targetType instanceof Type.Func tf) {
            return Types.isAssignable(vf, tf) && !Types.equals(vf, tf)
                && vf.paramTypes().size() < tf.paramTypes().size();
        }
        return false;
    }

    /**
     * The arity-extension adapter closure (js-backend-emitter D6, the
     * {@code LuaBackend.emitArityAdapter} mirror with the JS span
     * plumbing): a {@code $rt.function} wrapper carrying the TARGET
     * descriptor signature, whose body checks every extended parameter
     * in order (generated {@code $p0}/{@code $p1}/… bindings,
     * creation-site span — the Lua adapter convention), drops the
     * extras, and calls the inner wrapper's {@code .$f} with the
     * overlapping arguments plus the forwarded {@code $file}/
     * {@code $line}/{@code $column} span. The sync return check
     * validates the inner result against the target return type at the
     * creation site; an async adapter returns the inner operation
     * untouched — the inner async body's own declared-return check is
     * the completion boundary (the arity-extension return types match
     * exactly, so the inner check validates the target return type),
     * and the awaiting caller observes the inner Promise, so the
     * completion value crosses its typed boundary exactly once
     * (js-backend-runtime D6). The value expression is evaluated per
     * adapter call like the reference, which embeds
     * {@code <valueLua>.f(...)} inside the per-call closure
     * (deal/codegen/lua/LuaBackend.java:2405-2420 — the compiled Lua
     * re-awaits per call). An await-bearing inner value re-awaits on
     * every call through an {@code async function} body carrying the
     * inlined value expression, with the wrapper {@code $sig} staying
     * the target descriptor; node rejects {@code await} in non-async
     * function bodies, so only an async body can host the inlined
     * await. A sync target keeps the creation-time capture
     * {@code (($fn) => <adapter>)(<valueExpr>)}: its {@code $f} must
     * stay sync under the sync {@code $sig} (a Promise returned by a
     * sync-sig wrapper would leak the async operation through a sync
     * call site — js-backend-architecture D5), and a sync JS function
     * cannot suspend, so the awaited value evaluates once at the
     * creation site — the source-level assignment semantics.
     */
    private String emitArityAdapter(Type.Func targetFunc, Type.Func valueFunc,
                                    ExpressionNode valueNode,
                                    boolean valueHasAwait,
                                    Span span) {
        boolean asyncBody = valueHasAwait && targetFunc.isAsync();
        // The adapter's structural prefix: the wrapper-opening line
        // plus one extended-parameter check line per target parameter.
        // The embedded value text lands on the adapter's return line,
        // exactly prefixNewlines lines below the enclosing statement's
        // line; the sync-target await-bearing form appends the value
        // after the adapter's closing, one structural line lower (its
        // return line). The value is emitted at that splice position
        // (js-v12-source-maps D2 exactness) so its wrapper and
        // captured-body mappings rebase past the adapter's structural
        // lines to the lines carrying their own generated code —
        // emitting it at the plain statement position would pin them
        // to the adapter's opening/check lines instead.
        int prefixNewlines = 1 + targetFunc.paramTypes().size();
        int valueShift = asyncBody || !valueHasAwait
            ? prefixNewlines : prefixNewlines + 1;
        String valueExpr = emitExpressionAtOffset(valueShift, valueNode);
        String innerRef = asyncBody ? valueExpr
            : (valueHasAwait ? "$fn" : valueExpr);
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < targetFunc.paramTypes().size(); i++) {
            if (i > 0) params.append(", ");
            params.append("$p").append(i);
        }
        if (params.length() > 0) params.append(", ");
        params.append("$file, $line, $column");

        StringBuilder sb = new StringBuilder();
        sb.append("$rt.function(")
            .append(jsStringLiteral(descriptors.encode(targetFunc)))
            .append(", ")
            .append(asyncBody ? "async function(" : "function(")
            .append(params).append(") {\n");
        String bodyIndent = "  ".repeat(indent + 1);
        for (int i = 0; i < targetFunc.paramTypes().size(); i++) {
            sb.append(bodyIndent)
                .append(emitCheckExpr("$p" + i,
                    targetFunc.paramTypes().get(i), span))
                .append(";\n");
        }
        String innerCall = adapterInnerCall(innerRef,
            valueFunc.paramTypes().size());
        if (targetFunc.isAsync()) {
            sb.append(bodyIndent).append("return ").append(innerCall)
                .append(";\n");
        } else {
            sb.append(bodyIndent).append("return ")
                .append(emitCheckExpr(innerCall,
                    targetFunc.returnType(), span))
                .append(";\n");
        }
        sb.append("  ".repeat(indent)).append("})");
        String adapter = sb.toString();
        String result = (valueHasAwait && !asyncBody)
            ? "(($fn) => " + adapter + ")(" + valueExpr + ")"
            : adapter;
        // The adapter text carries its own body lines (the
        // wrapper-prefix newline, one entry-check line per target
        // parameter, and the return line) into the enclosing
        // statement's in-memory assembly; the embedded value
        // expression's newlines were already counted when the value was
        // emitted, so only the net new-line contribution lands in the
        // counter — exact for both forms (the sync-target await-bearing
        // form appends the value expression after the adapter, so its
        // lines count here too).
        inFlightNewlines += newlineCount(result) - newlineCount(valueExpr);
        return result;
    }

    /**
     * The adapter's inner {@code .$f} call: the overlapping leading
     * arguments plus the forwarded span triple; a zero-parameter inner
     * wrapper emits {@code .$f($file, $line, $column)} — never the
     * invalid leading-comma form.
     */
    private String adapterInnerCall(String valueExpr, int overlapCount) {
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < overlapCount; i++) {
            if (i > 0) args.append(", ");
            args.append("$p").append(i);
        }
        StringBuilder callArgs = new StringBuilder();
        callArgs.append(args);
        if (args.length() > 0) callArgs.append(", ");
        callArgs.append("$file, $line, $column");
        return valueExpr + ".$f(" + callArgs + ")";
    }

    /**
     * Template literal lowering (the JVM concatenation approach,
     * js-backend-architecture D6): string parts concatenate with the
     * interpolated expressions via native string {@code +}; empty string
     * parts are skipped (the {@code LuaBackend} precedent); a template
     * with no interpolations is the plain string literal.
     */
    private String emitTemplateLiteral(TemplateLiteralExpr tl) {
        List<ExpressionNode> parts = tl.parts();
        if (parts.size() == 1) {
            return emitExpression(parts.get(0));
        }
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        for (int i = 0; i < parts.size(); i++) {
            ExpressionNode part = parts.get(i);
            if (i % 2 == 0) {
                if (part instanceof LiteralExpr lit
                        && lit.value() instanceof LiteralValue.StringLiteral s
                        && s.value().isEmpty()) {
                    continue;
                }
                if (!first) sb.append(" + ");
                sb.append(emitExpression(part));
                first = false;
            } else {
                if (!first) sb.append(" + ");
                sb.append(emitExpression(part));
                first = false;
            }
        }
        if (first) {
            // Every part was an empty string part.
            return "\"\"";
        }
        sb.append(")");
        return sb.toString();
    }

    // =========================================================================
    // Emission helpers
    // =========================================================================

    /**
     * Appends one indented source line to the accumulator. This is the
     * flush point for assembled expression text: once the statement
     * (any embedded function-expression body lines included) lands in
     * the buffer, the current level's in-flight newline count is
     * cleared — {@link #currentGeneratedLine} then reports the true
     * position for the next statement group.
     */
    private void line(String s) {
        out.append("  ".repeat(indent)).append(s).append("\n");
        inFlightNewlines = 0;
    }

    /**
     * Emits an expression whose text will be spliced
     * {@code extraLines} lines below the enclosing statement's current
     * assembly line (js-v12-source-maps D2 exactness): the
     * arity-extension adapter assembles its structural prefix
     * (wrapper-opening and extended-parameter check lines) before the
     * embedded value text lands, so the value's recorded mappings must
     * rebase past those lines. The emission runs against a fresh
     * scratch buffer with the capture offset placed at the future
     * splice line, so every mapping the value records
     * (function-expression wrappers and their captured bodies, nested
     * siblings included) lands on the line carrying its own generated
     * code; the returned text and the accumulated in-flight newline
     * count then join the enclosing statement's assembly exactly like
     * a plain {@link #emitExpression} call (the adapter adds only the
     * net new-line contribution of its structural text).
     * {@code extraLines} must be &ge; 1 (the adapter's structural
     * prefix always carries at least its wrapper-opening line), which
     * keeps the capture offset non-zero so {@link #recordMapping}
     * applies the rebase.
     */
    private String emitExpressionAtOffset(int extraLines,
                                          ExpressionNode node) {
        StringBuilder savedOut = out;
        int savedOffset = captureLineOffset;
        int savedInFlight = inFlightNewlines;
        int spliceLine = currentGeneratedLine() + savedInFlight
            + extraLines;
        out = new StringBuilder();
        captureLineOffset = savedOffset + spliceLine - 1;
        inFlightNewlines = 0;
        try {
            String text = emitExpression(node);
            savedInFlight += inFlightNewlines;
            return text;
        } finally {
            out = savedOut;
            captureLineOffset = savedOffset;
            inFlightNewlines = savedInFlight;
        }
    }

    /**
     * Captures the output of a sub-emission (function-expression bodies)
     * into a returned string, restoring the accumulator and indent
     * afterwards (the {@code LuaBackend.captureOutput} pattern).
     */
    private String captureOutput(Runnable action) {
        StringBuilder saved = out;
        int savedIndent = indent;
        int savedOffset = captureLineOffset;
        int savedInFlight = inFlightNewlines;
        // The captured text is spliced after a prefix ending in a
        // newline, so its first line lands on the artifact line after
        // the outer buffer's current line: rebase mappings recorded
        // inside the capture (js-v12-source-maps D2 exactness). The
        // rebase also adds the enclosing level's in-flight newlines —
        // expression text already assembled on the current statement's
        // line but not yet appended to the outer buffer (an earlier
        // sibling function expression's body), which shifts the splice
        // position down by that many lines. The capture gets its own
        // fresh in-flight count: its statements flush into the captured
        // buffer through {@link #line}, so sibling function expressions
        // inside the capture rebase against each other the same way.
        int currentLine = currentGeneratedLine() + inFlightNewlines;
        out = new StringBuilder();
        captureLineOffset = savedOffset + currentLine;
        inFlightNewlines = 0;
        try {
            action.run();
            return out.toString();
        } finally {
            out = saved;
            indent = savedIndent;
            captureLineOffset = savedOffset;
            inFlightNewlines = savedInFlight;
        }
    }

    /**
     * Returns the current 1-based line number in the output buffer
     * (the {@code LuaBackend.currentGeneratedLine} mechanics,
     * deal/codegen/lua/LuaBackend.java:800-806).
     */
    private int currentGeneratedLine() {
        int line = 1;
        for (int i = 0; i < out.length(); i++) {
            if (out.charAt(i) == '\n') line++;
        }
        return line;
    }

    /** The number of {@code '\n'} characters in the given assembled
     * text — the line shift the text contributes once spliced into the
     * output buffer. */
    private static int newlineCount(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

    /**
     * Returns the current 1-based column number in the output buffer
     * (the {@code LuaBackend.currentGeneratedColumn} mechanics).
     */
    private int currentGeneratedColumn() {
        int lastNewline = out.lastIndexOf("\n");
        if (lastNewline == -1) return out.length() + 1;
        return out.length() - lastNewline;
    }

    /**
     * Records a source mapping for the given AST span at the current
     * output position (js-v12-source-maps D2): the generated position
     * is computed from the emitter's output-buffer state, mapped to
     * the span's start line/column. Inside a {@link #captureOutput}
     * splice the position rebases through {@link #captureLineOffset}
     * so captured-body mappings stay exact in the final artifact.
     */
    private void recordMapping(Span span) {
        if (sourceMapGenerator == null || span == null) return;
        // The current level's in-flight newlines shift the recorded
        // position past sibling expression text already assembled on
        // the current line (js-v12-source-maps D2 exactness); inside a
        // capture the position additionally rebases through the splice
        // offset.
        int generatedLine = currentGeneratedLine() + inFlightNewlines;
        if (captureLineOffset != 0) {
            generatedLine = captureLineOffset + generatedLine;
        }
        sourceMapGenerator.emitStatement(generatedLine,
            currentGeneratedColumn(), span);
    }

    /**
     * The literal {@code .deal} source-location argument triple every
     * check and {@code .$f} call forwards: the span's file (an absolute
     * path when compiled through the production CLI), its 1-based start
     * line, and its 1-based start column (js-backend-architecture D8).
     */
    private String spanArgs(Span span) {
        if (span == null) {
            return "void 0, void 0, void 0";
        }
        return jsStringLiteral(span.file()) + ", "
            + span.startLine() + ", " + span.startColumn();
    }

    private Type typeOf(ExpressionNode expr) {
        Type t = typeMap.get(expr);
        if (t == null && expr instanceof LiteralExpr lit) {
            return literalType(lit);
        }
        return t;
    }

    private Type literalType(LiteralExpr lit) {
        return switch (lit.value()) {
            case LiteralValue.NullLiteral n -> Type.Null.INSTANCE;
            case LiteralValue.BooleanLiteral b -> Type.Boolean.INSTANCE;
            case LiteralValue.IntLiteral i -> Type.Int.INSTANCE;
            case LiteralValue.NumberLiteral n -> Type.Number.INSTANCE;
            case LiteralValue.StringLiteral s -> Type.String.INSTANCE;
        };
    }

    /**
     * The runtime typed-boundary check for a value crossing into a
     * declared type: the primitive members ({@code $rt.checkNull}/
     * {@code checkBoolean}/{@code checkInt}/{@code checkNumber}/
     * {@code checkString}/{@code checkTable}), the descriptor-driven
     * {@code $rt.checkArray}/{@code checkNullable}/{@code checkType}
     * members, and the literal source location (js-backend-runtime D3).
     * {@link Type.Error} values pass through unchecked (the checker has
     * already failed the module).
     */
    private String emitCheckExpr(String valueExpr, Type type, Span span) {
        if (type == null) return valueExpr;
        return emitCheckExprCore(valueExpr, type, spanArgs(span));
    }

    /**
     * The wrapper-entry variant of the typed-boundary check: the span
     * arguments are the forwarded {@code $file}/{@code $line}/
     * {@code $column} parameters, so a parameter error reports the call
     * site (js-backend-runtime D6).
     */
    private String emitCheckExprForwarded(String valueExpr, Type type) {
        if (type == null) return valueExpr;
        return emitCheckExprCore(valueExpr, type, "$file, $line, $column");
    }

    private String emitCheckExprCore(String valueExpr, Type type,
                                     String spanParam) {
        return switch (type) {
            case Type.Null ignored ->
                "$rt.checkNull(" + valueExpr + ", " + spanParam + ")";
            case Type.Boolean ignored ->
                "$rt.checkBoolean(" + valueExpr + ", " + spanParam + ")";
            case Type.Int ignored ->
                "$rt.checkInt(" + valueExpr + ", " + spanParam + ")";
            case Type.Number ignored ->
                "$rt.checkNumber(" + valueExpr + ", " + spanParam + ")";
            case Type.String ignored ->
                "$rt.checkString(" + valueExpr + ", " + spanParam + ")";
            case Type.Table ignored ->
                "$rt.checkTable(" + valueExpr + ", " + spanParam + ")";
            case Type.Bytes ignored ->
                "$rt.checkBytes(" + valueExpr + ", " + spanParam + ")";
            case Type.Error ignored -> valueExpr;
            case Type.Array arr ->
                "$rt.checkArray(\"" + descriptors.encode(type) + "\", "
                    + valueExpr + ", " + spanParam + ")";
            case Type.Nullable n ->
                "$rt.checkNullable(\"" + descriptors.encode(n.inner()) + "\", "
                    + valueExpr + ", " + spanParam + ")";
            case Type.Class cls ->
                "$rt.checkType(\"" + descriptors.encode(cls) + "\", "
                    + valueExpr + ", " + spanParam + ")";
            case Type.Func f ->
                "$rt.checkType(\"" + descriptors.encode(f) + "\", "
                    + valueExpr + ", " + spanParam + ")";
        };
    }

    /**
     * A JS double-quoted string literal: backslash, quote, the C-style
     * escapes, control characters, and U+2028/U+2029 escape; every other
     * code unit (surrogate pairs included) passes through — valid JS
     * source, round-trip exact.
     */
    private static String jsStringLiteral(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
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
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
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
