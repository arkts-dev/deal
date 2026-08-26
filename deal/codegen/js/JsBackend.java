package deal.codegen.js;

import deal.ast.ClassDeclaration;
import deal.ast.ExportDeclaration;
import deal.ast.ExpressionNode;
import deal.ast.FunctionDeclaration;
import deal.ast.ImportDeclaration;
import deal.ast.NamedType;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.ast.TypeNode;
import deal.checker.CheckResult;
import deal.checker.SymbolTable;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticRange;
import deal.lexer.Diagnostic;
import deal.types.Type;

import java.util.ArrayList;
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
 * backstop. The walker tolerates every checker-valid statement
 * structurally at this slice — no lowering, no diagnostic — so a
 * checker-valid entry module compiles today; no in-scope construct is
 * rejected merely because its lowering lands later (js-backend-emitter
 * D1).
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
                                  List<Diagnostic> diagnostics,
                                  List<DiagnosticRange> diagnosticRanges) {
        public JsCodegenResult {
            java.util.Objects.requireNonNull(modulePath, "modulePath must not be null");
            java.util.Objects.requireNonNull(source, "source must not be null");
            diagnostics = List.copyOf(diagnostics);
            // Transitional parallel ranged channel (backend-boundary
            // conversion, removed by the T12 backend migration): one
            // range per legacy entry in the same order.
            diagnosticRanges = List.copyOf(diagnosticRanges);
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
    // symbolTable()): this slice stores it like JvmBackend does; later
    // slices read it for their lowering.
    private final Map<ExpressionNode, Type> typeMap;
    private final SymbolTable symbols;
    private final String sourcePath;
    private final String modulePath;
    // Import classification (raw import path → imported module path /
    // declared export map), supplied by the orchestrator: consumed by the
    // import-binding and rejection slices (T4/T6).
    private final Map<String, String> importResolutions;
    private final Map<String, Map<String, Type>> hostModules;
    private final boolean isEntry;

    /** Backend diagnostics; all error-severity by construction. */
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    /**
     * Transitional parallel ranged channel (backend-boundary conversion,
     * removed by the T12 backend migration): one {@link DiagnosticRange}
     * per legacy {@link Diagnostic} entry in the same emission order,
     * computed from the span the backend anchored each diagnostic at.
     */
    private final List<DiagnosticRange> diagnosticRanges = new ArrayList<>();
    /** The generated CommonJS source accumulator. */
    private final StringBuilder out = new StringBuilder();

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

        emitHeader();
        for (StatementNode stmt : program.statements()) {
            visitStatement(stmt);
        }
        if (isEntry && entryMain != null) {
            emitEntryShim();
        }

        return new JsCodegenResult(modulePath, out.toString(), diagnostics,
            diagnosticRanges);
    }

    // =========================================================================
    // Module shape (js-backend-architecture D2, js-backend-emitter D4)
    // =========================================================================

    /**
     * Emits the canonical CommonJS module shape, steps 1-8 in exact order.
     * Steps 5-8 are structurally empty at this slice: later slices
     * populate the import bindings, the predeclared {@code let}s, the
     * declarations, and the export assignments. Source comments precede
     * generated statement groups (js-backend-architecture D8).
     *
     * <p>Host-global hygiene holds from the first merge: after the
     * capture line generated code never spells bare {@code require}/
     * {@code module}/{@code exports}, and never spells a bare host-global
     * name anywhere; the nil-equivalent value is the runtime member
     * {@code $rt.undefined} (js-backend-architecture D2).
     */
    private void emitHeader() {
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
        out.append("// (int)->number; the builtin-Error defaults/constructor pair stays\n");
        out.append("// private (never reaches the export surface).\n");
        out.append("const int = $rt.function(\"(number)->int\", "
            + "(v, $file, $line, $column) => $rt.intConvert(v, $file, $line, $column));\n");
        out.append("const number = $rt.function(\"(int)->number\", "
            + "(v, $file, $line, $column) => $rt.numberConvert(v, $file, $line, $column));\n");
        out.append("const $ErrorDefaults = () => ({ [\"code\"]: \"\", "
            + "[\"message\"]: \"\" });\n");
        out.append("const Error$new = (provided, $file, $line, $column) => "
            + "$rt.makeClass(\"Error\", \"Error\", $ErrorDefaults, provided, "
            + "$file, $line, $column);\n");
        out.append("\n");
        // 5. Import bindings in import order (populated by T4).
        out.append("// Import bindings (import order).\n");
        out.append("\n");
        // 6. Predeclared lets for module-level functions and class
        // artifacts in declaration order (populated by T2/T3).
        out.append("// Predeclared function and class-artifact bindings "
            + "(declaration order).\n");
        out.append("\n");
        // 7. Declarations in source order (populated by T2/T3).
        out.append("// Declarations (source order).\n");
        out.append("\n");
        // 8. Export assignments in declaration order (populated by T4).
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
            diagnostics.add(Diagnostic.error(DiagnosticCode.E6004,
                "entry module must export non-async main(): null; found "
                    + (foundAnyMain
                        ? "main with a different signature or an async marker"
                        : "no main export"),
                program.span().file(), program.span().startLine(),
                program.span().startColumn()));
            // Parallel ranged channel: the program span's own range
            // (SOURCE-exact via the T2 program-span obligation).
            diagnosticRanges.add(program.span().range());
        }
        return match;
    }

    /**
     * Minimal type-node resolution for this slice's only consumer, the
     * E6004 backstop: a {@code NamedType} primitive resolves to its
     * {@link Type} enum; every other form resolves to
     * {@link Type.Error#INSTANCE} — a non-null return type is exactly the
     * signature mismatch the backstop rejects. Later slices extend this
     * resolver alongside their lowering.
     */
    private Type resolveTypeNode(TypeNode typeNode) {
        if (typeNode instanceof NamedType nt) {
            return switch (nt.name()) {
                case "null" -> Type.Null.INSTANCE;
                case "boolean" -> Type.Boolean.INSTANCE;
                case "int" -> Type.Int.INSTANCE;
                case "number" -> Type.Number.INSTANCE;
                case "string" -> Type.String.INSTANCE;
                case "table" -> Type.Table.INSTANCE;
                default -> Type.Error.INSTANCE;
            };
        }
        return Type.Error.INSTANCE;
    }

    // =========================================================================
    // Structural staging walk (js-backend-emitter D1)
    // =========================================================================

    /**
     * Structural staging walk (ISSUE-0247 core slice, js-backend-emitter
     * D1): every statement kind of a checker-valid module is tolerated
     * here without lowering and without a diagnostic — import
     * declarations, function/class declarations, export declarations, and
     * their bodies included. Later slices replace the no-op arms with
     * their lowering and populate the module-shape sections; no in-scope
     * construct is rejected merely because its lowering lands later.
     */
    private void visitStatement(StatementNode stmt) {
        switch (stmt) {
            case ImportDeclaration imp -> {
                // T4: import bindings in import order (shape step 5).
            }
            case FunctionDeclaration fd -> {
                // T2/T3: predeclared let + wrapper assignment (steps 6-7).
            }
            case ClassDeclaration cd -> {
                // T2/T3: predeclared artifact lets + artifact assignments
                // (steps 6-7).
            }
            case ExportDeclaration ed -> {
                // T4: export assignments via $rt.setProp (shape step 8).
            }
            default -> {
                // Every other checker-valid construct (variables, control
                // flow, errors, ...) is owned by a later slice and is
                // tolerated structurally at this slice.
            }
        }
    }
}
