package deal.codegen.lua;

import deal.ast.*;
import deal.ast.ArrayType;
import deal.ast.FunctionType;
import deal.ast.NullableType;
import deal.checker.CheckResult;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.codegen.SourceMapGenerator;
import deal.lexer.Diagnostic;
import deal.types.Type;
import deal.types.Types;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import deal.diagnostics.DiagnosticCode;

/**
 * Lua code generator (ISSUE-0007). Walks the typed AST and emits Lua source code
 * with runtime type checks on every typed boundary.
 *
 * <p>Implements {@link Visitor}{@code <Void>} with one visit method per node type,
 * exhaustive per sealed interface. Uses the type map from the type checker to
 * determine when to insert runtime checks.</p>
 */
public final class LuaBackend implements Visitor<Void> {

    private final Map<ExpressionNode, Type> typeMap;
    private final SymbolTable symbols;
    private StringBuilder out = new StringBuilder();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private int indent = 0;

    private final Map<String, String> exportedValues = new LinkedHashMap<>();

    // ISSUE-0050: deferred @jsonable class metadata for two-pass emission
    private final List<JsonableClassMeta> deferredJsonables = new ArrayList<>();

    private Type currentReturnType = null;
    private String sourceFilePath = "unknown.deal";

    private String currentContinueLabel = null;
    private int labelCounter = 0;

    private String tryReturnFlag = null;
    private String tryReturnVal = null;

    // Break/continue flag support (ISSUE-0011): flag-based pattern for
    // break/continue inside try within loops. Allocated at every TryStatement
    // level when inside a loop, with save/restore for nesting.
    private String tryBreakFlag = null;
    private String tryContinueFlag = null;

    private int functionDepth = 0;

    // Retained for potential assertions and future use; no longer used
    // for break/continue detection (ISSUE-0011 replaced that with flag-based pattern).
    private int insideTryDepth = 0;

    // Import resolution map: raw import path → Lua require path
    private Map<String, String> importResolutions = Map.of();

    // ISSUE-0009: for-loop shadow-local lowering.
    // When non-null, all IdentifierExpr nodes with this name in condition
    // and update expressions are remapped to "_name" (the outer counter).
    private String forLoopShadowVar = null;

    // Module-scope flag: true while walking statements at Lua chunk scope.
    // Module-level class artifacts are emitted into the __deal namespace
    // table; classes declared inside functions or control-flow constructs
    // keep scope-local artifact locals (ISSUE-0074 D2.6).
    private boolean moduleScope = true;

    // Source map support
    private SourceMapGenerator sourceMapGenerator = null;

    /**
     * Entry point: generate Lua source for a complete program.
     */
    public static String generate(ProgramNode program, CheckResult result,
                                   String sourcePath) {
        return generateWithImports(program, result, sourcePath, Map.of());
    }

    /**
     * Entry point with import resolution mapping.
     * Maps raw import paths (e.g. "./lib") to Lua require paths (e.g. "lib").
     */
    public static String generateWithImports(ProgramNode program, CheckResult result,
                                              String sourcePath,
                                              Map<String, String> importResolutions) {
        LuaBackend backend = new LuaBackend(result.typeMap(), result.symbolTable());
        backend.sourceFilePath = sourcePath;
        backend.importResolutions = Map.copyOf(importResolutions);
        backend.emitHeader();
        backend.emitLine("");

        backend.walkStatements(program.statements());
        backend.emitJsonableCode();
        backend.emitExports();
        return backend.out.toString();
    }

    /**
     * Generate Lua source with source map tracking.
     * Returns both the Lua source and the SourceMapGenerator (which can produce
     * the JSON sidecar).
     */
    public static String generateWithSourceMap(ProgramNode program, CheckResult result,
                                                String sourcePath,
                                                Map<String, String> importResolutions,
                                                SourceMapGenerator smg) {
        LuaBackend backend = new LuaBackend(result.typeMap(), result.symbolTable());
        backend.sourceFilePath = sourcePath;
        backend.importResolutions = Map.copyOf(importResolutions);
        backend.sourceMapGenerator = smg;
        backend.emitHeader();
        backend.emitLine("");

        backend.walkStatements(program.statements());
        backend.emitJsonableCode();
        backend.emitExports();
        return backend.out.toString();
    }

    /**
     * Generate Lua source and write it to the output path.
     * Runtime library is copied to {@code outputRoot/deal/runtime.lua}.
     *
     * @param outputRoot the root output directory (runtime lands at outputRoot/deal/runtime.lua)
     * @param outputPath the full path for this module's .lua file
     */
    public static void generateToFile(ProgramNode program, CheckResult result,
                                       String sourcePath, Path outputRoot,
                                       Path outputPath) throws IOException {
        generateToFile(program, result, sourcePath, outputRoot, outputPath, false, Map.of());
    }

    /**
     * Generate Lua source and write it to the output path, optionally producing
     * a source map sidecar file.
     *
     * @param outputRoot the root output directory
     * @param outputPath the full path for this module's .lua file
     * @param emitSourceMap if true, a {@code .deal.map.json} sidecar is written
     */
    public static void generateToFile(ProgramNode program, CheckResult result,
                                       String sourcePath, Path outputRoot,
                                       Path outputPath, boolean emitSourceMap)
                                       throws IOException {
        generateToFile(program, result, sourcePath, outputRoot, outputPath,
            emitSourceMap, Map.of());
    }

    /**
     * Generate Lua source and write it to the output path, optionally producing
     * a source map sidecar file, with import resolution mapping.
     *
     * @param outputRoot        the root output directory
     * @param outputPath        the full path for this module's .lua file
     * @param emitSourceMap     if true, a {@code .deal.map.json} sidecar is written
     * @param importResolutions raw import path → Lua require path mapping
     */
    public static void generateToFile(ProgramNode program, CheckResult result,
                                       String sourcePath, Path outputRoot,
                                       Path outputPath, boolean emitSourceMap,
                                       Map<String, String> importResolutions)
                                       throws IOException {
        SourceMapGenerator smg = emitSourceMap ? new SourceMapGenerator() : null;
        String luaSource;
        if (smg != null) {
            luaSource = generateWithSourceMap(program, result, sourcePath,
                importResolutions, smg);
        } else {
            luaSource = generateWithImports(program, result, sourcePath, importResolutions);
        }

        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, luaSource);

        // Write source map sidecar
        if (smg != null && smg.hasMappings()) {
            // Normalize both source and generated paths to be project-relative.
            // The project root is inferred as outputRoot/../.. (for a typical
            // build/lua output dir, this yields the project root).  When that
            // fails we fall back to keeping absolute/relative paths consistent.
            String relSourcePath = sourcePath;
            String relGeneratedPath = outputRoot.relativize(outputPath).toString();

            try {
                Path absOutputRoot = outputRoot.toAbsolutePath().normalize();
                Path projectRoot = absOutputRoot.resolve("..").resolve("..").normalize();
                Path absSource = Path.of(sourcePath).toAbsolutePath();

                Path srcRel = projectRoot.relativize(absSource);
                if (!srcRel.startsWith("..")) {
                    relSourcePath = srcRel.toString();
                }

                Path genRel = projectRoot.relativize(outputPath.toAbsolutePath());
                if (!genRel.startsWith("..")) {
                    relGeneratedPath = genRel.toString();
                }
            } catch (IllegalArgumentException e) {
                // Keep fallback paths if relativization fails
            }

            String mapJson = smg.toJson(relSourcePath, relGeneratedPath);
            String baseName = outputPath.getFileName().toString();
            baseName = baseName.substring(0, baseName.lastIndexOf('.'));
            Path mapPath = outputPath.resolveSibling(baseName + ".deal.map.json");
            Files.writeString(mapPath, mapJson);
        }

