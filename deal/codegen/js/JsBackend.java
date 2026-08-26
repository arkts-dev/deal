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
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.types.Type;
import deal.types.Types;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JavaScript backend: the typed-AST-walking CommonJS emitter
 * (js-backend-architecture D2-D8, js-backend-emitter).
 *
 * <p>ISSUE-0247 core slice: the {@link #generate} seam with the
 * {@link JsCodegenResult} shape, the canonical module skeleton (shape
 * steps 1-9, with steps 5-8 structurally empty — later slices populate
 * the import bindings, the predeclared {@code let}s, the declarations,
 * and the export assignments), the {@link #jsName} binding-position
 * translation and {@link #jsTypeDescriptor} descriptor foundations, the
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
 * {@code jsTypeDescriptor} signature, the entry parameter checks in
 * parameter order with the forwarded {@code $file}/{@code $line}/
 * {@code $column} span (parameter errors report the call site), the
 * return-site exit checks on every {@code return} path plus the
 * validated {@code null} fall-off return for null-typed functions, the
 * trailing {@code $}-prefixed span parameters (duplicate-free for user
 * parameters literally named {@code file}/{@code line}/{@code column}),
 * direct/nested/indirect/member calls through {@code .$f} with the
 * literal call-site span arguments, recursion/forward calls/mutual
 * recursion through the predeclare-then-assign pattern (module shape
 * step 6 plus per-scope {@code let} hoisting for nested functions),
 * native closure capture, function values crossing function-typed
 * boundaries through the runtime's exact-{@code $sig} E8010 check, and
 * arity-extension adapters (a wider function-typed declaration or
 * assignment context wraps the value in an adapter closure that checks
 * the extended parameters, drops the extras, and calls the inner
 * {@code .$f} with the forwarded span — the {@code deal/runtime.lua}
 * adapter precedent). The header {@code int}/{@code number} wrapper
 * values are first-class function values with no special casing.
 *
 * <p>Cluster staging (js-backend-emitter D1): import declarations and
 * the export-assignment section remain tolerated structurally at this
 * slice — no binding/export emission, no diagnostic — so T4 (modules)
 * populates those sections; an export-wrapped function declaration
 * emits its wrapper here. Async completion checks and await lowering
 * are T5's; this slice emits the {@code async function} keyword
 * structurally for async declarations and leaves the await-site
 * lowering untouched.
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
     * generated CommonJS source, and any backend diagnostics.
     * {@link #hasErrors()} gates the artifact (the
     * {@code JvmCodegenResult} shape,
     * deal/codegen/jvm/JvmBackend.java:605-618, with {@code modulePath} in
     * place of {@code className}).
     */
    public record JsCodegenResult(String modulePath, String source,
                                  List<CompilerDiagnostic> diagnostics) {
        public JsCodegenResult {
            java.util.Objects.requireNonNull(modulePath, "modulePath must not be null");
            java.util.Objects.requireNonNull(source, "source must not be null");
            diagnostics = List.copyOf(diagnostics);
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
     * The ECMAScript reserved words that are not DEAL keywords, plus the
     * strict-mode binding-restricted names {@code eval} and
     * {@code arguments} (js-backend-architecture D4):
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
        "static", "eval", "arguments");

    /**
     * Translates a user identifier for a JS binding position: appends
     * {@code $} exactly when the identifier cannot appear as a strict-mode
     * binding name (the fixed word list above); every other identifier
     * passes through unchanged. Binding positions are {@code let}/
     * {@code const} locals, function parameters, function names, and
     * catch parameters; property/export/table keys keep the raw name
     * (js-backend-architecture D4). Package-visible so a same-package
     * harness can assert the pins directly.
     */
    static String jsName(String identifier) {
        return JS_RESERVED_BINDINGS.contains(identifier)
            ? identifier + "$" : identifier;
    }

    /**
     * The JS runtime type descriptor for an internal {@link Type},
     * mirroring {@code LuaBackend.typeDescriptor} byte-for-byte
     * (deal/codegen/lua/LuaBackend.java:884-933): {@code null}/primitive
     * names, bare {@code Error}, {@code @<modulePath>/<Name>} classes,
     * {@code T[]} with the {@code [T]} disambiguation form for
     * function-involving elements, {@code T|null} with the {@code ?F}
     * form for nullable function types, and {@code async(...)->R}
     * (js-backend-emitter D5). Package-visible for the same-package
     * harness.
     */
    static String jsTypeDescriptor(Type t) {
        if (t == null) return "null";
        return switch (t) {
            case Type.Null ignored -> "null";
            case Type.Boolean ignored -> "boolean";
            case Type.Int ignored -> "int";
            case Type.Number ignored -> "number";
            case Type.String ignored -> "string";
            case Type.Table ignored -> "table";
            case Type.Error ignored -> "Error";
            case Type.Array arr -> {
                String elem = jsTypeDescriptor(arr.element());
                // Function-involving elements emit the spec bracket form,
                // so "[(int)->int]" (array of functions) cannot be misread
                // as "(int)->int[]" (function returning an int array).
                yield elem.contains("->") ? "[" + elem + "]" : elem + "[]";
            }
            case Type.Nullable n -> {
                // Nullable function types emit the spec "?F" form, so
                // "?(int)->int" (nullable function) cannot be misread as
                // "(int)->int|null" (function returning a nullable int).
                // Every other nullable keeps the legacy "T|null" spelling.
                if (n.inner() instanceof Type.Func) {
                    yield "?" + jsTypeDescriptor(n.inner());
                }
                yield jsTypeDescriptor(n.inner()) + "|null";
            }
            case Type.Class cls -> {
                if (cls.modulePath() != null && !cls.modulePath().isEmpty()) {
                    yield "@" + cls.modulePath() + "/" + cls.name();
                }
                yield cls.name();
            }
            case Type.Func f -> {
                StringBuilder sb = new StringBuilder();
                if (f.isAsync()) sb.append("async");
                sb.append("(");
                for (int i = 0; i < f.paramTypes().size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(jsTypeDescriptor(f.paramTypes().get(i)));
                }
                sb.append(")->").append(jsTypeDescriptor(f.returnType()));
                yield sb.toString();
            }
        };
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
    // Import classification (raw import path → imported module path /
    // declared export map): consumed by the import-binding and rejection
    // slices (T4/T6); retained here for the module shape.
    private final Map<String, String> importResolutions;
    private final Map<String, Map<String, Type>> hostModules;
    private final boolean isEntry;

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

    /**
     * True while the walk sits at module level; false inside a
     * function-expression body walk. The D6 rejection table fires its
     * nested-class E6000 arm when a {@link ClassDeclaration} is met
     * below module level (js-backend-emitter D8) — function-expression
     * bodies ARE walked at this slice (function-typed class defaults),
     * so the arm is live.
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

    private JsBackend(Map<ExpressionNode, Type> typeMap, SymbolTable symbols,
                      String sourcePath, String modulePath,
                      Map<String, String> importResolutions,
                      Map<String, Map<String, Type>> hostModules,
                      boolean isEntry) {
        this.typeMap = typeMap;
        this.symbols = symbols;
        this.sourcePath = sourcePath;
        this.modulePath = modulePath;
        this.importResolutions = importResolutions;
        this.hostModules = hostModules;
        this.isEntry = isEntry;
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
                                           Map<String, Map<String, Type>> hostModules,
                                           boolean isEntry) {
        java.util.Objects.requireNonNull(program, "program must not be null");
        java.util.Objects.requireNonNull(result, "result must not be null");
        java.util.Objects.requireNonNull(importResolutions,
            "importResolutions must not be null");
        java.util.Objects.requireNonNull(hostModules,
            "hostModules must not be null");
        JsBackend backend = new JsBackend(result.typeMap(), result.symbolTable(),
            sourcePath, modulePath, importResolutions, hostModules, isEntry);
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
                    "let " + cd.name() + "$new; let " + cd.name() + "$meta;");
                case ExportDeclaration ed -> {
                    switch (ed.declaration()) {
                        case ClassDeclaration cd -> predeclares.add(
                            "let " + cd.name() + "$new; let "
                                + cd.name() + "$meta;");
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

        emitHeader(predeclares);
        for (StatementNode stmt : program.statements()) {
            visitStatement(stmt);
        }
        // Shape step 8: the export-assignment section follows the
        // declarations in the artifact (T4 populates it).
        emitExportsSection();
        if (isEntry && entryMain != null) {
            emitEntryShim();
        }

        return new JsCodegenResult(modulePath, out.toString(), diagnostics);
    }

    // =========================================================================
    // Module shape (js-backend-architecture D2, js-backend-emitter D4)
    // =========================================================================

    /**
     * Emits the canonical CommonJS module shape, steps 1-7 in exact order.
     * Step 5 (import bindings) and the function predeclares are populated
     * by T3/T4; step 6 carries the predeclared class-artifact {@code let}s
     * of this slice; step 8's section header is emitted by
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
    private void emitHeader(List<String> predeclares) {
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
        out.append("\n");
        // 4. Intrinsic header seeds: int/number are first-class function
        // values with the seeded static signatures, plus the module-private
        // builtin-Error defaults/constructor pair (neither is exported).
        out.append("// Intrinsic header wrappers: int/number are first-class function\n");
        out.append("// values with the seeded static signatures (number)->int and\n");
        out.append("// (int)->number; the builtin-Error defaults/constructor/META\n");
        out.append("// artifacts stay private (never reach the export surface).\n");
        out.append("const int = $rt.function(\"(number)->int\", "
            + "(v, $file, $line, $column) => $rt.intConvert(v, $file, $line, $column));\n");
        out.append("const number = $rt.function(\"(int)->number\", "
            + "(v, $file, $line, $column) => $rt.numberConvert(v, $file, $line, $column));\n");
        out.append("const $ErrorDefaults = () => ({ [\"code\"]: \"\", "
            + "[\"message\"]: \"\" });\n");
        out.append("const Error$new = (provided, $file, $line, $column) => "
            + "$rt.makeClass(\"Error\", \"Error\", $ErrorDefaults, provided, "
            + "$file, $line, $column);\n");
        // The builtin-Error META pair member: `Error` as a value is
        // checker-accepted class metadata exactly like any declared
        // class's — module-private, identity `Error` bare, the inline
        // META shape of js-backend-emitter D4 step 7.
        out.append("const Error$meta = { $kind: \"class\", "
            + "$classname: \"Error\" };\n");
        out.append("\n");
        // 5. Import bindings in import order (populated by T4).
        out.append("// Import bindings (import order).\n");
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
     * Shape step 8's section header: the export assignments in
     * declaration order follow the declaration walk in the artifact
     * (T4 populates the assignments themselves).
     */
    private void emitExportsSection() {
        out.append("// Export assignments (declaration order).\n");
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
        String artifactPath = (modulePath == null ? "" : modulePath)
            .replace('.', '/');
        int lastSlash = artifactPath.lastIndexOf('/');
        String moduleDir = lastSlash < 0 ? ""
            : artifactPath.substring(0, lastSlash);
        if (moduleDir.isEmpty()) {
            return "./deal/runtime";
        }
        int depth = moduleDir.split("/", -1).length;
        StringBuilder specifier = new StringBuilder();
        specifier.append("..");
        for (int i = 1; i < depth; i++) {
            specifier.append("/..");
        }
        specifier.append("/deal/runtime");
        return specifier.toString();
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
    private void emitEntryShim() {
        out.append("\n");
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
                case "Error" -> Types.classType("Error", "");
                default -> {
                    Symbol sym = symbols.resolve(nt.name());
                    if (sym instanceof Symbol.ClassSymbol cs) {
                        yield Types.classType(nt.name(), cs.modulePath());
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
                yield Types.classType(qt.typeName(), qt.moduleName());
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
        switch (stmt) {
            case ImportDeclaration imp -> {
                // T4: import bindings in import order (shape step 5).
            }
            case FunctionDeclaration fd -> visit(fd);
            case ClassDeclaration cd -> visit(cd);
            case ExportDeclaration ed -> {
                // T4 adds the export assignments (shape step 8); the
                // wrapped declaration still emits its artifacts.
                switch (ed.declaration()) {
                    case ClassDeclaration cd -> visit(cd);
                    case FunctionDeclaration fd -> visit(fd);
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
     * statements; a null-typed sync function appends the validated
     * {@code null} fall-off return after the body (control falling off
     * the end returns the DEAL null). Async declarations emit the
     * {@code async function} keyword structurally; completion checks
     * and await lowering are T5's.
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
        String sig = funcType != null ? jsTypeDescriptor(funcType) : "()";
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
            emitWrappedBody(fd.params(), fd.body(), returnType, isAsync,
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
     * the body statement walk inside a fresh lexical scope with the
     * parameters declared (mirroring the checker's function scope), and
     * — for a sync function whose declared return type is {@code null}
     * — the validated fall-off return after the body (control falling
     * off the end returns the DEAL null). The body sits below module
     * level, so a nested class declaration fires the D6 E6000 arm.
     * Return statements inside the body check against
     * {@link #currentReturnType} at the return site.
     */
    private void emitWrappedBody(List<Parameter> params, Block body,
                                 Type returnType, boolean isAsync,
                                 Span fallOffSpan) {
        for (Parameter param : params) {
            Type paramType = resolveTypeNode(param.type());
            if (paramType != null && !(paramType instanceof Type.Error)
                    && !(paramType instanceof Type.Null)) {
                line(emitCheckExprForwarded(jsName(param.name()), paramType)
                    + ";");
            }
        }
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
        if (!isAsync && returnType instanceof Type.Null) {
            line("return $rt.checkNull(null, " + spanArgs(fallOffSpan) + ");");
        }
    }

    /**
     * Class declaration (js-backend-emitter D4 step 7): the predeclared
     * artifact {@code let}s carry the header (step 6); the declaration
     * site assigns the construction closure — a zero-arg defaults thunk
     * builds the defaults object with computed keys, fresh per
     * construction, with {@code $rt.MISSING} for absent optional fields —
     * and the inline META pair. The identity is the module-qualified
     * descriptor {@code @<modulePath>/<Name>} (runtime-class-identity
     * D1-D2). A nested (non-module-level) class declaration is the D6
     * rejection table's E6000 arm: the walk meets it through
     * function-expression bodies (function-typed class defaults), which
     * ARE emitted at this slice, and the arm fires at the declaration
     * site instead of emitting assignments to undeclared
     * {@code <C>$new}/{@code <C>$meta} bindings — never a silent
     * miscompile (js-backend-emitter D8).
     */
    private void visit(ClassDeclaration cd) {
        if (!atModuleLevel) {
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E6000,
                "JavaScript backend: nested class declarations are not "
                    + "supported (ISSUE-0169 skeleton)",
                cd.span()));
            return;
        }
        out.append("// Class: ").append(cd.name())
            .append(" — construction closure, defaults thunk, and metadata.\n");
        line(cd.name() + "$new = (provided, $file, $line, $column) => "
            + "$rt.makeClass(" + jsStringLiteral(cd.name()) + ", "
            + jsStringLiteral(qualifiedClassName(cd.name())) + ", "
            + classDefaultsThunk(cd) + ", provided, $file, $line, $column);");
        line(cd.name() + "$meta = { $kind: \"class\", $classname: "
            + jsStringLiteral(qualifiedClassName(cd.name())) + " };");
        out.append("\n");
    }

    /**
     * The module-qualified runtime class identity for a class declared in
     * this module: bare name when the module path is empty, else
     * {@code @<modulePath>/<name>} (runtime-class-identity D1-D2, the
     * {@code LuaBackend.qualifiedClassName} mirror).
     */
    private String qualifiedClassName(String name) {
        String mp = modulePath != null ? modulePath : sourcePath;
        if (mp == null || mp.isEmpty()) {
            return name;
        }
        return "@" + mp + "/" + name;
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
     * E4001 requires every literal to provide them).
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
     * One field's defaults-thunk entry (the
     * {@code LuaBackend.visit(ClassDeclaration)} order,
     * deal/codegen/lua/LuaBackend.java:1076-1096): absent optional →
     * {@code $rt.MISSING}; default expression → the expression verbatim
     * (its own checks travel inside — {@code $rt.intAdd}, the conversions,
     * the table/array literals); required nullable without default →
     * {@code null}; otherwise the type-node zero value.
     */
    private String fieldDefault(ClassField field) {
        if (field.optional() && field.defaultExpr().isEmpty()) {
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
        boolean hasAnnotation = node.typeAnnotation().isPresent();
        Type targetType = hasAnnotation
            ? resolveTypeNode(node.typeAnnotation().get()) : null;
        Type exprType = typeOf(node.initializer());
        String init = emitExpression(node.initializer());

        Span span = hasAnnotation
            ? node.typeAnnotation().get().span()
            : node.initializer().span();

        if (hasAnnotation && targetType != null
                && !(targetType instanceof Type.Error)) {
            line("let " + name + " = "
                + boundaryValue(init, targetType, exprType, span) + ";");
        } else if (!hasAnnotation && exprType != null
                && node.initializer() instanceof LiteralExpr
                && (exprType instanceof Type.Int
                    || exprType instanceof Type.Boolean
                    || exprType instanceof Type.String
                    || exprType instanceof Type.Number
                    || exprType instanceof Type.Null)) {
            line("let " + name + " = " + init + ";");
        } else {
            Type checkType = targetType != null ? targetType : exprType;
            if (checkType != null
                    && !(checkType instanceof Type.Error)
                    && !(checkType instanceof Type.Null)) {
                line("let " + name + " = "
                    + emitCheckExpr(init, checkType, span) + ";");
            } else {
                line("let " + name + " = " + init + ";");
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
                line(emitExpression(idx.array()) + ".delete("
                    + emitExpression(idx.index()) + ");");
                return;
            }
        }
        if (node.target() instanceof MemberAccessExpr mae) {
            Type objType = typeOf(mae.object());
            if (objType instanceof Type.Class) {
                line("$rt.setProp(" + emitExpression(mae.object()) + ", "
                    + jsStringLiteral(mae.field()) + ", $rt.MISSING);");
                return;
            }
            if (objType instanceof Type.Table) {
                // Module aliases type as Table (getDeclaredType of a
                // ModuleSymbol); deleting a module export is a T4
                // refinement surface — no function body reaches this
                // slice's artifact.
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
        walkScopedBlock(node.catchBlock().statements());
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
     * carry the header, shape step 6).
     */
    private void walkStatements(List<StatementNode> statements) {
        for (StatementNode stmt : statements) {
            FunctionDeclaration function = switch (stmt) {
                case FunctionDeclaration fd -> fd;
                case ExportDeclaration ed
                        when ed.declaration()
                            instanceof FunctionDeclaration fd -> fd;
                default -> null;
            };
            if (function != null) {
                line("let " + jsName(function.name()) + ";");
                declareLocal(function.name());
            }
        }
        for (StatementNode stmt : statements) {
            visitStatement(stmt);
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
    }

    private void popLocalScope() {
        localScopes.remove(localScopes.size() - 1);
    }

    private void declareLocal(String name) {
        localScopes.get(localScopes.size() - 1).add(name);
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
            case AwaitExpression await ->
                "await " + emitExpression(await.callee());
            case TemplateLiteralExpr tl -> emitTemplateLiteral(tl);
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
     * {@code static$}/{@code eval$}). A module-level class-symbol
     * reference in expression position (a class META as a value —
     * checker-accepted, spec §Export forms: exporting a class exports
     * class metadata) lowers to the predeclared {@code <C>$meta}
     * artifact (the header-seeded {@code Error$meta} for the builtin
     * Error) — never a bare unbound identifier, because the artifact
     * binds only {@code <C>$new}/{@code <C>$meta}. The lexical scope
     * stack disambiguates shadowing locals (a function parameter or
     * {@code let} named like a class resolves to its variable binding),
     * mirroring the checker's scoping.
     */
    private String emitIdentifier(IdentifierExpr id) {
        if (!isLocalName(id.name())) {
            Symbol sym = symbols.resolve(id.name());
            if (sym instanceof Symbol.ClassSymbol) {
                return id.name() + "$meta";
            }
        }
        return jsName(id.name());
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
     * decides); module members (the T4 bindings) stay plain property
     * accesses. Property positions keep the raw name — a field spelled
     * {@code static}/{@code eval}/{@code __proto__} reads as written.
     */
    private String emitMemberAccess(MemberAccessExpr mae) {
        String obj = emitExpression(mae.object());
        Type objType = typeOf(mae.object());
        String field = mae.field();
        if (field.equals("length") && objType instanceof Type.Array) {
            return obj + ".length";
        }
        // Module members: the alias identifier types as Table
        // (getDeclaredType of a ModuleSymbol) but its members are plain
        // export-table properties, never Map keys.
        if (mae.object() instanceof IdentifierExpr id) {
            Symbol sym = symbols.resolve(id.name());
            if (sym instanceof Symbol.ModuleSymbol) {
                return obj + "." + field;
            }
        }
        if (objType instanceof Type.Class cls) {
            ClassField cf = findClassField(cls, field);
            if (cf != null && cf.optional()) {
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
     * (local classes and the seeded builtin Error); imported classes
     * resolve through the T4 import surface — no function body reaches
     * this slice's artifact, so the plain-read fallback is deterministic
     * here.
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
     * index (checked) before the container, exactly the reference's
     * evaluation order.
     */
    private String emitIndex(IndexExpr idx) {
        Type containerType = typeOf(idx.array());
        String arr = emitExpression(idx.array());
        String index = emitExpression(idx.index());
        Span span = idx.span();
        if (containerType instanceof Type.Array) {
            return "(() => { const $idx = $rt.checkInt(" + index + ", "
                + spanArgs(span) + "); if ($idx < 0) { "
                + "$rt.fail(\"E8002\", \"negative array index\", "
                + spanArgs(span) + "); } return " + arr + "[$idx]; })()";
        }
        if (containerType instanceof Type.Table) {
            return arr + ".get(" + index + ")";
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
        String mp = cls.modulePath();
        if (mp == null || mp.isEmpty()) {
            return "Error$new";
        }
        if (mp.equals(modulePath)) {
            return cls.name() + "$new";
        }
        String alias = findImportAliasForClass(cls.name(), mp);
        if (alias != null) {
            return alias + "." + cls.name() + "$new";
        }
        // Defensive fallback (import bindings land at T4): the reference
        // is still the declaring module's exported closure.
        return cls.name() + "$new";
    }

    /**
     * Searches the module symbol table for a ModuleSymbol whose exports
     * include the class with the exact module path; returns the import
     * alias or {@code null} (the
     * {@code LuaBackend.findImportAliasForClass} mirror).
     */
    private String findImportAliasForClass(String className, String modulePath) {
        for (Map.Entry<String, Symbol> entry : symbols.symbols().entrySet()) {
            Symbol sym = entry.getValue();
            if (sym instanceof Symbol.ModuleSymbol ms) {
                Type exportType = ms.exports().get(className);
                if (exportType instanceof Type.Class tc
                        && tc.modulePath().equals(modulePath)) {
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
     * capture natively. Async function expressions emit the
     * {@code async function} keyword structurally; completion checks and
     * await lowering are T5's.
     */
    private String emitFunctionExpr(FunctionExpr fe) {
        Type funcType = typeOf(fe);
        String sig = funcType instanceof Type.Func f
            ? jsTypeDescriptor(f) : "()";
        Type returnType = funcType instanceof Type.Func f
            ? f.returnType() : null;
        boolean isAsync = funcType instanceof Type.Func f && f.isAsync();

        Type savedReturn = currentReturnType;
        currentReturnType = returnType;

        String body = captureOutput(() -> {
            indent++;
            try {
                emitWrappedBody(fe.params(), fe.body(), returnType,
                    isAsync, fe.returnType().span());
            } finally {
                indent--;
            }
        });

        currentReturnType = savedReturn;

        String keyword = isAsync ? "async function" : "function";
        return "$rt.function(\"" + sig + "\", " + keyword + "("
            + buildParamList(fe.params()) + ") {\n" + body
            + "  ".repeat(indent) + "})";
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
     * checked value, write) in expression position. Identifier targets
     * and class field writes re-validate the assigned value against the
     * target's declared type at the assignment site (the value's own
     * boundary check for that transition), mirroring the
     * declaration-site boundary checks.
     */
    private String emitAssignment(AssignmentExpr assign) {
        String value = emitExpression(assign.value());
        Span span = assign.span();

        if (assign.target() instanceof IndexExpr idx) {
            Type containerType = typeOf(idx.array());
            if (containerType instanceof Type.Array arrT) {
                String arr = emitExpression(idx.array());
                String index = emitExpression(idx.index());
                return "(() => { const $arr = " + arr
                    + "; const $idx = $rt.checkInt(" + index + ", "
                    + spanArgs(idx.span()) + "); "
                    + "if ($idx < 0 || $idx > $arr.length) { "
                    + "$rt.fail(\"E8002\", \"array index out of bounds\", "
                    + spanArgs(idx.span()) + "); } "
                    + "return $arr[$idx] = "
                    + emitCheckExpr(value, arrT.element(), span) + "; })()";
            }
            if (containerType instanceof Type.Table) {
                return emitExpression(idx.array()) + ".set("
                    + emitExpression(idx.index()) + ", " + value + ")";
            }
        }
        if (assign.target() instanceof MemberAccessExpr mae) {
            Type objType = typeOf(mae.object());
            if (objType instanceof Type.Class) {
                return "$rt.setProp(" + emitExpression(mae.object()) + ", "
                    + jsStringLiteral(mae.field()) + ", "
                    + checkedAssignmentValue(value,
                        typeOf(assign.target()), typeOf(assign.value()),
                        span) + ")";
            }
            if (objType instanceof Type.Table) {
                return emitExpression(mae.object()) + ".set("
                    + jsStringLiteral(mae.field()) + ", " + value + ")";
            }
            return emitExpression(mae.object()) + "." + mae.field()
                + " = " + value;
        }
        if (assign.target() instanceof IdentifierExpr id) {
            return jsName(id.name()) + " = "
                + checkedAssignmentValue(value, typeOf(assign.target()),
                    typeOf(assign.value()), span);
        }
        // Defensive plain form: unreachable for checker-accepted
        // programs (E3017 rejects array .length targets, the checker
        // rejects every other unsupported target shape).
        return emitExpression(assign.target()) + " = " + value;
    }

    /**
     * The assignment-site re-validation of the assigned value against
     * the target's declared type (the checker records the target type in
     * the type map, so a dynamic value like a table read crossing into a
     * typed binding or field is checked exactly where the transition
     * happens): a {@code Type.Error}/{@code null} type and the unchecked
     * table target pass the value through verbatim; every other target
     * type crosses the runtime typed boundary with the assignment-span
     * location — function targets via the exact-{@code $sig}
     * {@code checkType} E8010 check, with the arity-extension adapter
     * replacing it when the value's declared signature is assignably
     * narrower ({@link #boundaryValue}, the
     * {@code LuaBackend.emitArityAdapter} mirror).
     */
    private String checkedAssignmentValue(String value, Type targetType,
                                          Type valueType, Span span) {
        if (targetType == null || targetType instanceof Type.Error
                || targetType instanceof Type.Table) {
            return value;
        }
        return boundaryValue(value, targetType, valueType, span);
    }

    /**
     * The value emission for a typed transition (declaration-site,
     * assignment-site): an arity-extension pair — a function value whose
     * declared signature is assignable to the target but declares fewer
     * parameters — emits the adapter closure (js-backend-emitter D6);
     * every other value crosses the runtime typed-boundary check —
     * function targets compare the wrapper's {@code $sig} against the
     * expected descriptor exactly, so a wrong-signature wrapper surfaces
     * the E8010 mismatch from the runtime {@code checkType} function
     * branch (js-backend-runtime D3).
     */
    private String boundaryValue(String value, Type targetType,
                                 Type valueType, Span span) {
        if (targetType instanceof Type.Func tf
                && valueType instanceof Type.Func vf
                && isArityExtension(vf, tf)) {
            return emitArityAdapter(tf, vf, value, span);
        }
        return emitCheckExpr(value, targetType, span);
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
     * untouched (T5 refines).
     */
    private String emitArityAdapter(Type.Func targetFunc, Type.Func valueFunc,
                                    String valueExpr, Span span) {
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < targetFunc.paramTypes().size(); i++) {
            if (i > 0) params.append(", ");
            params.append("$p").append(i);
        }
        if (params.length() > 0) params.append(", ");
        params.append("$file, $line, $column");

        StringBuilder sb = new StringBuilder();
        sb.append("$rt.function(")
            .append(jsStringLiteral(jsTypeDescriptor(targetFunc)))
            .append(", function(").append(params).append(") {\n");
        String bodyIndent = "  ".repeat(indent + 1);
        for (int i = 0; i < targetFunc.paramTypes().size(); i++) {
            sb.append(bodyIndent)
                .append(emitCheckExpr("$p" + i,
                    targetFunc.paramTypes().get(i), span))
                .append(";\n");
        }
        String innerCall = adapterInnerCall(valueExpr,
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
        return sb.toString();
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

    /** Appends one indented source line to the accumulator. */
    private void line(String s) {
        out.append("  ".repeat(indent)).append(s).append("\n");
    }

    /**
     * Captures the output of a sub-emission (function-expression bodies)
     * into a returned string, restoring the accumulator and indent
     * afterwards (the {@code LuaBackend.captureOutput} pattern).
     */
    private String captureOutput(Runnable action) {
        StringBuilder saved = out;
        int savedIndent = indent;
        out = new StringBuilder();
        try {
            action.run();
            return out.toString();
        } finally {
            out = saved;
            indent = savedIndent;
        }
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
            case Type.Error ignored -> valueExpr;
            case Type.Array arr ->
                "$rt.checkArray(\"" + jsTypeDescriptor(type) + "\", "
                    + valueExpr + ", " + spanParam + ")";
            case Type.Nullable n ->
                "$rt.checkNullable(\"" + jsTypeDescriptor(n.inner()) + "\", "
                    + valueExpr + ", " + spanParam + ")";
            case Type.Class cls ->
                "$rt.checkType(\"" + jsTypeDescriptor(cls) + "\", "
                    + valueExpr + ", " + spanParam + ")";
            case Type.Func f ->
                "$rt.checkType(\"" + jsTypeDescriptor(f) + "\", "
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
