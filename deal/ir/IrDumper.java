package deal.ir;

import deal.ast.*;
import deal.checker.CheckResult;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.types.Type;
import deal.types.Types;

import java.util.*;

/**
 * Produces a deterministic, indented text IR dump for a typed DEAL AST.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #dump(ProgramNode, CheckResult, String)} — full module with type-checked expressions</li>
 *   <li>{@link #dump(ProgramNode, SymbolTable, String)} — declaration file without CheckResult</li>
 * </ul>
 *
 * <p>Type descriptors use the spec's {@code RuntimeTypeDescriptor} format:
 * {@code ?T} for nullables, {@code [T]} for arrays, {@code @path/Name} for classes,
 * and {@code async} prefix for async function types.
 *
 * <p>Spans use compact one-line format: {@code @file:startLine:startCol-endLine:endCol}.
 *
 * <p>Unhandled AST node types throw {@link IllegalStateException}.
 */
public final class IrDumper implements Visitor<String> {

    private final Map<ExpressionNode, Type> typeMap;
    private final SymbolTable symbolTable;
    private final String modulePath;
    private final boolean isDeclFile;
    private int indent;

    private Type currentReturnType;
    private boolean currentFunctionIsAsync;

    /** Maps import alias to module path for boundary annotations on calls. */
    private final Map<String, String> importAliasToPath = new HashMap<>();

    private IrDumper(Map<ExpressionNode, Type> typeMap, SymbolTable symbolTable,
                     String modulePath, boolean isDeclFile) {
        this.typeMap = Collections.unmodifiableMap(new HashMap<>(typeMap));
        this.symbolTable = symbolTable != null ? symbolTable : new SymbolTable();
        this.modulePath = modulePath;
        this.isDeclFile = isDeclFile;
        this.indent = 0;
    }

    // =========================================================================
    // Public entry points
    // =========================================================================

    public static String dump(ProgramNode program, CheckResult result, String modulePath) {
        if (result == null) {
            throw new IllegalArgumentException("CheckResult must not be null for full-module dump");
        }
        if (result.hasErrors()) {
            return "";
        }
        IrDumper dumper = new IrDumper(result.typeMap(), result.symbolTable(), modulePath, false);
        return dumper.visit(program);
    }

    public static String dump(ProgramNode program, SymbolTable symbolTable, String modulePath) {
        IrDumper dumper = new IrDumper(Map.of(), symbolTable, modulePath, true);
        return dumper.visit(program);
    }

    // =========================================================================
    // Span helpers
    // =========================================================================

    private String spanStr(Span span) {
        if (span == null) {
            throw new IllegalStateException("null span encountered in IR dump");
        }
        return "@" + span.file() + ":" + span.startLine() + ":" + span.startColumn()
            + "-" + span.endLine() + ":" + span.endColumn();
    }

    // =========================================================================
    // Indentation
    // =========================================================================

    private String indent() { return "  ".repeat(indent); }
    private void pushIndent() { indent++; }
    private void popIndent() { indent--; }

    // =========================================================================
    // Type descriptors — spec RuntimeTypeDescriptor format
    // =========================================================================