        Path runtimeDest = outputRoot.resolve("deal/runtime.lua");
        if (!Files.exists(runtimeDest)) {
            Files.createDirectories(runtimeDest.getParent());
            InputStream runtimeStream = LuaBackend.class.getClassLoader()
                .getResourceAsStream("deal/runtime.lua");
            if (runtimeStream != null) {
                Files.copy(runtimeStream, runtimeDest);
                runtimeStream.close();
            } else {
                Path runtimeSrc = Path.of("deal/runtime.lua");
                if (Files.exists(runtimeSrc)) {
                    Files.createDirectories(runtimeDest.getParent());
                    Files.copy(runtimeSrc, runtimeDest);
                }
            }
        }
    }

    public List<Diagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    // =========================================================================
    // Constructor
    // =========================================================================

    private LuaBackend(Map<ExpressionNode, Type> typeMap, SymbolTable symbols) {
        this.typeMap = new HashMap<>(typeMap);
        this.symbols = symbols;
    }

    /**
     * Public constructor for tests that need to capture codegen diagnostics.
     */
    public LuaBackend(Map<ExpressionNode, Type> typeMap, SymbolTable symbols, String sourcePath) {
        this.typeMap = new HashMap<>(typeMap);
        this.symbols = symbols;
        this.sourceFilePath = sourcePath;
    }

    /**
     * Generate Lua source using this instance (for tests that need diagnostics).
     */
    public String generateFromInstance(ProgramNode program) {
        moduleScope = true;
        emitHeader();
        emitLine("");
        walkStatements(program.statements());
        emitJsonableCode();
        emitExports();
        return this.out.toString();
    }

    /**
     * Generate Lua source using this instance with source map tracking.
     * Returns the Lua source; source map can be retrieved via {@link #getSourceMapJson}.
     */
    public String generateFromInstanceWithSourceMap(ProgramNode program) {
        this.sourceMapGenerator = new SourceMapGenerator();
        return generateFromInstance(program);
    }

    /**
     * Returns the source map JSON string, or null if source map generation
     * was not enabled.
     */
    public String getSourceMapJson(String sourcePath, String generatedPath) {
        if (sourceMapGenerator == null || !sourceMapGenerator.hasMappings()) {
            return null;
        }
        return sourceMapGenerator.toJson(sourcePath, generatedPath);
    }

    /**
     * Returns the SourceMapGenerator for inspection in tests.
     */
    public SourceMapGenerator sourceMapGenerator() {
        return sourceMapGenerator;
    }

    // =========================================================================
    // Header / Exports
    // =========================================================================

    private void emitHeader() {
        emitLine("-- Generated by DEAL compiler v0.7");
        emitLine("-- Source: " + sourceFilePath);
        emitLine("");
        emitLine("local __rt = require(\"deal.runtime\")");
        emitLine("");
        emitLine("local __NULL = __rt.__NULL");
        emitLine("local __MISSING = __rt.__MISSING");
        // Intrinsic aliases: int() and number() are direct-call-only in v1.0.
        // Indirect use (assigned to variables / passed as callbacks) fails at
        // runtime because codegen emits .f() for captured function values.
        emitLine("local int = __rt.int_convert");
        emitLine("local number = __rt.number_convert");
        emitLine("");
        // Generated-namespace table: owns all compiler-generated module-level
        // artifacts (Error defaults, class defaults/meta, @jsonable helpers).
        // User bindings cannot shadow table fields.
        emitLine("local " + LuaAbi.NAMESPACE + " = {}");
        emitLine(LuaAbi.namespaceAssignment("Error_defaults",
            "{ code = \"\", message = \"\" }"));
        emitLine("");
    }

    private void emitExports() {
        emitLine("local exports = {}");
        for (var entry : exportedValues.entrySet()) {
            emitLine(LuaAbi.exportAssignment(entry.getKey(), entry.getValue()));
        }
        emitLine("return exports");
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private void emit(String s) { out.append(s); }

    private void emitLine(String s) {
        if (!s.isEmpty()) out.append("  ".repeat(indent));
        out.append(s).append('\n');
    }

    private void emitLine() { out.append('\n'); }

    private void addDiagnostic(DiagnosticCode code, String message, Span span) {
        diagnostics.add(Diagnostic.error(code, message,
            span.file(), span.startLine(), span.startColumn()));
    }

    /** Returns the current 1-based line number in the output buffer. */
    private int currentGeneratedLine() {
        int line = 1;
        for (int i = 0; i < out.length(); i++) {
            if (out.charAt(i) == '\n') line++;
        }
        return line;
    }

    /** Returns the current 1-based column number in the output buffer. */
    private int currentGeneratedColumn() {
        int lastNewline = out.lastIndexOf("\n");
        if (lastNewline == -1) return out.length() + 1;
        return out.length() - lastNewline;
    }

    /** Records a source mapping for the given AST span at the current output position. */
    private void recordMapping(Span span) {
        if (sourceMapGenerator != null && span != null) {
            sourceMapGenerator.emitStatement(
                currentGeneratedLine(), currentGeneratedColumn(), span);
        }
    }

    /** Formats span coordinates for a Lua function call argument list. */
    private String spanArgs(Span span) {
        if (span == null) return "nil, nil, nil";
        return "\"" + escapeLuaStringNoQuotes(span.file()) + "\", "
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

    private String captureOutput(Runnable action) {
        StringBuilder saved = this.out;
        int savedIndent = this.indent;
        this.out = new StringBuilder();
        try {
            action.run();
            return this.out.toString();
        } finally {
            this.out = saved;
            this.indent = savedIndent;
        }
    }

    private String freshLabel(String prefix) {
        return prefix + "_" + (++labelCounter);
    }

    // =========================================================================
    // Type descriptor generation
    // =========================================================================

    private String typeDescriptor(Type t) {
        if (t == null) return "null";
        return switch (t) {
            case Type.Null ignored -> "null";
            case Type.Boolean ignored -> "boolean";
            case Type.Int ignored -> "int";
            case Type.Number ignored -> "number";
            case Type.String ignored -> "string";
            case Type.Table ignored -> "table";
            case Type.Error ignored -> "Error";
            case Type.Array arr -> typeDescriptor(arr.element()) + "[]";
            case Type.Nullable n -> typeDescriptor(n.inner()) + "|null";
            case Type.Class cls -> {
                if (cls.modulePath() != null && !cls.modulePath().isEmpty()) {
                    yield "@" + cls.modulePath() + "/" + cls.name();
                } else {
                    yield cls.name();
                }
            }
            case Type.Func f -> {
                StringBuilder sb = new StringBuilder();
                if (f.isAsync()) sb.append("async");
                sb.append("(");
                for (int i = 0; i < f.paramTypes().size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(typeDescriptor(f.paramTypes().get(i)));
                }
                if (f.restType().isPresent()) {
                    if (!f.paramTypes().isEmpty()) sb.append(",");
                    sb.append("...").append(typeDescriptor(f.restType().get().element()));
                }
                sb.append(")->").append(typeDescriptor(f.returnType()));
                yield sb.toString();
            }
        };
    }

    /**
     * Emits a runtime type check expression.
     */
    private String emitCheckExpr(String valueExpr, Type type) {
        return emitCheckExpr(valueExpr, type, null);
    }

    /**
     * Emits a runtime type check expression with source location information.
     */
    private String emitCheckExpr(String valueExpr, Type type, Span span) {
        if (type == null) return valueExpr;
        String spanParam = spanArgs(span);
        return switch (type) {
            case Type.Null ignored ->
                "__rt.check_null(" + valueExpr + ", " + spanParam + ")";
            case Type.Boolean ignored ->
                "__rt.check_boolean(" + valueExpr + ", " + spanParam + ")";
            case Type.Int ignored ->
                "__rt.check_int(" + valueExpr + ", " + spanParam + ")";
            case Type.Number ignored ->
                "__rt.check_number(" + valueExpr + ", " + spanParam + ")";
            case Type.String ignored ->
                "__rt.check_string(" + valueExpr + ", " + spanParam + ")";
            case Type.Table ignored ->
                "__rt.check_table(" + valueExpr + ", " + spanParam + ")";
            case Type.Array arr ->
                "__rt.check_array(\"" + typeDescriptor(type) + "\", "
                    + valueExpr + ", " + spanParam + ")";
            case Type.Nullable n ->
                "__rt.check_nullable(\"" + typeDescriptor(n.inner()) + "\", "
                    + valueExpr + ", " + spanParam + ")";
            case Type.Class cls ->
                "__rt.check_type(\"" + typeDescriptor(cls) + "\", "
                    + valueExpr + ", " + spanParam + ")";
            case Type.Func f ->
                "__rt.check_type(\"" + typeDescriptor(f) + "\", "
                    + valueExpr + ", " + spanParam + ")";
            default -> valueExpr;
        };
    }

    private String checkFunctionFor(Type type) {
        if (type == null) return null;
        return switch (type) {
            case Type.Null ignored -> "__rt.check_null";
            case Type.Boolean ignored -> "__rt.check_boolean";
            case Type.Int ignored -> "__rt.check_int";
            case Type.Number ignored -> "__rt.check_number";
            case Type.String ignored -> "__rt.check_string";
            case Type.Table ignored -> "__rt.check_table";
            default -> null;
        };
    }

    // =========================================================================
    // Statement walking
    // =========================================================================

    private void walkStatements(List<StatementNode> statements) {
        // DEAL function declarations are hoisted. Lua locals, however, are not
        // visible inside their own initializer (`local f = function() f() end`
        // resolves the inner f as a global). Declare every function in the
        // current statement scope first, then assign its wrapper when visiting
        // the declaration. This supports recursion, forward calls, and mutual
        // recursion while retaining lexical scope.
        for (StatementNode stmt : statements) {
            FunctionDeclaration function = switch (stmt) {
                case FunctionDeclaration fd -> fd;
                case ExportDeclaration ed
                    when ed.declaration() instanceof FunctionDeclaration fd -> fd;
                default -> null;
            };
            if (function != null) {
                emitLine("local " + function.name());
            }
        }
        for (StatementNode stmt : statements) visitStatement(stmt);
    }

    private void visitStatement(StatementNode stmt) {
        // Record source mapping before emitting the statement
        recordMapping(stmt.span());

        switch (stmt) {
            case VariableDeclaration vd -> visit(vd);
            case FunctionDeclaration fd -> visit(fd);
            case ClassDeclaration cd -> visit(cd);
            case ReturnStatement rs -> visit(rs);
            case IfStatement is -> visit(is);
            case WhileStatement ws -> visit(ws);
            case ForStatement fs -> visit(fs);
            case ForOfStatement fos -> visit(fos);
            case BreakStatement bs -> visit(bs);
            case ContinueStatement cs -> visit(cs);
            case ExpressionStatement es -> visit(es);
            case ImportDeclaration id -> visit(id);
            case ExportDeclaration ed -> visit(ed);
            case DeleteStatement ds -> visit(ds);
            case TryStatement ts -> visit(ts);
            case ThrowStatement ts2 -> visit(ts2);
            case Block b -> visit(b);
            default -> addDiagnostic(DiagnosticCode.E6000,
                "unsupported statement type: " + stmt.getClass().getSimpleName(),
                stmt.span());
        }
    }

    // =========================================================================
    // Visitor: Statements
    // =========================================================================

    @Override
    public Void visit(ProgramNode node) {
        moduleScope = true;
        emitHeader();
        walkStatements(node.statements());
        emitJsonableCode();
        emitExports();
        return null;
    }

    @Override
    public Void visit(ClassDeclaration node) {
        String name = node.name();
        StringBuilder defaults = new StringBuilder("{");
        boolean first = true;
        for (ClassField field : node.fields()) {
            if (!first) defaults.append(", ");
            first = false;
            String fieldDefault;
            if (field.optional() && field.defaultExpr().isEmpty()) {
                fieldDefault = "__MISSING";
            } else if (field.defaultExpr().isPresent()) {
                fieldDefault = emitExpression(field.defaultExpr().get());
            } else if (field.nullable()) {
                fieldDefault = "__NULL";
            } else {
                fieldDefault = defaultValueForTypeNode(field.type());
            }
            defaults.append(LuaAbi.tableField(field.name(), fieldDefault));
        }
        defaults.append("}");

        emitLine("-- Class: " + name);
        if (moduleScope) {
            emitLine(LuaAbi.namespaceAssignment(
                LuaAbi.helperKey(name, LuaAbi.HelperKind.DEFAULTS),
                defaults.toString()));
            emitLine(LuaAbi.namespaceAssignment(
                LuaAbi.helperKey(name, LuaAbi.HelperKind.META),
                "__rt.export_class(\"" + name + "\")"));
        } else {
            emitLine("local " + LuaAbi.helperKey(name, LuaAbi.HelperKind.DEFAULTS)
                + " = " + defaults.toString());
            emitLine("local " + LuaAbi.helperKey(name, LuaAbi.HelperKind.META)
                + " = __rt.export_class(\"" + name + "\")");
        }
        emitLine();
        return null;
    }

    private String defaultValueForTypeNode(TypeNode typeNode) {
        return switch (typeNode) {
            case NamedType nt -> switch (nt.name()) {
                case "int" -> "0"; case "number" -> "0.0";
                case "boolean" -> "false"; case "string" -> "\"\"";
                case "null" -> "__NULL"; case "table" -> "{}";
                default -> "{}";
            };
            case NullableType ignored -> "__NULL";
            case ArrayType ignored -> "{}";
            case FunctionType ignored -> "{}";
            default -> "nil";
        };
    }

    @Override
    public Void visit(FunctionDeclaration node) {
        String name = node.name();
        Type.Func funcType = getFunctionType(name);

        // Build parameter list. Rest params use Lua "..." in the function header.
        StringBuilder paramList = new StringBuilder();
        for (int i = 0; i < node.params().size(); i++) {
            if (i > 0) paramList.append(", ");
            paramList.append(node.params().get(i).name());
        }
        boolean hasRest = node.restParam().isPresent();
        if (hasRest) {
            if (!node.params().isEmpty()) paramList.append(", ");
            paramList.append("...");
        }

        String sig = funcType != null ? typeDescriptor(funcType) : "()";
        emitLine(name + " = __rt.function_(\"" + sig
            + "\", function(" + paramList.toString() + ")");

        functionDepth++;
        indent++;
        boolean savedModuleScope = moduleScope;
        moduleScope = false;

        // Emit rest parameter unpacking: local <name> = {...}
        if (hasRest) {
            Parameter rest = node.restParam().get();
            emitLine("local " + rest.name() + " = {...}");
            Type restType = resolveTypeNode(rest.type());
            if (restType instanceof Type.Array arr) {
                emitLine("__rt.check_array(\"" + typeDescriptor(arr)
                    + "\", " + rest.name() + ", "
                    + spanArgs(rest.type().span()) + ")");
            }
        }

        for (Parameter param : node.params()) {
            Type paramType = resolveTypeNode(param.type());
            if (paramType != null && !(paramType instanceof Type.Error)
                && !(paramType instanceof Type.Null)) {
                String checkFn = checkFunctionFor(paramType);
                Span paramSpan = param.type().span();
                if (checkFn != null) {
                    emitLine(checkFn + "(" + param.name() + ", "
                        + spanArgs(paramSpan) + ")");
                } else {
                    emitLine(emitCheckExpr(param.name(), paramType, paramSpan));
                }
            }
        }

        Type savedReturn = currentReturnType;
        currentReturnType = funcType != null ? funcType.returnType() : null;

        if (node.isAsync()) {
            emitLine("return __rt.async_start(function()");
            indent++;
            visit(node.body());
            indent--;
            emitLine("end)");  // close async_start
        } else {
            visit(node.body());
        }

        currentReturnType = savedReturn;
        indent--;
        functionDepth--;
        moduleScope = savedModuleScope;

        emitLine("end)");  // close outer function
        emitLine();
        return null;
    }

    private Type.Func getFunctionType(String name) {
        Symbol sym = symbols.resolve(name);
        if (sym instanceof Symbol.FunctionSymbol fs) return fs.funcType();
        return null;
    }

    @Override
    public Void visit(VariableDeclaration node) {
        String name = node.name();
        boolean hasAnnotation = node.typeAnnotation().isPresent();
        Type targetType = hasAnnotation
            ? resolveTypeNode(node.typeAnnotation().get()) : null;
        Type exprType = typeOf(node.initializer());
        String initLua = emitExpression(node.initializer());

        Span span = node.typeAnnotation().isPresent()
            ? node.typeAnnotation().get().span()
            : node.initializer().span();

        if (hasAnnotation && targetType != null && !(targetType instanceof Type.Error)) {
            if (targetType instanceof Type.Func tf
                && exprType instanceof Type.Func ef
                && isArityExtension(ef, tf)) {
                String adapter = emitArityAdapter(tf, ef, initLua, span);
                emitLine("local " + name + " = " + adapter);
            } else {
                String checked = emitCheckExpr(initLua, targetType, span);
                emitLine("local " + name + " = " + checked);
            }
        } else if (!hasAnnotation && exprType != null
            && node.initializer() instanceof LiteralExpr
            && (exprType instanceof Type.Int || exprType instanceof Type.Boolean
                || exprType instanceof Type.String || exprType instanceof Type.Number
                || exprType instanceof Type.Null)) {
            emitLine("local " + name + " = " + initLua);
        } else {
            Type checkType = targetType != null ? targetType : exprType;
            if (checkType != null && !(checkType instanceof Type.Error)
                && !(checkType instanceof Type.Null)) {
                String checked = emitCheckExpr(initLua, checkType, span);
                emitLine("local " + name + " = " + checked);
            } else {
                emitLine("local " + name + " = " + initLua);
            }
        }
        return null;
    }

    @Override
    public Void visit(ReturnStatement node) {
        if (node.expr().isPresent()) {
            String exprLua = emitExpression(node.expr().get());
            String checkedExpr;
            if (currentReturnType != null
                && !(currentReturnType instanceof Type.Error)
                && !(currentReturnType instanceof Type.Null)) {
                checkedExpr = emitCheckExpr(exprLua, currentReturnType,
                    node.expr().get().span());
            } else {
                checkedExpr = exprLua;
            }

            if (tryReturnFlag != null) {
                emitLine(tryReturnFlag + " = true");
                emitLine(tryReturnVal + " = " + checkedExpr);
            }
            emitLine("return " + checkedExpr);
        } else {
            // F2: bare return in null-typed function must return __NULL, not nil
            if (currentReturnType instanceof Type.Null) {
                if (tryReturnFlag != null) {
                    emitLine(tryReturnFlag + " = true");
                    emitLine(tryReturnVal + " = __NULL");
                }
                emitLine("return __NULL");
            } else {
                if (tryReturnFlag != null) {
                    emitLine(tryReturnFlag + " = true");
                }
                emitLine("return");
            }
        }
        return null;
    }

    @Override
    public Void visit(IfStatement node) {
        boolean savedModuleScope = moduleScope;
        moduleScope = false;
        String condLua = "__rt.check_boolean(" + emitExpression(node.condition())
            + ", " + spanArgs(node.condition().span()) + ")";
        emitLine("if " + condLua + " then");
        indent++;
        visit(node.thenBlock());
        indent--;

        if (node.elseBranch().isPresent()) {
            switch (node.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left -> {
                    IfStatement elseIf = left.value();
                    emitLine("elseif __rt.check_boolean("
                        + emitExpression(elseIf.condition())
                        + ", " + spanArgs(elseIf.condition().span()) + ") then");
                    indent++;
                    visit(elseIf.thenBlock());
                    indent--;
                    emitElseChain(elseIf);
                }
                case Either.Right<IfStatement, Block> right -> {
                    emitLine("else");
                    indent++;
                    visit(right.value());
                    indent--;
                    emitLine("end");
                }
            }
        } else {
            emitLine("end");
        }
        moduleScope = savedModuleScope;
        return null;
    }

    private void emitElseChain(IfStatement node) {
        if (node.elseBranch().isPresent()) {
            switch (node.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left -> {
                    IfStatement elseIf = left.value();
                    emitLine("elseif __rt.check_boolean("
                        + emitExpression(elseIf.condition())
                        + ", " + spanArgs(elseIf.condition().span()) + ") then");
                    indent++;
                    visit(elseIf.thenBlock());
                    indent--;
                    emitElseChain(elseIf);
                }
                case Either.Right<IfStatement, Block> right -> {
                    emitLine("else");
                    indent++;
                    visit(right.value());
                    indent--;
                    emitLine("end");
                }
            }
        } else {
            emitLine("end");
        }
    }

    @Override
    public Void visit(WhileStatement node) {
        boolean savedModuleScope = moduleScope;
        moduleScope = false;
        String condLua = "__rt.check_boolean(" + emitExpression(node.condition())
            + ", " + spanArgs(node.condition().span()) + ")";

        String savedLabel = currentContinueLabel;
        String loopLabel = freshLabel("__continue");
        currentContinueLabel = loopLabel;

        emitLine("while " + condLua + " do");
        indent++;
        visit(node.body());
        emitLine("::" + loopLabel + "::");
        indent--;
        emitLine("end");

        currentContinueLabel = savedLabel;
        moduleScope = savedModuleScope;
        return null;
    }

    @Override
    public Void visit(ForStatement node) {
        boolean savedModuleScope = moduleScope;
        moduleScope = false;
        emitLine("-- DEAL for-loop (v0.6 lowering)");
        emitLine("do");
        indent++;

        // Determine if this is a let-declared loop variable (shadow-local pattern).
        // When the for-init is a let declaration, we create a shadow variable _name
        // for the outer counter and remap condition/update references to it.
        String loopVarName = null;
        boolean hasLetInit = false;

        if (node.init().isPresent()) {
            ForInit init = node.init().get();
            if (init instanceof ForInit.VarDecl) {
                hasLetInit = true;
                loopVarName = ((ForInit.VarDecl) init).decl().name();
            }
        }

        if (node.init().isPresent()) {
            ForInit init = node.init().get();
            switch (init) {
                case ForInit.VarDecl vd -> {
                    VariableDeclaration decl = vd.decl();
                    Type varType = decl.typeAnnotation().isPresent()
                        ? resolveTypeNode(decl.typeAnnotation().get()) : null;
                    String initExpr = emitExpression(decl.initializer());
                    // Emit the outer counter as "_name" instead of "name"
                    String shadowName = "_" + decl.name();
                    Span initSpan = decl.typeAnnotation().isPresent()
                        ? decl.typeAnnotation().get().span()
                        : decl.initializer().span();
                    if (varType != null && !(varType instanceof Type.Error)) {
                        emitLine("local " + shadowName + " = "
                            + emitCheckExpr(initExpr, varType, initSpan));
                    } else {
                        emitLine("local " + shadowName + " = " + initExpr);
                    }
                }
                case ForInit.AssignExpr ae -> visit(ae.expr());
            }
        }

        // Condition: remap loop variable references to the shadow name
        String savedShadowVar = this.forLoopShadowVar;
        this.forLoopShadowVar = hasLetInit ? loopVarName : null;
        Span condSpan = node.condition().isPresent()
            ? node.condition().get().span() : null;
        String condStr = node.condition().isPresent()
            ? "__rt.check_boolean(" + emitExpression(node.condition().get())
                + ", " + spanArgs(condSpan) + ")"
            : "true";
        this.forLoopShadowVar = savedShadowVar;

        String savedLabel = currentContinueLabel;
        String loopLabel = freshLabel("__continue");
        currentContinueLabel = loopLabel;

        emitLine("while " + condStr + " do");
        indent++;

        // Per-iteration fresh binding: copy the outer counter into a new local
        if (hasLetInit) {
            emitLine("local " + loopVarName + " = _" + loopVarName);
        }

        visit(node.body());
        // Continue landing pad: placed before the update so that continue
        // in a C-style for-loop jumps to the update step, then re-checks condition.
        emitLine("::" + loopLabel + "::");
        if (node.update().isPresent()) {
            // Update: remap loop variable references to the shadow name
            this.forLoopShadowVar = hasLetInit ? loopVarName : null;
            emitLine(emitExpression(node.update().get()));
            this.forLoopShadowVar = savedShadowVar;
        }
        indent--;
        emitLine("end");  // while
        indent--;
        emitLine("end");  // do

        currentContinueLabel = savedLabel;
        moduleScope = savedModuleScope;
        return null;
    }

    @Override
    public Void visit(ForOfStatement node) {
        boolean savedModuleScope = moduleScope;
        moduleScope = false;
        Type iterableType = typeOf(node.iterable());

        emitLine("do");
        indent++;

        String iterableExpr = emitExpression(node.iterable());
        String varName = node.varName();

        String savedLabel = currentContinueLabel;
        String loopLabel = freshLabel("__continue");
        currentContinueLabel = loopLabel;

        if (iterableType instanceof Type.Array) {
            emitLine("for __i, " + varName + " in ipairs(" + iterableExpr + ") do");
        } else {
            // D17: Hoist iterable expression to local for single evaluation
            emitLine("local __iterable = " + iterableExpr);
            emitLine("for __i = 1, #__iterable do");
            indent++;
            emitLine("local " + varName + " = string.sub(__iterable, __i, __i)");
            indent--;
        }

        indent++;
        visit(node.body());
        emitLine("::" + loopLabel + "::");
        indent--;
        emitLine("end");  // for

        indent--;
        emitLine("end");  // do

        currentContinueLabel = savedLabel;
        moduleScope = savedModuleScope;
        return null;
    }

    @Override
    public Void visit(BreakStatement node) {
        if (tryBreakFlag != null) {
            // Inside a try that is inside a loop: set flag and exit pcall
            emitLine(tryBreakFlag + " = true");
            emitLine("return");
        } else {
            emitLine("break");
        }
        return null;
    }

    @Override
    public Void visit(ContinueStatement node) {
        if (tryContinueFlag != null) {
            // Inside a try that is inside a loop: set flag and exit pcall
            emitLine(tryContinueFlag + " = true");
            emitLine("return");
        } else if (currentContinueLabel != null) {
            emitLine("goto " + currentContinueLabel);
        } else {
            addDiagnostic(DiagnosticCode.E6001, "continue outside loop", node.span());
        }
        return null;
    }

    @Override
    public Void visit(ExpressionStatement node) {
        String expr = emitExpression(node.expr());
        if (expr.startsWith("(")) {
            emitLine(";" + expr);
        } else {
            emitLine(expr);
        }
        return null;
    }

    @Override
    public Void visit(ImportDeclaration node) {
        String requirePath = importResolutions.getOrDefault(
            node.modulePath(), node.modulePath());
        emitLine("local " + node.alias() + " = require(\""
            + requirePath + "\")");
        return null;
    }

    @Override
    public Void visit(ExportDeclaration node) {
        switch (node.declaration()) {
            case FunctionDeclaration fd -> {
                exportedValues.putIfAbsent(fd.name(), fd.name());
                visit(fd);
            }
            case ClassDeclaration cd -> {
                exportedValues.putIfAbsent(cd.name(),
                    LuaAbi.helperRef(cd.name(), LuaAbi.HelperKind.META));
                // Also export the defaults table so importing modules
                // can construct instances of this class.
                exportedValues.putIfAbsent(
                    LuaAbi.helperKey(cd.name(), LuaAbi.HelperKind.DEFAULTS),
                    LuaAbi.helperRef(cd.name(), LuaAbi.HelperKind.DEFAULTS));
                visit(cd);

                // ISSUE-0050: @jsonable deferred codegen
                if (cd.isJsonable()) {
                    // Record metadata for deferred emission
                    deferredJsonables.add(new JsonableClassMeta(
                        cd.name(), cd.fields()));
                    // Register exports for generated jsonable artifacts
                    exportedValues.putIfAbsent(
                        LuaAbi.helperKey(cd.name(), LuaAbi.HelperKind.FIELDS),
                        LuaAbi.helperRef(cd.name(), LuaAbi.HelperKind.FIELDS));
                    exportedValues.putIfAbsent(
                        LuaAbi.helperKey(cd.name(), LuaAbi.HelperKind.FROM_JSON),
                        LuaAbi.helperRef(cd.name(), LuaAbi.HelperKind.FROM_JSON));
                    exportedValues.putIfAbsent(
                        LuaAbi.helperKey(cd.name(), LuaAbi.HelperKind.TO_JSON),
                        LuaAbi.helperRef(cd.name(), LuaAbi.HelperKind.TO_JSON));
                }
            }
            default -> {}
        }
        return null;
    }

    @Override
    public Void visit(DeleteStatement node) {
        if (node.target() instanceof IndexExpr idx) {
            Type arrType = typeOf(idx.array());
            if (arrType instanceof Type.Array) {
                String arr = emitExpression(idx.array());
                String index = emitExpression(idx.index());
                String myIndent = "  ".repeat(indent);
                emitLine("do");
                indent++;
                emitLine("local __arr = " + arr);
                emitLine("local __idx = __rt.check_int(" + index
                    + ", " + spanArgs(idx.span()) + ")");
                emitLine("if __idx < 0 or __idx > #__arr then error(__rt._err(\"E8002\", "
                    + "\"array index out of bounds\", " + spanArgs(idx.span()) + ")) end");
                emitLine("__arr[__idx + 1] = nil");
                indent--;
                emitLine("end");
                return null;
            }
        }
        emitLine(emitExpression(node.target()) + " = nil");
        return null;
    }

    @Override
    public Void visit(TryStatement node) {
        boolean savedModuleScope = moduleScope;
        moduleScope = false;
        // ---- Save try-return flags ----
        String savedFlag = tryReturnFlag;
        String savedVal = tryReturnVal;
        String myFlag = null;
        String myVal = null;

        if (functionDepth > 0) {
            int tryId = ++labelCounter;
            myFlag = "__try_returned_" + tryId;
            myVal = "__try_return_val_" + tryId;
            tryReturnFlag = myFlag;
            tryReturnVal = myVal;

            emitLine("local " + myFlag + " = false");
            emitLine("local " + myVal + " = nil");
        }

        // ---- Save and allocate try-break/continue flags ----
        boolean inLoop = currentContinueLabel != null;
        String savedBreakFlag = tryBreakFlag;
        String savedContinueFlag = tryContinueFlag;
        String myBreakFlag = null;
        String myContinueFlag = null;

        if (inLoop) {
            int tryId = ++labelCounter;
            myBreakFlag = "__try_break_" + tryId;
            myContinueFlag = "__try_continue_" + tryId;
            tryBreakFlag = myBreakFlag;
            tryContinueFlag = myContinueFlag;

            emitLine("local " + myBreakFlag + " = false");
            emitLine("local " + myContinueFlag + " = false");
        }

        // ---- Emit pcall wrapper ----
        emitLine("local __ok, __err = pcall(function()");
        indent++;
        insideTryDepth++;
        visit(node.tryBlock());
        insideTryDepth--;
        indent--;
        emitLine("end)");

        // ---- Restore ALL flags before catch block (catch is outside pcall) ----
        tryReturnFlag = savedFlag;
        tryReturnVal = savedVal;
        tryBreakFlag = savedBreakFlag;
        tryContinueFlag = savedContinueFlag;

        // ---- Catch block ----
        emitLine("if not __ok then");
        indent++;
        emitLine("local " + node.catchVar());
        emitLine("if type(__err) == \"table\" and __err.code ~= nil then");
        indent++;
        emitLine(node.catchVar() + " = __err");
        indent--;
        emitLine("else");
        indent++;
        emitLine(node.catchVar()
            + " = { code = \"E8001\", message = tostring(__err) }");
        indent--;
        emitLine("end");
        visit(node.catchBlock());
        indent--;

        // ---- Try-return flag check (after catch, continues elseif chain) ----
        if (functionDepth > 0) {
            emitLine("elseif " + myFlag + " then");
            indent++;
            // Propagate to outer try's flag for nested try/catch
            if (savedFlag != null) {
                emitLine(savedFlag + " = true");
                emitLine(savedVal + " = " + myVal);
            }
            emitLine("return " + myVal);
            indent--;
        }

        // ---- Try-break/continue flag checks (ISSUE-0011) ----
        if (inLoop) {
            if (functionDepth > 0) {
                // Continue the elseif chain
                emitLine("elseif " + myBreakFlag + " then");
            } else {
                // Close the catch if-block first, start a new if
                emitLine("end");
                emitLine("if " + myBreakFlag + " then");
            }
            indent++;
            if (savedBreakFlag != null) {
                // Nested try: propagate to outer try's break flag
                emitLine(savedBreakFlag + " = true");
                emitLine("return");
            } else {
                // Outermost try: real break
                emitLine("break");
            }
            indent--;

            emitLine("elseif " + myContinueFlag + " then");
            indent++;
            if (savedContinueFlag != null) {
                // Nested try: propagate to outer try's continue flag
                emitLine(savedContinueFlag + " = true");
                emitLine("return");
            } else {
                // Outermost try: real continue via goto
                emitLine("goto " + currentContinueLabel);
            }
            indent--;
        }

        emitLine("end");
        moduleScope = savedModuleScope;
        return null;
    }

    @Override
    public Void visit(ThrowStatement node) {
        Span throwSpan = node.span();
        if (node.expr() instanceof ObjectLiteralExpr objLit) {
            // Include default Error fields when not provided
            Set<String> providedFields = new HashSet<>();
            for (Property prop : objLit.properties()) {
                providedFields.add(prop.name());
            }

            StringBuilder sb = new StringBuilder("error({");
            boolean first = true;
            for (Property prop : objLit.properties()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(prop.name()).append(" = ")
                    .append(emitExpression(prop.value()));
            }
            {
                if (!providedFields.contains("code")) {
                    if (!first) sb.append(", ");
                    first = false;
                    sb.append("code = \"\"");
                }
                if (!providedFields.contains("message")) {
                    if (!first) sb.append(", ");
                    first = false;
                    sb.append("message = \"\"");
                }
            }
            // Add source location to the error object
            sb.append(", file = \"")
                .append(escapeLuaStringNoQuotes(throwSpan.file()))
                .append("\"");
            sb.append(", line = ").append(throwSpan.startLine());
            sb.append(", column = ").append(throwSpan.startColumn());
            sb.append("})");
            emitLine(sb.toString());
        } else {
            emitLine("error(" + emitExpression(node.expr()) + ")");
        }
        return null;
    }

    @Override
    public Void visit(Block node) {
        // A bare block is a lexical container like every other nesting
        // container: a class declared inside it is block-scoped, not
        // module-level, so its artifacts keep the scope-local form
        // (local <C>_defaults / local <C>_meta) and never write
        // __deal namespace keys (lua-abi-emission-layer D2.6).
        boolean savedModuleScope = moduleScope;
        moduleScope = false;
        walkStatements(node.statements());
        moduleScope = savedModuleScope;
        return null;
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
            case AwaitExpression await -> "coroutine.yield(" + emitExpression(await.callee()) + ")";
            case TemplateLiteralExpr tl -> emitTemplateLiteral(tl);
        };
    }

    @Override public Void visit(LiteralExpr node) {
        emitLine(emitLiteral(node)); return null;
    }

    private String emitLiteral(LiteralExpr lit) {
        return switch (lit.value()) {
            case LiteralValue.NullLiteral n -> "__NULL";
            case LiteralValue.BooleanLiteral b -> b.value() ? "true" : "false";
            case LiteralValue.IntLiteral i -> Long.toString(i.value());
            case LiteralValue.NumberLiteral n -> {
                double v = n.value();
                if (Double.isNaN(v)) yield "(0/0)";
                if (Double.isInfinite(v)) yield v > 0 ? "(1/0)" : "(-1/0)";
                yield Double.toString(v);
            }
            case LiteralValue.StringLiteral s -> escapeLuaString(s.value());
        };
    }

    @Override public Void visit(IdentifierExpr node) {
        emitLine(emitIdentifier(node)); return null;
    }

    /**
     * Emit an identifier. When forLoopShadowVar is set and the identifier
     * name matches, emits the shadow name (prefixed with "_") so that the
     * condition and update expressions in a for-let loop reference the
     * outer counter.
     */
    private String emitIdentifier(IdentifierExpr id) {
        String name = id.name();
        if (forLoopShadowVar != null && name.equals(forLoopShadowVar)) {
            return "_" + name;
        }
        if (name.indexOf('$') >= 0) {
            return LuaAbi.generatedRef(name);
        }
        return name;
    }

    @Override public Void visit(BinaryExpr node) {
        emitLine(emitBinary(node)); return null;
    }

    private String emitBinary(BinaryExpr bin) {
        Type leftType = typeOf(bin.left());
        Type rightType = typeOf(bin.right());
        String left = emitExpression(bin.left());
        String right = emitExpression(bin.right());
        BinaryOp op = bin.op();
        Span span = bin.span();
        String spanParam = spanArgs(span);

        if (op == BinaryOp.ADD && leftType instanceof Type.String
            && rightType instanceof Type.String) {
            return left + " .. " + right;
        }

        if (leftType instanceof Type.Int && rightType instanceof Type.Int) {
            return switch (op) {
                case ADD -> "__rt.int_add(" + left + ", " + right
                    + ", " + spanParam + ")";
                case SUB -> "__rt.int_sub(" + left + ", " + right
                    + ", " + spanParam + ")";
                case MUL -> "__rt.int_mul(" + left + ", " + right
                    + ", " + spanParam + ")";
                case DIV -> "__rt.int_div(" + left + ", " + right
                    + ", " + spanParam + ")";
                case MOD -> "__rt.int_mod(" + left + ", " + right
                    + ", " + spanParam + ")";
                case POW -> "__rt.int_pow(" + left + ", " + right
                    + ", " + spanParam + ")";
                default -> "(" + left + " " + opSymbolLua(op) + " " + right + ")";
            };
        }

        if (leftType instanceof Type.Number || rightType instanceof Type.Number) {
            return switch (op) {
                case ADD -> "(" + left + " + " + right + ")";
                case SUB -> "(" + left + " - " + right + ")";
                case MUL -> "(" + left + " * " + right + ")";
                case DIV -> "(" + left + " / " + right + ")";
                case MOD -> "(" + left + " % " + right + ")";
                case POW -> "(" + left + " ^ " + right + ")";
                default -> "(" + left + " " + opSymbolLua(op) + " " + right + ")";
            };
        }

        // Nullable-vs-nullable comparison
        if (leftType instanceof Type.Nullable && rightType instanceof Type.Nullable) {
            if (op == BinaryOp.EQ) {
                return "(" + left + " == " + right + " or ("
                    + "(" + left + " == nil or " + left + " == __NULL) and ("
                    + right + " == nil or " + right + " == __NULL)))";
            }
            if (op == BinaryOp.NEQ) {
                return "(not (" + left + " == " + right + " or ("
                    + "(" + left + " == nil or " + left + " == __NULL) and ("
                    + right + " == nil or " + right + " == __NULL))))";
            }
        }

        if (op == BinaryOp.EQ && isNullLiteral(bin.right()) && leftType instanceof Type.Nullable) {
            return "(" + left + " == nil or " + left + " == __NULL)";
        }
        if (op == BinaryOp.EQ && isNullLiteral(bin.left()) && rightType instanceof Type.Nullable) {
            return "(" + right + " == nil or " + right + " == __NULL)";
        }
        if (op == BinaryOp.NEQ && isNullLiteral(bin.right()) && leftType instanceof Type.Nullable) {
            return "(" + left + " ~= nil and " + left + " ~= __NULL)";
        }
        if (op == BinaryOp.NEQ && isNullLiteral(bin.left()) && rightType instanceof Type.Nullable) {
            return "(" + right + " ~= nil and " + right + " ~= __NULL)";
        }
        if (op == BinaryOp.EQ) return "(" + left + " == " + right + ")";
        if (op == BinaryOp.NEQ) return "(" + left + " ~= " + right + ")";
        if (op == BinaryOp.LT) return "(" + left + " < " + right + ")";
        if (op == BinaryOp.LTE) return "(" + left + " <= " + right + ")";
        if (op == BinaryOp.GT) return "(" + left + " > " + right + ")";
        if (op == BinaryOp.GTE) return "(" + left + " >= " + right + ")";
        if (op == BinaryOp.AND) return "(" + left + " and " + right + ")";
        if (op == BinaryOp.OR) return "(" + left + " or " + right + ")";

        return "(" + left + " " + opSymbolLua(op) + " " + right + ")";
    }

    @Override public Void visit(UnaryExpr node) {
        emitLine(emitUnary(node)); return null;
    }

    private String emitUnary(UnaryExpr un) {
        String expr = emitExpression(un.expr());
        return switch (un.op()) {
            case NOT -> "(not (" + expr + "))";
            case NEG -> "(-" + expr + ")";
        };
    }

    @Override public Void visit(CallExpr node) {
        emitLine(emitCall(node)); return null;
    }

    private String emitCall(CallExpr call) {
        Type calleeType = typeOf(call.callee());
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < call.args().size(); i++) {
            if (i > 0) args.append(", ");
            args.append(emitExpression(call.args().get(i)));
        }
        // Intrinsic calls (int, number) — emit direct call, not .f()
        // KNOWN LIMIT (v1.0): only direct calls (callee is an IdentifierExpr
        // resolving to an IntrinsicSymbol) are intercepted. Indirect use
        // (assigned to variables / passed as callbacks) will still emit .f()
        // and fail at runtime because the aliases are plain Lua functions,
        // not function wrappers.
        if (call.callee() instanceof IdentifierExpr id) {
            Symbol sym = symbols.resolve(id.name());
            if (sym instanceof Symbol.IntrinsicSymbol) {
                return emitExpression(call.callee()) + "(" + args.toString() + ", " + spanArgs(call.span()) + ")";
            }
        }
        if (calleeType instanceof Type.Func) {
            return emitExpression(call.callee()) + ".f(" + args.toString() + ")";
        }
        return emitExpression(call.callee()) + "(" + args.toString() + ")";
    }

    @Override public Void visit(MemberAccessExpr node) {
        emitLine(emitMemberAccess(node)); return null;
    }

    private String emitMemberAccess(MemberAccessExpr mae) {
        String obj = emitExpression(mae.object());
        Type objType = typeOf(mae.object());
        String field = mae.field();
        if (field.equals("length") && objType instanceof Type.Array) {
            return "#" + obj;
        }
        return LuaAbi.memberAccess(obj, field);
    }

    @Override public Void visit(IndexExpr node) {
        String idx = emitIndex(node);
        if (idx.startsWith("(")) {
            emitLine(";" + idx);
        } else {
            emitLine(idx);
        }
        return null;
    }

    private String emitIndex(IndexExpr idx) {
        Type arrayType = typeOf(idx.array());
        String arr = emitExpression(idx.array());
        String index = emitExpression(idx.index());
        Span span = idx.span();
        if (arrayType instanceof Type.Array) {
            return "(function() local __idx = __rt.check_int(" + index + ", "
                + spanArgs(span) + "); if __idx < 0 then error(__rt._err(\"E8002\", "
                + "\"negative array index\", " + spanArgs(span) + ")) end; "
                + "return " + arr + "[__idx + 1] end)()";
        }
        return arr + "[" + index + "]";
    }

    @Override public Void visit(ArrayLiteralExpr node) {
        emitLine(emitArrayLiteral(node)); return null;
    }

    private String emitArrayLiteral(ArrayLiteralExpr arr) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < arr.elements().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(emitExpression(arr.elements().get(i)));
        }
        sb.append("}");
        return sb.toString();
    }

    @Override public Void visit(ObjectLiteralExpr node) {
        emitLine(emitObjectLiteral(node)); return null;
    }

    private String emitObjectLiteral(ObjectLiteralExpr obj) {
        Type expectedType = typeOf(obj);
        if (expectedType instanceof Type.Class cls) {
            return emitClassConstruction(cls, obj);
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Property prop : obj.properties()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(LuaAbi.tableField(prop.name(),
                emitExpression(prop.value())));
        }
        sb.append("}");
        return sb.toString();
    }

    private String emitClassConstruction(Type.Class cls, ObjectLiteralExpr obj) {
        String className = cls.name();
        Symbol sym = symbols.resolve(className);

        StringBuilder provided = new StringBuilder("{");
        boolean first = true;
        for (Property prop : obj.properties()) {
            if (!first) provided.append(", ");
            first = false;
            provided.append(LuaAbi.tableField(prop.name(),
                emitExpression(prop.value())));
        }
        provided.append("}");

        String defaultsRef;
        if (sym instanceof Symbol.ClassSymbol cs) {
            // Root ClassSymbol (module-level class, including the seeded
            // Error): the artifact lives in the __deal namespace table.
            defaultsRef = LuaAbi.helperRef(className, LuaAbi.HelperKind.DEFAULTS);
        } else if (cls.modulePath() != null && !cls.modulePath().isEmpty()) {
            // Imported class: find the import alias and use alias._defaults
            String alias = findImportAliasForClass(className, cls.modulePath());
            if (alias != null) {
                defaultsRef = LuaAbi.memberAccess(alias,
                    LuaAbi.helperKey(className, LuaAbi.HelperKind.DEFAULTS));
            } else {
                defaultsRef = "{}";
            }
        } else {
            defaultsRef = "{}";
        }

        Span cspan = obj.span();
        return "__rt.class_(\"" + className + "\", " + defaultsRef
            + ", " + provided.toString() + ", " + spanArgs(cspan) + ")";
    }

    /**
     * Searches the root symbol table for a ModuleSymbol whose exports
     * include the given class with the exact module path. Returns the
     * import alias (module local name) or {@code null} if not found.
     *
     * <p>The {@code modulePath} parameter is essential: when two imported
     * modules export classes with the same name (e.g., both {@code mod1}
     * and {@code mod2} export {@code class Result}), checking only the
     * class name would return the wrong alias and produce incorrect
     * defaults-table references.</p>
     */
    private String findImportAliasForClass(String className, String modulePath) {
        for (var entry : symbols.symbols().entrySet()) {
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

    @Override public Void visit(FunctionExpr node) {
        emitLine(emitFunctionExpr(node)); return null;
    }

    private String emitFunctionExpr(FunctionExpr fe) {
        // Build parameter list. Rest params use Lua "..." in the function header.
        StringBuilder paramList = new StringBuilder();
        for (int i = 0; i < fe.params().size(); i++) {
            if (i > 0) paramList.append(", ");
            paramList.append(fe.params().get(i).name());
        }
        boolean hasRest = fe.restParam().isPresent();
        if (hasRest) {
            if (!fe.params().isEmpty()) paramList.append(", ");
            paramList.append("...");
        }

        Type funcType = typeOf(fe);
        String sig = funcType instanceof Type.Func f ? typeDescriptor(f) : "()";

        Type savedReturn = currentReturnType;
        if (funcType instanceof Type.Func f) currentReturnType = f.returnType();

        int savedIndent = indent;
        indent = 0;
        functionDepth++;

        String bodyStr = captureOutput(() -> {
            boolean savedModuleScope = moduleScope;
            moduleScope = false;
            indent = 1;

            // Emit rest parameter unpacking: local <name> = {...}
            if (hasRest) {
                Parameter rest = fe.restParam().get();
                emitLine("local " + rest.name() + " = {...}");
                Type restType = resolveTypeNode(rest.type());
                if (restType instanceof Type.Array arr) {
                    emitLine("__rt.check_array(\"" + typeDescriptor(arr)
                        + "\", " + rest.name() + ", "
                        + spanArgs(rest.type().span()) + ")");
                }
            }

            for (Parameter param : fe.params()) {
                Type paramType = resolveTypeNode(param.type());
                if (paramType != null && !(paramType instanceof Type.Error)
                    && !(paramType instanceof Type.Null)) {
                    String checkFn = checkFunctionFor(paramType);
                    Span paramSpan = param.type().span();
                    if (checkFn != null) {
                        emitLine(checkFn + "(" + param.name() + ", "
                            + spanArgs(paramSpan) + ")");
                    } else {
                        emitLine(emitCheckExpr(param.name(), paramType, paramSpan));
                    }
                }
            }
            if (fe.isAsync()) {
                emitLine("return __rt.async_start(function()");
                indent++;
                visit(fe.body());
                indent--;
                emitLine("end)");  // close async_start
            } else {
                visit(fe.body());
            }
            moduleScope = savedModuleScope;
        });

        functionDepth--;
        currentReturnType = savedReturn;
        indent = savedIndent;

        return "__rt.function_(\"" + sig + "\", function("
            + paramList.toString() + ")\n" + bodyStr
            + "  ".repeat(indent) + "end)";
    }

    @Override public Void visit(HasExpr node) {
        emitLine(emitHas(node)); return null;
    }

    private String emitHas(HasExpr has) {
        return LuaAbi.hasCheck(emitExpression(has.object()), has.field());
    }

    @Override public Void visit(AssignmentExpr node) {
        emitLine(emitAssignment(node)); return null;
    }

    @Override public Void visit(AwaitExpression node) {
        emitLine(emitExpression(node));
        return null;
    }

    private String emitTemplateLiteral(TemplateLiteralExpr tl) {
        List<ExpressionNode> parts = tl.parts();
        if (parts.size() == 1) {
            // No interpolations: emit plain string literal
            return emitExpression(parts.get(0));
        }
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        for (int i = 0; i < parts.size(); i++) {
            ExpressionNode part = parts.get(i);
            if (i % 2 == 0) {
                // String part: skip if empty
                String emitted = emitExpression(part);
                if (emitted.equals("\"\"") || emitted.equals("''")) {
                    continue;
                }
                if (!first) sb.append(" .. ");
                sb.append(emitted);
                first = false;
            } else {
                // Expression part
                if (!first) sb.append(" .. ");
                sb.append(emitExpression(part));
                first = false;
            }
        }
        sb.append(")");
        return sb.toString();
    }

    private String emitAssignment(AssignmentExpr assign) {
        Type targetType = typeOf(assign.target());
        Type valueType = typeOf(assign.value());
        String targetLua = emitExpression(assign.target());
        String valueLua = emitExpression(assign.value());
        Span span = assign.span();

        if (isArityExtension(valueType, targetType)) {
            return targetLua + " = "
                + emitArityAdapter((Type.Func) targetType, (Type.Func) valueType, valueLua, span);
        }

        if (assign.target() instanceof IndexExpr idx) {
            Type arrType = typeOf(idx.array());
            if (arrType instanceof Type.Array arrT) {
                String arr = emitExpression(idx.array());
                String index = emitExpression(idx.index());

                if (idx.index() instanceof MemberAccessExpr mae
                    && mae.field().equals("length")
                    && typeOf(mae.object()) instanceof Type.Array) {
                    return arr + "[#" + arr + " + 1] = "
                        + emitCheckExpr(valueLua, arrT.element(), span);
                }

                String myIndent = "  ".repeat(indent);
                return "do\n"
                    + myIndent + "  local __arr = " + arr + "\n"
                    + myIndent + "  local __idx = __rt.check_int(" + index
                    + ", " + spanArgs(idx.span()) + ")\n"
                    + myIndent + "  if __idx < 0 or __idx > #__arr then error(__rt._err(\"E8002\", "
                    + "\"array index out of bounds\", " + spanArgs(idx.span()) + ")) end\n"
                    + myIndent + "  __arr[__idx + 1] = "
                    + emitCheckExpr(valueLua, arrT.element(), span) + "\n"
                    + myIndent + "end";
            }
        }

        return targetLua + " = " + valueLua;
    }

    private boolean isArityExtension(Type valueType, Type targetType) {
        if (valueType instanceof Type.Func vf && targetType instanceof Type.Func tf) {
            return Types.isAssignable(vf, tf) && !Types.equals(vf, tf)
                && vf.paramTypes().size() < tf.paramTypes().size();
        }
        return false;
    }

    private String emitArityAdapter(Type.Func targetFunc, Type.Func valueFunc,
                                     String valueLua, Span span) {
        StringBuilder sb = new StringBuilder();
        sb.append("__rt.function_(\"").append(typeDescriptor(targetFunc))
            .append("\", function(");
        for (int i = 0; i < targetFunc.paramTypes().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("__p").append(i);
        }
        sb.append(")\n");

        String bodyIndent = "  ".repeat(indent + 1);
        for (int i = 0; i < targetFunc.paramTypes().size(); i++) {
            Type pt = targetFunc.paramTypes().get(i);
            sb.append(bodyIndent).append(emitCheckExpr("__p" + i, pt, span)).append("\n");
        }

        sb.append(bodyIndent).append("return ");
        sb.append(emitCheckExpr(
            valueLua + ".f(" + buildOverlappingArgs(valueFunc.paramTypes().size()) + ")",
            targetFunc.returnType(), span));
        sb.append("\n");
        sb.append("  ".repeat(indent)).append("end)");
        return sb.toString();
    }

    private String buildOverlappingArgs(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append("__p").append(i);
        }
        return sb.toString();
    }

    // =========================================================================
    // Type node visitors (no-op)
    // =========================================================================

    @Override public Void visit(NamedType node) { return null; }
    @Override public Void visit(ArrayType node) { return null; }
    @Override public Void visit(NullableType node) { return null; }
    @Override public Void visit(FunctionType node) { return null; }

    // =========================================================================
    // ISSUE-0050: @jsonable codegen — deferred two-pass emission with
    // topological sort
    // =========================================================================

    /**
     * Metadata for a single @jsonable class, recorded during statement
     * walking and processed during {@link #emitJsonableCode()}.
     * No dependency names are pre-computed — the dependency graph is
     * built from scratch in emitJsonableCode() when deferredJsonables
     * is complete.
     */
    private static final class JsonableClassMeta {
        final String className;
        final List<ClassField> fields;

        JsonableClassMeta(String className, List<ClassField> fields) {
            this.className = className;
            this.fields = fields;
        }
    }

    /**
     * Emits all deferred @jsonable code after all statements have been walked.
     *
     * <p>Performs two sub-passes after topological sort by same-module
     * class-typed field dependencies:
     * <ol>
     *   <li>Field descriptor tables ({@code C_fields}) in dependency order.</li>
     *   <li>{@code C$fromJson} and {@code C$toJson} functions in the same order.</li>
     * </ol>
     *
     * <p>Because same-module circular @jsonable class dependencies are caught
     * as E4008 in Phase 3 (TypeChecker), the topological sort here is
     * guaranteed acyclic — no cycle error is emitted during codegen.</p>
     */
    private void emitJsonableCode() {
        if (deferredJsonables.isEmpty()) return;

        // Emit JSON module loading only when there are @jsonable classes.
        // This conditional emission avoids an unconditional require("std.json")
        // at the top of every module including non-jsonable ones, which would
        // force all modules to have std/ available at runtime.  Module-level
        // locals are scoped to the entire Lua chunk regardless of where they
        // appear, so emitting them here (after walkStatements) is functionally
        // identical to emitting them in emitHeader() — the generated C$fromJson
        // and C$toJson closures capture these locals correctly.
        // Access raw functions via .f because std.json exports are __rt.function_
        // wrappers (tables with no __call metamethod).
        emitLine("-- @jsonable: JSON module loading");
        emitLine("local __json = require(\"std.json\")");
        emitLine("local __json_parse = __json.parse.f");
        emitLine("local __json_stringify = __json.stringify.f");
        emitLine("");

        // Build lookup map by class name
        Map<String, JsonableClassMeta> metaByName = new LinkedHashMap<>();
        for (JsonableClassMeta meta : deferredJsonables) {
            metaByName.put(meta.className, meta);
        }
        Set<String> jsonableNames = metaByName.keySet();

        // Step 1: Build dependency graph from scratch against the complete set.
        // For each class A, scan each field's AST type node, recursively
        // unwrapping ArrayType and NullableType wrappers to find underlying
        // NamedType references. For each same-module @jsonable class B found,
        // add edge A → B (A depends on B, so B must be emitted before A).
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (JsonableClassMeta meta : deferredJsonables) {
            Set<String> classDeps = new LinkedHashSet<>();
            for (ClassField field : meta.fields) {
                collectSameModuleDeps(field.type(), classDeps, jsonableNames);
            }
            deps.put(meta.className, classDeps);
        }

        // Step 2: Topological sort (Kahn's algorithm).
        // Build in-degree map: for each dep B of A, A must come after B.
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> successors = new LinkedHashMap<>();
        for (String name : jsonableNames) {
            inDegree.put(name, 0);
            successors.put(name, new ArrayList<>());
        }
        for (var entry : deps.entrySet()) {
            String a = entry.getKey();
            for (String b : entry.getValue()) {
                // Edge B → A: B must precede A
                successors.get(b).add(a);
                inDegree.merge(a, 1, Integer::sum);
            }
        }

        // Start with nodes having no dependencies
        List<String> sorted = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        while (!queue.isEmpty()) {
            String name = queue.poll();
            sorted.add(name);
            for (String succ : successors.get(name)) {
                int deg = inDegree.merge(succ, -1, Integer::sum);
                if (deg == 0) {
                    queue.add(succ);
                }
            }
        }

        // If the sort didn't include all nodes, there's a cycle.
        // This shouldn't happen (E4008 in Phase 3), but handle gracefully.
        if (sorted.size() != jsonableNames.size()) {
            // Fall back to declaration order for any remaining nodes
            Set<String> sortedSet = new HashSet<>(sorted);
            for (JsonableClassMeta meta : deferredJsonables) {
                if (!sortedSet.contains(meta.className)) {
                    sorted.add(meta.className);
                }
            }
        }

        // Sub-pass 1: Field descriptors in sorted order
        emitLine("-- @jsonable field descriptors (topologically sorted)");
        for (String className : sorted) {
            JsonableClassMeta meta = metaByName.get(className);
            emitFieldDescriptor(meta);
        }
        emitLine();

        // Sub-pass 2: C$fromJson and C$toJson functions in sorted order
        emitLine("-- @jsonable serialization functions (topologically sorted)");
        for (String className : sorted) {
            JsonableClassMeta meta = metaByName.get(className);
            emitFromJson(meta);
            emitToJson(meta);
        }
        emitLine();
    }

    /**
     * Recursively scans an AST type node for same-module @jsonable class
     * dependencies. Unwraps ArrayType and NullableType wrappers to find
     * underlying NamedType references. QualifiedType (cross-module) is
     * skipped — imported class locals are already available via require.
     */
    private void collectSameModuleDeps(TypeNode typeNode, Set<String> deps,
                                        Set<String> jsonableNames) {
        TypeNode inner = typeNode;
        if (inner instanceof NullableType nt) {
            inner = nt.innerType();
        }
        if (inner instanceof ArrayType at) {
            collectSameModuleDeps(at.elementType(), deps, jsonableNames);
        } else if (inner instanceof NamedType nt) {
            if (jsonableNames.contains(nt.name())) {
                deps.add(nt.name());
            }
        }
        // QualifiedType: cross-module, no same-module dep
        // FunctionType: not jsonable, skip
    }

    /**
     * Emits the {@code C_fields} descriptor table for a single @jsonable class.
     */
    private void emitFieldDescriptor(JsonableClassMeta meta) {
        String name = meta.className;
        emitLine(LuaAbi.namespaceAssignment(
            LuaAbi.helperKey(name, LuaAbi.HelperKind.FIELDS), "{"));
        indent++;
        List<ClassField> fields = meta.fields;
        for (int i = 0; i < fields.size(); i++) {
            ClassField field = fields.get(i);
            String comma = (i < fields.size() - 1) ? "," : "";
            emitLine(emitSingleFieldDescriptor(field) + comma);
        }
        indent--;
        emitLine("}");
    }

    /**
     * Emits a single field descriptor entry as a Lua table literal.
     */
    private String emitSingleFieldDescriptor(ClassField field) {
        TypeNode typeNode = field.type();
        boolean nullable = field.nullable();
        boolean optional = field.optional();

        // Unwrap NullableType to get the inner type for jtype determination
        TypeNode innerType = typeNode;
        if (innerType instanceof NullableType nt) {
            innerType = nt.innerType();
        }

        String jtype = jtypeForTypeNode(innerType);

        StringBuilder sb = new StringBuilder();
        sb.append("{ name = \"").append(field.name()).append("\"");
        sb.append(", jtype = \"").append(jtype).append("\"");
        sb.append(", optional = ").append(optional ? "true" : "false");
        sb.append(", nullable = ").append(nullable ? "true" : "false");

        if (jtype.equals("class")) {
            sb.append(", className = \"")
                .append(classNameFromTypeNode(innerType)).append("\"");
            sb.append(", defaults = ").append(defaultsRefForTypeNode(innerType));
            sb.append(", fields = ").append(fieldsRefForTypeNode(innerType));
        } else if (jtype.equals("array")) {
            ArrayType at = (ArrayType) innerType;
            sb.append(", element = ")
                .append(emitElementDescriptor(at.elementType()));
        }

        sb.append(" }");
        return sb.toString();
    }

    /**
     * Emits the element sub-descriptor for an array field.
     */
    private String emitElementDescriptor(TypeNode elementType) {
        // Check if the element itself is nullable before unwrapping
        boolean nullable = elementType instanceof NullableType;

        // Unwrap NullableType to get the inner type for jtype determination
        TypeNode inner = elementType;
        if (inner instanceof NullableType nt) {
            inner = nt.innerType();
        }
        String jtype = jtypeForTypeNode(inner);

        StringBuilder sb = new StringBuilder();
        sb.append("{ jtype = \"").append(jtype).append("\"");
        sb.append(", optional = false, nullable = ").append(nullable ? "true" : "false");

        if (jtype.equals("class")) {
            sb.append(", className = \"")
                .append(classNameFromTypeNode(inner)).append("\"");
            sb.append(", defaults = ").append(defaultsRefForTypeNode(inner));
            sb.append(", fields = ").append(fieldsRefForTypeNode(inner));
        } else if (jtype.equals("array")) {
            ArrayType at = (ArrayType) inner;
            sb.append(", element = ")
                .append(emitElementDescriptor(at.elementType()));
        }

        sb.append(" }");
        return sb.toString();
    }

    /**
     * Determines the jtype string for a TypeNode (after NullableType unwrapping).
     */
    private String jtypeForTypeNode(TypeNode typeNode) {
        return switch (typeNode) {
            case NamedType nt -> switch (nt.name()) {
                case "null" -> "null";
                case "boolean" -> "boolean";
                case "int" -> "int";
                case "number" -> "number";
                case "string" -> "string";
                case "table" -> "table";
                default -> "class";  // user-defined class
            };
            case ArrayType at -> "array";
            case QualifiedType qt -> "class";
            case NullableType nt -> jtypeForTypeNode(nt.innerType());
            case FunctionType ft -> "function";  // not expected for jsonable
        };
    }

    /**
     * Extracts the class name from a type node representing a class type.
     */
    private String classNameFromTypeNode(TypeNode typeNode) {
        return switch (typeNode) {
            case NamedType nt -> nt.name();
            case QualifiedType qt -> qt.typeName();
            default -> "Unknown";
        };
    }

    /**
     * Returns the Lua reference for the defaults table of a class-typed field.
     */
    private String defaultsRefForTypeNode(TypeNode typeNode) {
        return switch (typeNode) {
            case NamedType nt -> LuaAbi.helperRef(nt.name(), LuaAbi.HelperKind.DEFAULTS);
            case QualifiedType qt -> LuaAbi.memberAccess(qt.moduleName(),
                LuaAbi.helperKey(qt.typeName(), LuaAbi.HelperKind.DEFAULTS));
            default -> "{}";
        };
    }

    /**
     * Returns the Lua reference for the fields descriptor table of a class-typed field.
     */
    private String fieldsRefForTypeNode(TypeNode typeNode) {
        return switch (typeNode) {
            case NamedType nt -> LuaAbi.helperRef(nt.name(), LuaAbi.HelperKind.FIELDS);
            case QualifiedType qt -> LuaAbi.memberAccess(qt.moduleName(),
                LuaAbi.helperKey(qt.typeName(), LuaAbi.HelperKind.FIELDS));
            default -> "{}";
        };
    }

    /**
     * Emits the {@code C$fromJson} function for a single @jsonable class.
     */
    private void emitFromJson(JsonableClassMeta meta) {
        String name = meta.className;
        String sig = "(string)->" + name + "|null";

        emitLine(LuaAbi.namespaceAssignment(
            LuaAbi.helperKey(name, LuaAbi.HelperKind.FROM_JSON),
            "__rt.function_(\"" + sig + "\", function(s)"));
        indent++;
        emitLine("__rt.check_string(s)");
        emitLine("local ok, parsed = pcall(__json_parse, s)");
        emitLine("if not ok then return __NULL end");
        emitLine("local instance = __rt.json_from_json(\"" + name
            + "\", parsed, "
            + LuaAbi.helperRef(name, LuaAbi.HelperKind.DEFAULTS) + ", "
            + LuaAbi.helperRef(name, LuaAbi.HelperKind.FIELDS) + ")");
        emitLine("if instance == nil then return __NULL end");
        emitLine("return instance");
        indent--;
        emitLine("end)");
        emitLine();
    }

    /**
     * Emits the {@code C$toJson} function for a single @jsonable class.
     */
    private void emitToJson(JsonableClassMeta meta) {
        String name = meta.className;
        String sig = "(" + name + ")->string";

        emitLine(LuaAbi.namespaceAssignment(
            LuaAbi.helperKey(name, LuaAbi.HelperKind.TO_JSON),
            "__rt.function_(\"" + sig + "\", function(v)"));
        indent++;
        emitLine("__rt.check_type(\"" + name + "\", v)");
        emitLine("local t = __rt.json_to_json(\"" + name
            + "\", v, " + LuaAbi.helperRef(name, LuaAbi.HelperKind.FIELDS) + ")");
        emitLine("return __json_stringify(t)");
        indent--;
        emitLine("end)");
        emitLine();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean isNullLiteral(ExpressionNode expr) {
        return expr instanceof LiteralExpr lit
            && lit.value() instanceof LiteralValue.NullLiteral;
    }

    private String escapeLuaString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    /**
     * Like {@link #escapeLuaString} but without surrounding quotes.
     * Used for embedding file paths in generated code.
     */
    private String escapeLuaStringNoQuotes(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private String opSymbolLua(BinaryOp op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*";
            case DIV -> "/"; case MOD -> "%"; case POW -> "^";
            case EQ -> "=="; case NEQ -> "~="; case LT -> "<";
            case LTE -> "<="; case GT -> ">"; case GTE -> ">=";
            case AND -> "and"; case OR -> "or";
        };
    }

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
                // Qualified type like B.Result — look up the module symbol
                // and extract the class type.
                Symbol sym = symbols.resolve(qt.moduleName());
                if (sym instanceof Symbol.ModuleSymbol ms) {
                    Type exportType = ms.exports().get(qt.typeName());
                    if (exportType != null) yield exportType;
                }
                yield Types.classType(qt.typeName(), qt.moduleName());
            }
            case deal.ast.ArrayType at -> {
                Type elem = resolveTypeNode(at.elementType());
                if (elem == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                yield new Type.Array(elem);
            }
            case deal.ast.NullableType nt2 -> {
                Type inner = resolveTypeNode(nt2.innerType());
                if (inner == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                yield new Type.Nullable(inner);
            }
            case deal.ast.FunctionType ft -> {
                List<Type> paramTypes = new ArrayList<>();
                for (FunctionTypeParam ftp : ft.params()) {
                    Type pt = resolveTypeNode(ftp.type());
                    if (pt == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                    paramTypes.add(pt);
                }
                Type ret = resolveTypeNode(ft.returnType());
                if (ret == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                if (ft.rest().isPresent()) {
                    Type rest = resolveTypeNode(ft.rest().get().type());
                    if (rest instanceof Type.Array ra) {
                        yield new Type.Func(paramTypes, Optional.of(ra), ret, ft.isAsync());
                    }
                    yield Type.Error.INSTANCE;
                }
                yield new Type.Func(paramTypes, Optional.empty(), ret, ft.isAsync());
            }
        };
    }
}
