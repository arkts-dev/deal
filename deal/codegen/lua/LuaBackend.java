package deal.codegen.lua;

import deal.ast.*;
import deal.ast.ArrayType;
import deal.ast.FunctionType;
import deal.ast.NullableType;
import deal.checker.CheckResult;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.lexer.Diagnostic;
import deal.types.Type;
import deal.types.Types;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

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

    private Type currentReturnType = null;
    private String sourceFilePath = "unknown.deal";

    private String currentContinueLabel = null;
    private int labelCounter = 0;

    private String tryReturnFlag = null;
    private String tryReturnVal = null;

    private int functionDepth = 0;

    // Round 5: track try-block nesting depth for detecting break/continue
    // inside try within a loop. Incremented when entering a try block body,
    // decremented after.
    private int insideTryDepth = 0;

    /**
     * Entry point: generate Lua source for a complete program.
     */
    public static String generate(ProgramNode program, CheckResult result,
                                   String sourcePath) {
        LuaBackend backend = new LuaBackend(result.typeMap(), result.symbolTable());
        backend.sourceFilePath = sourcePath;
        backend.emitHeader();
        backend.emitLine("local __NULL = __rt.__NULL");
        backend.emitLine("local __MISSING = __rt.__MISSING");
        backend.emitLine("local Error_defaults = { code = \"\", message = \"\" }");
        backend.emitLine("");

        backend.walkStatements(program.statements());
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
        String luaSource = generate(program, result, sourcePath);
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, luaSource);

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
        emitHeader();
        emitLine("local __NULL = __rt.__NULL");
        emitLine("local __MISSING = __rt.__MISSING");
        emitLine("local Error_defaults = { code = \"\", message = \"\" }");
        emitLine("");
        walkStatements(program.statements());
        emitExports();
        return this.out.toString();
    }

    // =========================================================================
    // Header / Exports
    // =========================================================================

    private void emitHeader() {
        emitLine("-- Generated by DEAL compiler v0.6");
        emitLine("-- Source: " + sourceFilePath);
        emitLine("");
        emitLine("local __rt = require(\"deal.runtime\")");
        emitLine("");
    }

    private void emitExports() {
        emitLine("local exports = {}");
        for (var entry : exportedValues.entrySet()) {
            emitLine("exports." + entry.getKey() + " = " + entry.getValue());
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

    private void addDiagnostic(String code, String message, Span span) {
        diagnostics.add(Diagnostic.error(code, message,
            span.file(), span.startLine(), span.startColumn()));
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
            case Type.Void ignored -> "void";
            case Type.Boolean ignored -> "boolean";
            case Type.Int ignored -> "int";
            case Type.Number ignored -> "number";
            case Type.String ignored -> "string";
            case Type.Table ignored -> "table";
            case Type.Coroutine ignored -> "coroutine";
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
                StringBuilder sb = new StringBuilder("(");
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

    private String emitCheckExpr(String valueExpr, Type type) {
        if (type == null) return valueExpr;
        return switch (type) {
            case Type.Null ignored -> "__rt.check_null(" + valueExpr + ")";
            case Type.Boolean ignored -> "__rt.check_boolean(" + valueExpr + ")";
            case Type.Int ignored -> "__rt.check_int(" + valueExpr + ")";
            case Type.Number ignored -> "__rt.check_number(" + valueExpr + ")";
            case Type.String ignored -> "__rt.check_string(" + valueExpr + ")";
            case Type.Table ignored -> "__rt.check_table(" + valueExpr + ")";
            case Type.Coroutine ignored -> "__rt.check_coroutine(" + valueExpr + ")";
            case Type.Array arr ->
                "__rt.check_array(\"" + typeDescriptor(type) + "\", " + valueExpr + ")";
            case Type.Nullable n ->
                "__rt.check_nullable(\"" + typeDescriptor(n.inner()) + "\", " + valueExpr + ")";
            case Type.Class cls ->
                "__rt.check_type(\"" + typeDescriptor(cls) + "\", " + valueExpr + ")";
            case Type.Func f ->
                "__rt.check_type(\"" + typeDescriptor(f) + "\", " + valueExpr + ")";
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
            case Type.Coroutine ignored -> "__rt.check_coroutine";
            default -> null;
        };
    }

    // =========================================================================
    // Statement walking
    // =========================================================================

    private void walkStatements(List<StatementNode> statements) {
        for (StatementNode stmt : statements) visitStatement(stmt);
    }

    private void visitStatement(StatementNode stmt) {
        switch (stmt) {
            case VariableDeclaration vd -> visit(vd);
            case FunctionDeclaration fd -> visit(fd);
            case ClassDeclaration cd -> visit(cd);
            case ReturnStatement rs -> visit(rs);
            case IfStatement is -> visit(is);
            case WhileStatement ws -> visit(ws);
            case ForStatement fs -> visit(fs);
            case BreakStatement bs -> visit(bs);
            case ContinueStatement cs -> visit(cs);
            case ExpressionStatement es -> visit(es);
            case ImportDeclaration id -> visit(id);
            case ExportDeclaration ed -> visit(ed);
            case DeleteStatement ds -> visit(ds);
            case TryStatement ts -> visit(ts);
            case ThrowStatement ts2 -> visit(ts2);
            case Block b -> visit(b);
            default -> addDiagnostic("E6000",
                "unsupported statement type: " + stmt.getClass().getSimpleName(),
                stmt.span());
        }
    }

    // =========================================================================
    // Visitor: Statements
    // =========================================================================

    @Override
    public Void visit(ProgramNode node) {
        emitHeader();
        walkStatements(node.statements());
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
            defaults.append(field.name()).append(" = ");
            if (field.optional() && field.defaultExpr().isEmpty()) {
                defaults.append("__MISSING");
            } else if (field.defaultExpr().isPresent()) {
                defaults.append(emitExpression(field.defaultExpr().get()));
            } else if (field.nullable()) {
                defaults.append("__NULL");
            } else {
                defaults.append(defaultValueForTypeNode(field.type()));
            }
        }
        defaults.append("}");

        emitLine("-- Class: " + name);
        emitLine("local " + name + "_defaults = " + defaults.toString());
        emitLine("local " + name + "_meta = __rt.export_class(\"" + name + "\")");
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
        emitLine("local " + name + " = __rt.function_(\"" + sig
            + "\", function(" + paramList.toString() + ")");

        functionDepth++;
        indent++;

        // Emit rest parameter unpacking: local <name> = {...}
        if (hasRest) {
            Parameter rest = node.restParam().get();
            emitLine("local " + rest.name() + " = {...}");
            Type restType = resolveTypeNode(rest.type());
            if (restType instanceof Type.Array arr) {
                emitLine("__rt.check_array(\"" + typeDescriptor(arr)
                    + "\", " + rest.name() + ")");
            }
        }

        for (Parameter param : node.params()) {
            Type paramType = resolveTypeNode(param.type());
            if (paramType != null && !(paramType instanceof Type.Error)
                && !(paramType instanceof Type.Void)) {
                String checkFn = checkFunctionFor(paramType);
                if (checkFn != null) {
                    emitLine(checkFn + "(" + param.name() + ")");
                } else {
                    emitLine(emitCheckExpr(param.name(), paramType));
                }
            }
        }

        Type savedReturn = currentReturnType;
        currentReturnType = funcType != null ? funcType.returnType() : null;
        visit(node.body());
        currentReturnType = savedReturn;
        indent--;
        functionDepth--;

        emitLine("end)");
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

        if (hasAnnotation && targetType != null && !(targetType instanceof Type.Error)) {
            if (targetType instanceof Type.Func tf
                && exprType instanceof Type.Func ef
                && isArityExtension(ef, tf)) {
                String adapter = emitArityAdapter(tf, ef, initLua);
                emitLine("local " + name + " = " + adapter);
            } else {
                String checked = emitCheckExpr(initLua, targetType);
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
                && !(checkType instanceof Type.Void)
                && !(checkType instanceof Type.Null)) {
                String checked = emitCheckExpr(initLua, checkType);
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
                && !(currentReturnType instanceof Type.Void)
                && !(currentReturnType instanceof Type.Null)) {
                checkedExpr = emitCheckExpr(exprLua, currentReturnType);
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
        String condLua = "__rt.check_boolean(" + emitExpression(node.condition()) + ")";
        emitLine("if " + condLua + " then");
        indent++;
        visit(node.thenBlock());
        indent--;

        if (node.elseBranch().isPresent()) {
            switch (node.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left -> {
                    IfStatement elseIf = left.value();
                    emitLine("elseif __rt.check_boolean("
                        + emitExpression(elseIf.condition()) + ") then");
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
        return null;
    }

    private void emitElseChain(IfStatement node) {
        if (node.elseBranch().isPresent()) {
            switch (node.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left -> {
                    IfStatement elseIf = left.value();
                    emitLine("elseif __rt.check_boolean("
                        + emitExpression(elseIf.condition()) + ") then");
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
        String condLua = "__rt.check_boolean(" + emitExpression(node.condition()) + ")";

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
        return null;
    }

    @Override
    public Void visit(ForStatement node) {
        emitLine("-- DEAL for-loop (v0.6 lowering)");
        emitLine("-- KNOWN LIMIT: closures inside the loop body that capture the loop variable");
        emitLine("-- will all see the final value (no per-iteration fresh binding).");
        emitLine("do");
        indent++;

        if (node.init().isPresent()) {
            ForInit init = node.init().get();
            switch (init) {
                case ForInit.VarDecl vd -> {
                    VariableDeclaration decl = vd.decl();
                    Type varType = decl.typeAnnotation().isPresent()
                        ? resolveTypeNode(decl.typeAnnotation().get()) : null;
                    String initExpr = emitExpression(decl.initializer());
                    if (varType != null && !(varType instanceof Type.Error)) {
                        emitLine("local " + decl.name() + " = "
                            + emitCheckExpr(initExpr, varType));
                    } else {
                        emitLine("local " + decl.name() + " = " + initExpr);
                    }
                }
                case ForInit.AssignExpr ae -> visit(ae.expr());
            }
        }

        String condStr = node.condition().isPresent()
            ? "__rt.check_boolean(" + emitExpression(node.condition().get()) + ")"
            : "true";

        String savedLabel = currentContinueLabel;
        String loopLabel = freshLabel("__continue");
        currentContinueLabel = loopLabel;

        emitLine("while " + condStr + " do");
        indent++;
        visit(node.body());
        // Continue landing pad: placed before the update so that continue
        // in a C-style for-loop jumps to the update step, then re-checks condition.
        emitLine("::" + loopLabel + "::");
        if (node.update().isPresent()) {
            emitLine(emitExpression(node.update().get()));
        }
        indent--;
        emitLine("end");  // while
        indent--;
        emitLine("end");  // do

        currentContinueLabel = savedLabel;
        return null;
    }

    @Override
    public Void visit(BreakStatement node) {
        if (insideTryDepth > 0) {
            addDiagnostic("E6002",
                "break inside try within loop not supported in v0.6",
                node.span());
            emitLine("error(__rt._err(\"E6002\","
                + " \"break inside try within loop not supported in v0.6\"))");
        } else {
            emitLine("break");
        }
        return null;
    }

    @Override
    public Void visit(ContinueStatement node) {
        if (insideTryDepth > 0) {
            addDiagnostic("E6002",
                "continue inside try within loop not supported in v0.6",
                node.span());
            emitLine("error(__rt._err(\"E6002\","
                + " \"continue inside try within loop not supported in v0.6\"))");
        } else if (currentContinueLabel != null) {
            emitLine("goto " + currentContinueLabel);
        } else {
            addDiagnostic("E6001", "continue outside loop", node.span());
        }
        return null;
    }

    @Override
    public Void visit(ExpressionStatement node) {
        emitLine(emitExpression(node.expr()));
        return null;
    }

    @Override
    public Void visit(ImportDeclaration node) {
        emitLine("local " + node.alias() + " = require(\""
            + node.modulePath() + "\")");
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
                exportedValues.putIfAbsent(cd.name(), cd.name() + "_meta");
                visit(cd);
            }
            default -> {}
        }
        return null;
    }

    @Override
    public Void visit(DeleteStatement node) {
        emitLine(emitExpression(node.target()) + " = nil");
        return null;
    }

    @Override
    public Void visit(TryStatement node) {
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

        emitLine("local __ok, __err = pcall(function()");
        indent++;
        insideTryDepth++;
        visit(node.tryBlock());
        insideTryDepth--;
        indent--;
        emitLine("end)");

        tryReturnFlag = savedFlag;
        tryReturnVal = savedVal;

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
        emitLine("end");
        return null;
    }

    @Override
    public Void visit(ThrowStatement node) {
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
            sb.append("})");
            emitLine(sb.toString());
        } else {
            emitLine("error(" + emitExpression(node.expr()) + ")");
        }
        return null;
    }

    @Override
    public Void visit(Block node) {
        walkStatements(node.statements());
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
        emitLine(node.name()); return null;
    }

    private String emitIdentifier(IdentifierExpr id) { return id.name(); }

    @Override public Void visit(BinaryExpr node) {
        emitLine(emitBinary(node)); return null;
    }

    private String emitBinary(BinaryExpr bin) {
        Type leftType = typeOf(bin.left());
        Type rightType = typeOf(bin.right());
        String left = emitExpression(bin.left());
        String right = emitExpression(bin.right());
        BinaryOp op = bin.op();

        if (op == BinaryOp.ADD && leftType instanceof Type.String
            && rightType instanceof Type.String) {
            return left + " .. " + right;
        }

        if (leftType instanceof Type.Int && rightType instanceof Type.Int) {
            return switch (op) {
                case ADD -> "__rt.int_add(" + left + ", " + right + ")";
                case SUB -> "__rt.int_sub(" + left + ", " + right + ")";
                case MUL -> "__rt.int_mul(" + left + ", " + right + ")";
                case DIV -> "__rt.int_div(" + left + ", " + right + ")";
                case MOD -> "__rt.int_mod(" + left + ", " + right + ")";
                case POW -> "__rt.int_pow(" + left + ", " + right + ")";
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
            case NOT -> "(not " + expr + ")";
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
        if (mae.field().equals("length") && objType instanceof Type.Array) {
            return "#" + obj;
        }
        return obj + "." + mae.field();
    }

    @Override public Void visit(IndexExpr node) {
        emitLine(emitIndex(node)); return null;
    }

    private String emitIndex(IndexExpr idx) {
        Type arrayType = typeOf(idx.array());
        String arr = emitExpression(idx.array());
        String index = emitExpression(idx.index());
        if (arrayType instanceof Type.Array) {
            return arr + "[__rt.check_int(" + index + ") + 1]";
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
            sb.append(prop.name()).append(" = ")
                .append(emitExpression(prop.value()));
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
            provided.append(prop.name()).append(" = ")
                .append(emitExpression(prop.value()));
        }
        provided.append("}");

        String defaultsRef;
        if (sym instanceof Symbol.ClassSymbol cs) {
            defaultsRef = className + "_defaults";
        } else {
            defaultsRef = "{}";
        }

        return "__rt.class_(\"" + className + "\", " + defaultsRef
            + ", " + provided.toString() + ")";
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
            indent = 1;

            // Emit rest parameter unpacking: local <name> = {...}
            if (hasRest) {
                Parameter rest = fe.restParam().get();
                emitLine("local " + rest.name() + " = {...}");
                Type restType = resolveTypeNode(rest.type());
                if (restType instanceof Type.Array arr) {
                    emitLine("__rt.check_array(\"" + typeDescriptor(arr)
                        + "\", " + rest.name() + ")");
                }
            }

            for (Parameter param : fe.params()) {
                Type paramType = resolveTypeNode(param.type());
                if (paramType != null && !(paramType instanceof Type.Error)
                    && !(paramType instanceof Type.Void)) {
                    String checkFn = checkFunctionFor(paramType);
                    if (checkFn != null) {
                        emitLine(checkFn + "(" + param.name() + ")");
                    } else {
                        emitLine(emitCheckExpr(param.name(), paramType));
                    }
                }
            }
            visit(fe.body());
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
        return emitExpression(has.object()) + "." + has.field() + " ~= nil";
    }

    @Override public Void visit(AssignmentExpr node) {
        emitLine(emitAssignment(node)); return null;
    }

    private String emitAssignment(AssignmentExpr assign) {
        Type targetType = typeOf(assign.target());
        Type valueType = typeOf(assign.value());
        String targetLua = emitExpression(assign.target());
        String valueLua = emitExpression(assign.value());

        if (isArityExtension(valueType, targetType)) {
            return targetLua + " = "
                + emitArityAdapter((Type.Func) targetType, (Type.Func) valueType, valueLua);
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
                        + emitCheckExpr(valueLua, arrT.element());
                }

                String myIndent = "  ".repeat(indent);
                return "do\n"
                    + myIndent + "  local __idx = __rt.check_int(" + index + ")\n"
                    + myIndent + "  if __idx < 0 then error(__rt._err(\"E8002\", \"negative array index\")) end\n"
                    + myIndent + "  " + arr + "[__idx + 1] = "
                    + emitCheckExpr(valueLua, arrT.element()) + "\n"
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
                                     String valueLua) {
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
            sb.append(bodyIndent).append(emitCheckExpr("__p" + i, pt)).append("\n");
        }

        sb.append(bodyIndent).append("return ");
        sb.append(emitCheckExpr(
            valueLua + ".f(" + buildOverlappingArgs(valueFunc.paramTypes().size()) + ")",
            targetFunc.returnType()));
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
                case "void" -> Type.Void.INSTANCE;
                case "boolean" -> Type.Boolean.INSTANCE;
                case "int" -> Type.Int.INSTANCE;
                case "number" -> Type.Number.INSTANCE;
                case "string" -> Type.String.INSTANCE;
                case "table" -> Type.Table.INSTANCE;
                case "coroutine" -> Type.Coroutine.INSTANCE;
                case "Error" -> Types.classType("Error", "");
                default -> {
                    Symbol sym = symbols.resolve(nt.name());
                    if (sym instanceof Symbol.ClassSymbol cs) {
                        yield Types.classType(nt.name(), cs.modulePath());
                    }
                    yield Type.Error.INSTANCE;
                }
            };
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
                        yield new Type.Func(paramTypes, Optional.of(ra), ret);
                    }
                    yield Type.Error.INSTANCE;
                }
                yield new Type.Func(paramTypes, Optional.empty(), ret);
            }
        };
    }
}