    /**
     * Converts an internal {@link Type} to its spec-format
     * {@code RuntimeTypeDescriptor} string.
     *
     * <p>When {@link Type.Func} gains an {@code isAsync} field (ISSUE-0017),
     * the async prefix will be emitted automatically via the reflective
     * {@link #funcIsAsync(Type.Func)} check.</p>
     */
    private String specTypeDescriptor(Type t) {
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
            case Type.Array arr -> "[" + specTypeDescriptor(arr.element()) + "]";
            case Type.Nullable n -> "?" + specTypeDescriptor(n.inner());
            case Type.Class cls -> {
                if (cls.modulePath() != null && !cls.modulePath().isEmpty()) {
                    yield "@" + cls.modulePath() + "/" + cls.name();
                } else {
                    yield cls.name();
                }
            }
            case Type.Func f -> {
                StringBuilder sb = new StringBuilder();
                // Async prefix: use reflective check until Type.Func gains isAsync (ISSUE-0017)
                if (funcIsAsync(f)) {
                    sb.append("async");
                }
                sb.append("(");
                for (int i = 0; i < f.paramTypes().size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(specTypeDescriptor(f.paramTypes().get(i)));
                }
                if (f.restType().isPresent()) {
                    if (!f.paramTypes().isEmpty()) sb.append(",");
                    sb.append("...").append(specTypeDescriptor(f.restType().get()));
                }
                sb.append(")->").append(specTypeDescriptor(f.returnType()));
                yield sb.toString();
            }
        };
    }

    /**
     * Reflective check for {@code Type.Func.isAsync()}.
     * Returns {@code false} when the method is not yet available (pre-ISSUE-0017).
     * Once ISSUE-0017 adds {@code isAsync} to {@code Type.Func}, this method
     * will transparently start returning the actual value.
     */
    private static boolean funcIsAsync(Type.Func f) {
        try {
            return (boolean) Type.Func.class.getMethod("isAsync").invoke(f);
        } catch (Exception e) {
            // isAsync not yet available (ISSUE-0017)
            return false;
        }
    }

    private String typeNodeToSpecDescriptor(TypeNode tn) {
        if (tn == null) return "null";
        return switch (tn) {
            case NamedType nt -> nt.name();
            case ArrayType at -> "[" + typeNodeToSpecDescriptor(at.elementType()) + "]";
            case NullableType nlt -> "?" + typeNodeToSpecDescriptor(nlt.innerType());
            case FunctionType ft -> {
                StringBuilder sb = new StringBuilder("(");
                for (int i = 0; i < ft.params().size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(typeNodeToSpecDescriptor(ft.params().get(i).type()));
                }
                if (ft.rest().isPresent()) {
                    if (!ft.params().isEmpty()) sb.append(",");
                    sb.append("...").append(typeNodeToSpecDescriptor(ft.rest().get().type()));
                }
                sb.append(")->").append(typeNodeToSpecDescriptor(ft.returnType()));
                yield sb.toString();
            }
            case QualifiedType qt -> "@" + qt.moduleName() + "/" + qt.typeName();
        };
    }

    // =========================================================================
    // Expression type lookup
    // =========================================================================

    /**
     * Returns the resolved type for an expression.
     *
     * <p>In full-module mode (not declaration-file), if an expression is not in
     * the typeMap and is not a self-describing literal, an
     * {@link IllegalStateException} is thrown per the design contract.
     */
    private Type typeOf(ExpressionNode expr) {
        Type t = typeMap.get(expr);
        if (t == null && expr instanceof LiteralExpr lit) {
            return literalType(lit);
        }
        if (t == null && !isDeclFile && !(expr instanceof LiteralExpr)) {
            throw new IllegalStateException(
                "Missing typeMap entry for " + expr.getClass().getSimpleName()
                + " " + spanStr(expr.span()));
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

    private boolean hasType(ExpressionNode expr) {
        return typeOf(expr) != null;
    }

    // =========================================================================
    // Dispatch helpers
    // =========================================================================

    private String dispatchStatement(StatementNode stmt) {
        if (stmt == null) return "";
        return switch (stmt) {
            case ClassDeclaration cd        -> visit(cd);
            case FunctionDeclaration fd     -> visit(fd);
            case VariableDeclaration vd     -> visit(vd);
            case ReturnStatement rs         -> visit(rs);
            case IfStatement is             -> visit(is);
            case WhileStatement ws          -> visit(ws);
            case ForStatement fs            -> visit(fs);
            case BreakStatement bs          -> visit(bs);
            case ContinueStatement cs       -> visit(cs);
            case ExpressionStatement es     -> visit(es);
            case ImportDeclaration id       -> visit(id);
            case ExportDeclaration ed       -> visit(ed);
            case DeleteStatement ds         -> visit(ds);
            case TryStatement ts            -> visit(ts);
            case ThrowStatement ths         -> visit(ths);
            case Block b                    -> visit(b);
            case ForOfStatement fos         -> visit(fos);
        };
    }

    private String dispatchExpr(ExpressionNode expr) {
        if (expr == null) return "";
        return switch (expr) {
            case LiteralExpr le            -> visit(le);
            case IdentifierExpr ie         -> visit(ie);
            case BinaryExpr be             -> visit(be);
            case UnaryExpr ue              -> visit(ue);
            case CallExpr ce               -> visit(ce);
            case MemberAccessExpr mae      -> visit(mae);
            case IndexExpr ie2             -> visit(ie2);
            case ArrayLiteralExpr ale      -> visit(ale);
            case ObjectLiteralExpr ole     -> visit(ole);
            case FunctionExpr fe           -> visit(fe);
            case HasExpr he                -> visit(he);
            case AssignmentExpr ae         -> visit(ae);
            case TemplateLiteralExpr tl      -> visit(tl);
        };
    }

    private String dispatchTypeNode(TypeNode tn) {
        if (tn == null) return "";
        return switch (tn) {
            case NamedType nt       -> visit(nt);
            case ArrayType at       -> visit(at);
            case NullableType nlt   -> visit(nlt);
            case FunctionType ft    -> visit(ft);
            case QualifiedType qt   -> visitQualifiedType(qt);
        };
    }

    // =========================================================================
    // Boundary annotation helpers
    // =========================================================================

    private String boundary(String kind) {
        return "[boundary: " + kind + "]";
    }

    private boolean isOptionalFieldRead(MemberAccessExpr mae) {
        Type objType = typeOf(mae.object());
        if (objType instanceof Type.Class cls) {
            Symbol sym = symbolTable.resolve(cls.name());
            if (sym instanceof Symbol.ClassSymbol cs) {
                for (ClassField field : cs.fields()) {
                    if (field.name().equals(mae.field())) {
                        return field.optional();
                    }
                }
            }
        }
        return false;
    }

    /**
     * Extracts the root identifier name from a callee chain for import-boundary
     * detection. For a {@code MemberAccessExpr} chain like
     * {@code M.foo.bar()}, this returns the base alias {@code "M"}.
     */
    private String rootCalleeAlias(ExpressionNode callee) {
        if (callee instanceof IdentifierExpr id) {
            return id.name();
        }
        if (callee instanceof MemberAccessExpr mae) {
            return rootCalleeAlias(mae.object());
        }
        return null;
    }

    // =========================================================================
    // Visit methods — Program
    // =========================================================================

    @Override
    public String visit(ProgramNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("module ").append(spanStr(node.span())).append("\n");
        List<StatementNode> stmts = new ArrayList<>(node.statements());
        pushIndent();
        for (StatementNode stmt : stmts) {
            sb.append(dispatchStatement(stmt));
        }
        popIndent();
        return sb.toString();
    }

    // =========================================================================
    // Visit methods — Statements
    // =========================================================================

    @Override
    public String visit(ClassDeclaration node) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("class ").append(node.name())
            .append(" ").append(spanStr(node.span())).append("\n");
        pushIndent();
        for (ClassField field : node.fields()) {
            sb.append(visitField(field));
        }
        popIndent();
        return sb.toString();
    }

    private String visitField(ClassField field) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("field ").append(field.name());
        if (field.optional()) sb.append("?");
        String typeDesc = typeNodeToSpecDescriptor(field.type());
        sb.append(": ").append(typeDesc).append(" ").append(spanStr(field.span())).append("\n");
        if (field.defaultExpr().isPresent()) {
            sb.append(indent()).append("  ").append(boundary("class-default")).append("\n");
            pushIndent();
            sb.append(dispatchExpr(field.defaultExpr().get()));
            popIndent();
        }
        return sb.toString();
    }

    @Override
    public String visit(FunctionDeclaration node) {
        StringBuilder sb = new StringBuilder();
        Type savedReturnType = this.currentReturnType;
        boolean savedAsync = this.currentFunctionIsAsync;

        // Check if this function is async via reflection (ISSUE-0017 will add isAsync field)
        boolean isAsync = funcDeclIsAsync(node);
        this.currentFunctionIsAsync = isAsync;

        Type funcReturnType = null;
        if (!isDeclFile) {
            Symbol sym = symbolTable.resolve(node.name());
            if (sym instanceof Symbol.FunctionSymbol fs) {
                funcReturnType = fs.funcType().returnType();
            }
        }
        this.currentReturnType = funcReturnType;

        String kind = isAsync ? "async function" : "function";
        sb.append(indent()).append(kind).append(" ").append(node.name());
        String retDesc = typeNodeToSpecDescriptor(node.returnType());
        sb.append(": ").append(retDesc).append(" ").append(spanStr(node.span())).append("\n");

        pushIndent();
        for (Parameter param : node.params()) {
            sb.append(visitParam(param));
        }
        if (node.restParam().isPresent()) {
            sb.append(visitRestParam(node.restParam().get()));
        }
        if (node.body() != null) {
            sb.append(indent()).append("body\n");
            pushIndent();
            for (StatementNode stmt : node.body().statements()) {
                sb.append(dispatchStatement(stmt));
            }
            popIndent();
        }
        popIndent();

        this.currentReturnType = savedReturnType;
        this.currentFunctionIsAsync = savedAsync;
        return sb.toString();
    }

    /**
     * Reflective check for {@code FunctionDeclaration.isAsync()}.
     * Returns {@code false} when the method is not yet available (pre-ISSUE-0017).
     * Once ISSUE-0017 adds {@code isAsync} to {@code FunctionDeclaration},
     * this method will transparently start returning the actual value.
     */
    private static boolean funcDeclIsAsync(FunctionDeclaration node) {
        try {
            return (boolean) FunctionDeclaration.class.getMethod("isAsync").invoke(node);
        } catch (Exception e) {
            // isAsync not yet available (ISSUE-0017)
            return false;
        }
    }

    private String visitParam(Parameter param) {
        StringBuilder sb = new StringBuilder();
        String typeDesc = typeNodeToSpecDescriptor(param.type());
        sb.append(indent()).append("param ").append(param.name())
            .append(": ").append(typeDesc)
            .append(" ").append(spanStr(param.span())).append("\n");
        sb.append(indent()).append("  ").append(boundary("param-entry")).append("\n");
        return sb.toString();
    }

    private String visitRestParam(Parameter param) {
        StringBuilder sb = new StringBuilder();
        String typeDesc = typeNodeToSpecDescriptor(param.type());
        sb.append(indent()).append("rest param ").append(param.name())
            .append(": ").append(typeDesc)
            .append(" ").append(spanStr(param.span())).append("\n");
        sb.append(indent()).append("  ").append(boundary("rest-construct")).append("\n");
        return sb.toString();
    }

    @Override
    public String visit(VariableDeclaration node) {
        StringBuilder sb = new StringBuilder();
        StringBuilder line = new StringBuilder();
        line.append(indent()).append("let ").append(node.name());
        if (node.typeAnnotation().isPresent()) {
            String typeDesc;
            if (isDeclFile || !hasTypeInfo()) {
                typeDesc = typeNodeToSpecDescriptor(node.typeAnnotation().get());
            } else {
                Type t = typeOf(node.initializer());
                typeDesc = t != null ? specTypeDescriptor(t)
                    : typeNodeToSpecDescriptor(node.typeAnnotation().get());
            }
            line.append(": ").append(typeDesc);
        } else {
            Type t = typeOf(node.initializer());
            if (t != null) line.append(": ").append(specTypeDescriptor(t));
        }
        line.append(" ").append(spanStr(node.span()));
        sb.append(line).append("\n");
        if (node.typeAnnotation().isPresent()) {
            sb.append(indent()).append("  ").append(boundary("var-annotation")).append("\n");
        }
        pushIndent();
        sb.append(dispatchExpr(node.initializer()));
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(ReturnStatement node) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("return ").append(spanStr(node.span())).append("\n");
        if (currentReturnType != null && !(currentReturnType instanceof Type.Null)
                && !(currentReturnType instanceof Type.Void)) {
            String boundaryKind = currentFunctionIsAsync ? "async-completion" : "return";
            sb.append(indent()).append("  ").append(boundary(boundaryKind)).append("\n");
        }
        if (node.expr().isPresent()) {
            pushIndent();
            sb.append(dispatchExpr(node.expr().get()));
            popIndent();
        }
        return sb.toString();
    }

    @Override
    public String visit(IfStatement node) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("if ").append(spanStr(node.span())).append("\n");
        pushIndent();
        sb.append(dispatchExpr(node.condition()));
        sb.append(dispatchStatement(node.thenBlock()));
        popIndent();
        if (node.elseBranch().isPresent()) {
            Either<IfStatement, Block> eb = node.elseBranch().get();
            if (eb instanceof Either.Left<IfStatement, Block> left) {
                sb.append(dispatchStatement(left.value()));
            } else if (eb instanceof Either.Right<IfStatement, Block> right) {
                sb.append(dispatchStatement(right.value()));
            }
        }
        return sb.toString();
    }

    @Override
    public String visit(WhileStatement node) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("while ").append(spanStr(node.span())).append("\n");
        pushIndent();
        sb.append(dispatchExpr(node.condition()));
        sb.append(dispatchStatement(node.body()));
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(ForStatement node) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("for ").append(spanStr(node.span())).append("\n");
        pushIndent();
        if (node.init().isPresent()) {
            ForInit init = node.init().get();
            if (init instanceof ForInit.VarDecl vd) {
                sb.append(dispatchStatement(vd.decl()));
            } else if (init instanceof ForInit.AssignExpr ie) {
                sb.append(dispatchExpr(ie.expr()));
            }
        }
        if (node.condition().isPresent()) {
            sb.append(dispatchExpr(node.condition().get()));
        }
        if (node.update().isPresent()) {
            sb.append(dispatchExpr(node.update().get()));
        }
        sb.append(dispatchStatement(node.body()));
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(BreakStatement node) {
        return indent() + "break " + spanStr(node.span()) + "\n";
    }

    @Override
    public String visit(ContinueStatement node) {
        return indent() + "continue " + spanStr(node.span()) + "\n";
    }

    @Override
    public String visit(ExpressionStatement node) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("expr-stmt ").append(spanStr(node.span())).append("\n");
        pushIndent();
        sb.append(dispatchExpr(node.expr()));
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(ImportDeclaration node) {
        // Record alias→modulePath for call boundary detection
        importAliasToPath.put(node.alias(), node.modulePath());

        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("import * as ").append(node.alias())
            .append(" from \"").append(node.modulePath()).append("\"")
            .append(" ").append(spanStr(node.span())).append("\n");
        // host-in for external (bare) imports
        if (!node.modulePath().startsWith("./") && !node.modulePath().startsWith("../")) {
            sb.append(indent()).append("  ").append(boundary("host-in")).append("\n");
        }
        sb.append(indent()).append("  ").append(boundary("import")).append("\n");
        return sb.toString();
    }

    @Override
    public String visit(ExportDeclaration node) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("export ").append(spanStr(node.span())).append("\n");
        String exportName = null;
        if (node.declaration() instanceof FunctionDeclaration fd) {
            exportName = fd.name();
        } else if (node.declaration() instanceof ClassDeclaration cd) {
            exportName = cd.name();
        }
        if (exportName != null) {
            if (isDeclFile) {
                sb.append(indent()).append("  ").append(boundary("host-out")).append("\n");
            }
            sb.append(indent()).append("  ").append(boundary("export")).append("\n");
        }
        pushIndent();
        sb.append(dispatchStatement(node.declaration()));
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(DeleteStatement node) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("delete ").append(spanStr(node.span())).append("\n");
        pushIndent();
        sb.append(dispatchExpr(node.target()));
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(TryStatement node) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("try ").append(spanStr(node.span())).append("\n");
        pushIndent();
        sb.append(dispatchStatement(node.tryBlock()));
        popIndent();
        if (node.catchVar() != null && node.catchBlock() != null) {
            sb.append(indent()).append("catch ").append(node.catchVar())
                .append(" ").append(spanStr(node.catchBlock().span())).append("\n");
            pushIndent();
            sb.append(dispatchStatement(node.catchBlock()));
            popIndent();
        }
        return sb.toString();
    }

    @Override
    public String visit(ThrowStatement node) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("throw ").append(spanStr(node.span())).append("\n");
        pushIndent();
        sb.append(dispatchExpr(node.expr()));
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(Block node) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("block ").append(spanStr(node.span())).append("\n");
        pushIndent();
        for (StatementNode stmt : node.statements()) {
            sb.append(dispatchStatement(stmt));
        }
        popIndent();
        return sb.toString();
    }

    // =========================================================================
    // Visit methods — Expressions
    // =========================================================================

    @Override
    public String visit(LiteralExpr node) {
        String typeStr = hasType(node) ? specTypeDescriptor(typeOf(node))
            : (isDeclFile ? "[no-expr-in-decl]" : "?");
        return indent() + "literal " + node.value() + " : " + typeStr
            + " " + spanStr(node.span()) + "\n";
    }

    @Override
    public String visit(IdentifierExpr node) {
        String typeStr;
        if (hasType(node)) {
            typeStr = specTypeDescriptor(typeOf(node));
        } else if (isDeclFile) {
            typeStr = "[no-expr-in-decl]";
        } else {
            typeStr = "?";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("ident ").append(node.name())
            .append(" : ").append(typeStr)
            .append(" ").append(spanStr(node.span())).append("\n");
        Symbol sym = symbolTable.resolve(node.name());
        if (sym instanceof Symbol.ModuleSymbol) {
            sb.append(indent()).append("  ").append(boundary("import")).append("\n");
        }
        return sb.toString();
    }

    @Override
    public String visit(BinaryExpr node) {
        String typeStr = hasType(node) ? specTypeDescriptor(typeOf(node))
            : (isDeclFile ? "[no-expr-in-decl]" : "?");
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("binary ").append(opStr(node.op()))
            .append(" : ").append(typeStr)
            .append(" ").append(spanStr(node.span())).append("\n");
        pushIndent();
        sb.append(dispatchExpr(node.left()));
        sb.append(dispatchExpr(node.right()));
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(UnaryExpr node) {
        String typeStr = hasType(node) ? specTypeDescriptor(typeOf(node))
            : (isDeclFile ? "[no-expr-in-decl]" : "?");
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("unary ").append(unaryOpStr(node.op()))
            .append(" : ").append(typeStr)
            .append(" ").append(spanStr(node.span())).append("\n");
        pushIndent();
        sb.append(dispatchExpr(node.expr()));
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(CallExpr node) {
        String typeStr = hasType(node) ? specTypeDescriptor(typeOf(node))
            : (isDeclFile ? "[no-expr-in-decl]" : "?");
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("call : ").append(typeStr)
            .append(" ").append(spanStr(node.span())).append("\n");

        // Detect stdlib/external boundaries: check if callee is an imported function
        String alias = rootCalleeAlias(node.callee());
        if (alias != null) {
            String resolvedPath = importAliasToPath.get(alias);
            if (resolvedPath != null) {
                if (resolvedPath.startsWith("std/")) {
                    sb.append(indent()).append("  ").append(boundary("stdlib-boundary")).append("\n");
                } else if (!resolvedPath.startsWith("./") && !resolvedPath.startsWith("../")) {
                    sb.append(indent()).append("  ").append(boundary("external-boundary")).append("\n");
                }
            }
        }

        pushIndent();
        sb.append(dispatchExpr(node.callee()));
        for (ExpressionNode arg : node.args()) {
            sb.append(dispatchExpr(arg));
        }
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(MemberAccessExpr node) {
        String typeStr = hasType(node) ? specTypeDescriptor(typeOf(node))
            : (isDeclFile ? "[no-expr-in-decl]" : "?");
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("member .").append(node.field())
            .append(" : ").append(typeStr)
            .append(" ").append(spanStr(node.span())).append("\n");

        // Optional field read boundary
        if (isOptionalFieldRead(node)) {
            sb.append(indent()).append("  ").append(boundary("optional-read")).append("\n");
        }

        // Table-read boundary: object is a Table type, result has a non-null, non-error type
        Type objType = typeOf(node.object());
        Type resultType = typeOf(node);
        if (objType instanceof Type.Table && resultType != null
                && !(resultType instanceof Type.Error)) {
            sb.append(indent()).append("  ").append(boundary("table-read")).append("\n");
        }

        pushIndent();
        sb.append(dispatchExpr(node.object()));
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(IndexExpr node) {
        String typeStr = hasType(node) ? specTypeDescriptor(typeOf(node))
            : (isDeclFile ? "[no-expr-in-decl]" : "?");
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("index [] : ").append(typeStr)
            .append(" ").append(spanStr(node.span())).append("\n");

        // Table-read boundary: array is a Table type, result has a non-null, non-error type
        Type arrType = typeOf(node.array());
        Type resultType = typeOf(node);
        if (arrType instanceof Type.Table && resultType != null
                && !(resultType instanceof Type.Error)) {
            sb.append(indent()).append("  ").append(boundary("table-read")).append("\n");
        }

        pushIndent();
        sb.append(dispatchExpr(node.array()));
        sb.append(dispatchExpr(node.index()));
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(ArrayLiteralExpr node) {
        String typeStr = hasType(node) ? specTypeDescriptor(typeOf(node))
            : (isDeclFile ? "[no-expr-in-decl]" : "?");
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("array : ").append(typeStr)
            .append(" ").append(spanStr(node.span())).append("\n");
        pushIndent();
        for (ExpressionNode elem : node.elements()) {
            sb.append(dispatchExpr(elem));
        }
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(ObjectLiteralExpr node) {
        String typeStr = hasType(node) ? specTypeDescriptor(typeOf(node))
            : (isDeclFile ? "[no-expr-in-decl]" : "?");
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("object : ").append(typeStr)
            .append(" ").append(spanStr(node.span())).append("\n");
        Type t = typeOf(node);
        if (t instanceof Type.Class) {
            sb.append(indent()).append("  ").append(boundary("class-construct")).append("\n");
        }
        pushIndent();
        for (Property prop : node.properties()) {
            sb.append(dispatchExpr(prop.value()));
        }
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(FunctionExpr node) {
        String typeStr = hasType(node) ? specTypeDescriptor(typeOf(node))
            : (isDeclFile ? "[no-expr-in-decl]" : "?");
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("func-expr : ").append(typeStr)
            .append(" ").append(spanStr(node.span())).append("\n");
        pushIndent();
        for (Parameter param : node.params()) {
            sb.append(visitParam(param));
        }
        if (node.restParam().isPresent()) {
            sb.append(visitRestParam(node.restParam().get()));
        }
        if (node.body() != null) {
            sb.append(indent()).append("body\n");
            pushIndent();
            for (StatementNode stmt : node.body().statements()) {
                sb.append(dispatchStatement(stmt));
            }
            popIndent();
        }
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(HasExpr node) {
        String typeStr = hasType(node) ? specTypeDescriptor(typeOf(node))
            : (isDeclFile ? "[no-expr-in-decl]" : "?");
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("has .").append(node.field())
            .append(" : ").append(typeStr)
            .append(" ").append(spanStr(node.span())).append("\n");
        pushIndent();
        sb.append(dispatchExpr(node.object()));
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(AssignmentExpr node) {
        String typeStr = hasType(node) ? specTypeDescriptor(typeOf(node))
            : (isDeclFile ? "[no-expr-in-decl]" : "?");
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("assign = : ").append(typeStr)
            .append(" ").append(spanStr(node.span())).append("\n");
        ExpressionNode target = node.target();
        if (target instanceof IdentifierExpr) {
            sb.append(indent()).append("  ").append(boundary("assignment")).append("\n");
        } else if (target instanceof MemberAccessExpr) {
            sb.append(indent()).append("  ").append(boundary("field-write")).append("\n");
        } else if (target instanceof IndexExpr) {
            sb.append(indent()).append("  ").append(boundary("array-write")).append("\n");
        }
        pushIndent();
        sb.append(dispatchExpr(node.target()));
        sb.append(dispatchExpr(node.value()));
        popIndent();
        return sb.toString();
    }

    // =========================================================================
    // Visit methods — Type nodes
    // =========================================================================

    @Override
    public String visit(NamedType node) {
        return indent() + "named-type " + node.name() + " " + spanStr(node.span()) + "\n";
    }

    @Override
    public String visit(ArrayType node) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("array-type ").append(spanStr(node.span())).append("\n");
        pushIndent();
        sb.append(dispatchTypeNode(node.elementType()));
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(NullableType node) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("nullable-type ").append(spanStr(node.span())).append("\n");
        pushIndent();
        sb.append(dispatchTypeNode(node.innerType()));
        popIndent();
        return sb.toString();
    }

    @Override
    public String visit(FunctionType node) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent()).append("function-type ").append(spanStr(node.span())).append("\n");
        pushIndent();
        for (FunctionTypeParam p : node.params()) {
            sb.append(dispatchTypeNode(p.type()));
        }
        if (node.rest().isPresent()) {
            sb.append(dispatchTypeNode(node.rest().get().type()));
        }
        sb.append(dispatchTypeNode(node.returnType()));
        popIndent();
        return sb.toString();
    }

    private String visitQualifiedType(QualifiedType node) {
        return indent() + "qualified-type " + node.moduleName() + "." + node.typeName()
            + " " + spanStr(node.span()) + "\n";
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean hasTypeInfo() {
        return !isDeclFile;
    }

    private String opStr(BinaryOp op) {
        return switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/";
            case MOD -> "%"; case POW -> "**"; case EQ -> "==="; case NEQ -> "!==";
            case LT -> "<"; case LTE -> "<="; case GT -> ">"; case GTE -> ">=";
            case AND -> "&&"; case OR -> "||";
        };
    }

    private String unaryOpStr(UnaryOp op) {
        return switch (op) {
            case NOT -> "!"; case NEG -> "-";
        };
    }
}
