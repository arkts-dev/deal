package deal.checker;

import deal.ast.*;
import deal.lexer.Diagnostic;
import deal.types.Type;
import deal.types.Types;

import java.util.*;

/**
 * Pass 1 of the type checker: name resolution.
 *
 * <p>Builds the hierarchical symbol table:
 * <ol>
 *   <li>Creates the module scope and seeds it with intrinsic bindings</li>
 *   <li>Processes imports, binding each alias to a {@link Symbol.ModuleSymbol}</li>
 *   <li>Hoists {@code class} declarations (name available anywhere in module)</li>
 *   <li>Hoists {@code function} declarations (name available anywhere in module)</li>
 *   <li>Walks statements in order, entering/exiting block scopes,
 *       defining {@code let} variables at their declaration point</li>
 * </ol>
 *
 * <p>Also produces a {@code scopeMap} mapping each scoped statement
 * (Block, FunctionDeclaration, ForStatement, TryStatement, and
 * FunctionExpr) to its {@link SymbolTable} scope, used by the TypeChecker
 * for scope-aware name resolution.</p>
 *
 * <p>Errors produced: E2001–E2007.</p>
 */
public final class NameResolver {

    private final String modulePath;
    private final ModuleResolver moduleResolver;
    private final SymbolTable root;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private SymbolTable currentScope;

    /** Maps scoped AST nodes to their symbol table scope. */
    private final Map<StatementNode, SymbolTable> scopeMap = new HashMap<>();

    /** Loop nesting depth — used to validate break/continue (F8). */
    private int loopDepth = 0;

    /** Set of modules currently being resolved (for circular import detection, F12/F2). */
    private final Set<String> modulesInProgress;

    public NameResolver(String modulePath, ModuleResolver moduleResolver) {
        this(modulePath, moduleResolver, new HashSet<>());
    }

    public NameResolver(String modulePath, ModuleResolver moduleResolver,
                 Set<String> modulesInProgress) {
        this.modulePath = modulePath;
        this.moduleResolver = moduleResolver;
        this.root = new SymbolTable();
        this.currentScope = root;
        this.modulesInProgress = modulesInProgress;
    }

    // =======================================================================
    // Public API
    // =======================================================================

    /**
     * Runs name resolution on a program and returns the populated symbol table.
     */
    public SymbolTable resolve(ProgramNode program) {
        seedIntrinsics();
        processImports(program);
        hoistClassDeclarations(program);
        hoistFunctionDeclarations(program);
        walkStatements(program.statements());
        return root;
    }

    /**
     * Returns the scope map: scoped statement → its SymbolTable.
     * Used by TypeChecker for scope-aware name resolution.
     */
    public Map<StatementNode, SymbolTable> scopeMap() {
        return scopeMap;
    }

    /** Returns diagnostics accumulated during resolution. */
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    // =======================================================================
    // Intrinsics
    // =======================================================================

    private void seedIntrinsics() {
        // int intrinsic: (number) => int  (with intrinsic resolver for overloads)
        Type.Func intFuncType = new Type.Func(
            List.of(Type.Number.INSTANCE), Optional.empty(), Type.Int.INSTANCE);
        root.define("int", new Symbol.IntrinsicSymbol("int",
            intFuncType, IntrinsicResolvers.INT));

        // number intrinsic: (int) => number  (with intrinsic resolver for overloads)
        Type.Func numFuncType = new Type.Func(
            List.of(Type.Int.INSTANCE), Optional.empty(), Type.Number.INSTANCE);
        root.define("number", new Symbol.IntrinsicSymbol("number",
            numFuncType, IntrinsicResolvers.NUMBER));

        // F7: The `has` intrinsic is handled by HasExpr in the type checker,
        // not via call-intrinsic path.  Store it with a boolean type for
        // symbol-table correctness; the intrinsic resolver is dead code
        // but kept for robustness.
        root.define("has", new Symbol.IntrinsicSymbol("has",
            Type.Boolean.INSTANCE, IntrinsicResolvers.HAS));

        Span synth = Span.synthetic(modulePath);
        List<ClassField> errorFields = List.of(
            new ClassField(synth, "code", false, false,
                new NamedType(synth, "string"), Optional.empty()),
            new ClassField(synth, "message", false, false,
                new NamedType(synth, "string"), Optional.empty())
        );
        root.define("Error", new Symbol.ClassSymbol("Error", errorFields, ""));
    }

    // =======================================================================
    // Imports
    // =======================================================================

    private void processImports(ProgramNode program) {
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ImportDeclaration imp) {
                processImport(imp);
            }
        }
    }

    private void processImport(ImportDeclaration imp) {
        String alias = imp.alias();
        String path = imp.modulePath();

        // F2: Circular import detection — check if the IMPORTED module
        // is already being resolved (not the importing module).
        if (modulesInProgress.contains(path)) {
            error("E2005", "Circular import with runtime dependency: '" + path + "'",
                imp.span());
            return;
        }

        try {
            // F2: Track the IMPORTED module, not the importing module.
            modulesInProgress.add(path);
            Map<String, Type> exports = moduleResolver.resolveModule(
                path, modulePath, modulesInProgress);
            root.define(alias, new Symbol.ModuleSymbol(alias, exports, imp.span()));
        } catch (ModuleResolver.ModuleNotFoundException e) {
            error("E2003", "Module not found: '" + path + "'", imp.span());
        } finally {
            modulesInProgress.remove(path);
        }
    }

    // =======================================================================
    // Hoisting
    // =======================================================================

    private void hoistClassDeclarations(ProgramNode program) {
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof ClassDeclaration cd) {
                hoistClass(cd);
            } else if (stmt instanceof ExportDeclaration exp
                    && exp.declaration() instanceof ClassDeclaration cd) {
                hoistClass(cd);
            }
        }
    }

    private void hoistClass(ClassDeclaration cd) {
        String name = cd.name();
        if (root.containsLocally(name)) {
            if (shadowsImport(name, cd.span())) return;
            error("E2002", "Redeclaration of '" + name + "'", cd.span());
            return;
        }
        root.define(name, new Symbol.ClassSymbol(name, cd.fields(), modulePath));

        // F9: Check class field default values against declared types
        for (ClassField cf : cd.fields()) {
            cf.defaultExpr().ifPresent(defaultExpr -> {
                Type fieldType = resolveTypeNode(cf.type());
                if (fieldType == Type.Error.INSTANCE) return;
                Type defaultType = inferDefaultType(defaultExpr);
                if (defaultType != null && defaultType != Type.Error.INSTANCE) {
                    // F3: Allow null default for nullable fields
                    if (!Types.equals(fieldType, defaultType)
                            && !(fieldType instanceof Type.Nullable ne
                                 && Types.equals(ne.inner(), defaultType))
                            && !(fieldType instanceof Type.Nullable
                                 && defaultType instanceof Type.Null)) {
                        error("E3001",
                            "Default value type mismatch for field '" + cf.name()
                            + "': expected " + TypeChecker.typeName(fieldType)
                            + ", got " + TypeChecker.typeName(defaultType),
                            defaultExpr.span());
                    }
                }
            });
        }
    }

    /**
     * Infer the type of a default value expression.
     * F8: Now handles non-literal defaults by delegating to a simple
     * expression-to-type resolution where possible.
     */
    private Type inferDefaultType(ExpressionNode expr) {
        return switch (expr) {
            case LiteralExpr lit -> switch (lit.value()) {
                case LiteralValue.NullLiteral n -> Type.Null.INSTANCE;
                case LiteralValue.BooleanLiteral b -> Type.Boolean.INSTANCE;
                case LiteralValue.IntLiteral i -> Type.Int.INSTANCE;
                case LiteralValue.NumberLiteral n -> Type.Number.INSTANCE;
                case LiteralValue.StringLiteral s -> Type.String.INSTANCE;
            };
            case UnaryExpr un -> {
                if (un.op() == UnaryOp.NEG && un.expr() instanceof LiteralExpr lit) {
                    yield inferDefaultType(lit);
                }
                yield null;
            }
            case IdentifierExpr id -> {
                // Resolve a simple identifier to its declared type
                Symbol sym = currentScope.resolve(id.name());
                if (sym instanceof Symbol.VariableSymbol vs && vs.type() != null) {
                    yield vs.type();
                }
                yield null;
            }
            default -> null;
        };
    }

    private void hoistFunctionDeclarations(ProgramNode program) {
        for (StatementNode stmt : program.statements()) {
            if (stmt instanceof FunctionDeclaration fd) {
                hoistFunction(fd);
            } else if (stmt instanceof ExportDeclaration exp
                    && exp.declaration() instanceof FunctionDeclaration fd) {
                hoistFunction(fd);
            }
        }
    }

    private void hoistFunction(FunctionDeclaration fd) {
        String name = fd.name();
        if (root.containsLocally(name)) {
            if (shadowsImport(name, fd.span())) return;
            error("E2002", "Redeclaration of '" + name + "'", fd.span());
            return;
        }

        Type funcType = resolveTypeNode(fd.returnType());
        List<Type> paramTypes = new ArrayList<>();
        for (Parameter p : fd.params()) {
            paramTypes.add(resolveTypeNode(p.type()));
        }
        Optional<Type.Array> restType = fd.restParam()
            .map(rp -> (Type.Array) resolveTypeNode(rp.type()));

        Type.Func ft = new Type.Func(paramTypes, restType, funcType);
        root.define(name, new Symbol.FunctionSymbol(name, ft));
    }

    /**
     * Checks whether the existing symbol at root with the given name is a
     * ModuleSymbol (import).
     *
     * <p>F7 fix: Compares source positions to distinguish E2006 from E2007.
     * <ul>
     *   <li>If the declaration came BEFORE the import in source order,
     *       the import should have been rejected with E2006. The import
     *       was processed first (before hoisting), so we correct this by
     *       emitting E2006 at the import's span and removing the import.</li>
     *   <li>If the declaration came AFTER the import, emit E2007.</li>
     * </ul>
     *
     * @return true if a diagnostic was emitted and the declaration should be skipped
     */
    private boolean shadowsImport(String name, Span declSpan) {
        Symbol existing = root.resolveLocal(name);
        if (existing instanceof Symbol.ModuleSymbol ms) {
            if (ms.importSpan() != null
                    && spanIsBefore(declSpan, ms.importSpan())) {
                // F7: Declaration came first in source order.
                // The import was processed before hoisting, so it didn't see
                // the declaration.  The real error is the import, not the
                // declaration.  Emit E2006 and remove the import so the
                // declaration can be used.
                error("E2006",
                    "Import '" + name + "' shadows module-level declaration",
                    ms.importSpan());
                root.remove(name);
                return false; // allow the declaration to be added below
            }
            // Declaration came after import → E2007
            error("E2007",
                "Module-level declaration '" + name + "' shadows import",
                declSpan);
            return true;
        }
        return false;
    }

    /** Returns true if span a comes strictly before span b in source order. */
    private static boolean spanIsBefore(Span a, Span b) {
        if (a.startLine() < b.startLine()) return true;
        if (a.startLine() > b.startLine()) return false;
        return a.startColumn() < b.startColumn();
    }

    // =======================================================================
    // Statement walking
    // =======================================================================

    private void walkStatements(List<StatementNode> statements) {
        for (StatementNode stmt : statements) {
            walkStatement(stmt);
        }
    }

    private void walkStatement(StatementNode stmt) {
        switch (stmt) {
            case VariableDeclaration vd -> walkVarDecl(vd);
            case FunctionDeclaration fd  -> walkFuncDecl(fd);
            case ClassDeclaration cd     -> walkClassDecl(cd);
            case Block b                 -> walkBlock(b);
            case IfStatement is          -> walkIf(is);
            case WhileStatement ws       -> walkWhile(ws);
            case ForStatement fs         -> walkFor(fs);
            case TryStatement ts         -> walkTry(ts);
            case ImportDeclaration id    -> { /* already processed */ }
            case ExportDeclaration ed    -> walkStatement(ed.declaration());
            case BreakStatement bs       -> walkBreak(bs);
            case ContinueStatement cs    -> walkContinue(cs);
            // F1: Walk expressions in these contexts to find FunctionExpr nodes
            case ReturnStatement rs      -> rs.expr().ifPresent(this::walkExpression);
            case ExpressionStatement es  -> walkExpression(es.expr());
            case DeleteStatement ds      -> walkExpression(ds.target());
            case ThrowStatement ts2      -> walkExpression(ts2.expr());
            default                      -> { /* no declarations */ }
        }
    }

    // =======================================================================
    // F1: Expression walking — finds FunctionExpr nodes for Pass 1
    // =======================================================================

    /**
     * Recursively walk an expression tree to find and process any
     * {@link FunctionExpr} nodes.  This ensures that let-declarations
     * inside function expression bodies are resolved during Pass 1
     * and visible during Pass 2.
     */
    private void walkExpression(ExpressionNode expr) {
        switch (expr) {
            case FunctionExpr fe -> walkFunctionExpr(fe);
            case BinaryExpr bin -> {
                walkExpression(bin.left());
                walkExpression(bin.right());
            }
            case UnaryExpr un -> walkExpression(un.expr());
            case CallExpr call -> {
                walkExpression(call.callee());
                for (ExpressionNode arg : call.args()) {
                    walkExpression(arg);
                }
            }
            case MemberAccessExpr mae -> walkExpression(mae.object());
            case IndexExpr idx -> {
                walkExpression(idx.array());
                walkExpression(idx.index());
            }
            case ArrayLiteralExpr arr -> {
                for (ExpressionNode elem : arr.elements()) {
                    walkExpression(elem);
                }
            }
            case ObjectLiteralExpr obj -> {
                for (Property prop : obj.properties()) {
                    walkExpression(prop.value());
                }
            }
            case HasExpr has -> walkExpression(has.object());
            case AssignmentExpr assign -> {
                walkExpression(assign.target());
                walkExpression(assign.value());
            }
            default -> { /* leaf expression — no nested FunctionExpr possible */ }
        }
    }

    private void walkVarDecl(VariableDeclaration vd) {
        String name = vd.name();
        Type type = vd.typeAnnotation().map(this::resolveTypeNode).orElse(null);
        if (currentScope.containsLocally(name)) {
            if (currentScope == root && shadowsImport(name, vd.span())) return;
            error("E2002", "Redeclaration of '" + name + "'", vd.span());
            return;
        }
        currentScope.define(name, new Symbol.VariableSymbol(name, type, false));

        // F1: Walk the initializer expression to find any FunctionExpr nodes
        walkExpression(vd.initializer());
    }

    /**
     * Walk a class declaration in statement context.
     * At module scope, classes are already hoisted.
     * Inside a function or block, we must add them to the current scope.
     */
    private void walkClassDecl(ClassDeclaration cd) {
        String name = cd.name();
        if (!currentScope.containsLocally(name)) {
            // Not already hoisted (nested class inside a function/block)
            currentScope.define(name,
                new Symbol.ClassSymbol(name, cd.fields(), modulePath));
        }
    }

    /**
     * Walk a function expression body for Pass 1 name resolution.
     * Defines parameters in a new scope, records the scope in scopeMap,
     * and recurses into the body.
     */
    private void walkFunctionExpr(FunctionExpr fe) {
        SymbolTable saved = currentScope;
        currentScope = currentScope.enterScope();

        // F1: Record the scope so Pass 2 can reuse it
        scopeMap.put(fe.body(), currentScope);

        Set<String> paramNames = new HashSet<>();
        for (Parameter p : fe.params()) {
            String name = p.name();
            if (paramNames.contains(name)) {
                error("E2002", "Duplicate parameter '" + name + "'", p.span());
                continue;
            }
            paramNames.add(name);
            Type paramType = resolveTypeNode(p.type());
            currentScope.define(name, new Symbol.VariableSymbol(name, paramType, true));
        }

        fe.restParam().ifPresent(rp -> {
            String name = rp.name();
            if (paramNames.contains(name)) {
                error("E2002", "Duplicate parameter '" + name + "'", rp.span());
                return;
            }
            paramNames.add(name);
            Type restType = resolveTypeNode(rp.type());
            currentScope.define(name, new Symbol.VariableSymbol(name, restType, true));
        });

        // F1: Use walkStatement(fe.body()) so the Block's scope is also recorded
        walkStatement(fe.body());
        currentScope = saved;
    }

    private void walkFuncDecl(FunctionDeclaration fd) {
        // F2: Define nested function name in the enclosing scope.
        // For module-level functions, the name is already hoisted.
        if (!currentScope.containsLocally(fd.name())) {
            Type funcType = resolveTypeNode(fd.returnType());
            List<Type> paramTypes = new ArrayList<>();
            for (Parameter p : fd.params()) {
                paramTypes.add(resolveTypeNode(p.type()));
            }
            Optional<Type.Array> restType = fd.restParam()
                .map(rp -> (Type.Array) resolveTypeNode(rp.type()));

            Type.Func ft = new Type.Func(paramTypes, restType, funcType);
            currentScope.define(fd.name(), new Symbol.FunctionSymbol(fd.name(), ft));
        }

        SymbolTable saved = currentScope;
        currentScope = currentScope.enterScope();
        scopeMap.put(fd, currentScope);

        Set<String> paramNames = new HashSet<>();
        for (Parameter p : fd.params()) {
            String name = p.name();
            if (paramNames.contains(name)) {
                error("E2002", "Duplicate parameter '" + name + "'", p.span());
                continue;
            }
            paramNames.add(name);
            Type paramType = resolveTypeNode(p.type());
            currentScope.define(name, new Symbol.VariableSymbol(name, paramType, true));
        }

        fd.restParam().ifPresent(rp -> {
            String name = rp.name();
            if (paramNames.contains(name)) {
                error("E2002", "Duplicate parameter '" + name + "'", rp.span());
                return;
            }
            paramNames.add(name);
            Type restType = resolveTypeNode(rp.type());
            currentScope.define(name, new Symbol.VariableSymbol(name, restType, true));
        });

        walkStatement(fd.body());
        currentScope = saved;
    }

    private void walkBlock(Block block) {
        SymbolTable saved = currentScope;
        currentScope = currentScope.enterScope();
        scopeMap.put(block, currentScope);
        walkStatements(block.statements());
        currentScope = saved;
    }

    private void walkIf(IfStatement is) {
        // F1: Walk condition expression to find FunctionExpr nodes
        walkExpression(is.condition());
        walkBlock(is.thenBlock());
        is.elseBranch().ifPresent(eb -> {
            switch (eb) {
                case Either.Left<IfStatement, Block> left -> walkIf(left.value());
                case Either.Right<IfStatement, Block> right -> walkBlock(right.value());
            }
        });
    }

    private void walkWhile(WhileStatement ws) {
        // F1: Walk condition expression
        walkExpression(ws.condition());
        loopDepth++;
        walkBlock(ws.body());
        loopDepth--;
    }

    private void walkFor(ForStatement fs) {
        SymbolTable saved = currentScope;
        currentScope = currentScope.enterScope();
        scopeMap.put(fs, currentScope);

        fs.init().ifPresent(init -> {
            if (init instanceof ForInit.VarDecl vd) {
                VariableDeclaration decl = vd.decl();
                String name = decl.name();
                Type type = decl.typeAnnotation().map(this::resolveTypeNode).orElse(null);
                currentScope.define(name, new Symbol.VariableSymbol(name, type, false));
                // F1: Walk init expression
                walkExpression(decl.initializer());
            } else if (init instanceof ForInit.AssignExpr ae) {
                walkExpression(ae.expr());
            }
        });

        // F1: Walk condition and update expressions
        fs.condition().ifPresent(this::walkExpression);
        fs.update().ifPresent(this::walkExpression);

        loopDepth++;
        walkBlock(fs.body());
        loopDepth--;
        currentScope = saved;
    }

    private void walkTry(TryStatement ts) {
        walkBlock(ts.tryBlock());

        SymbolTable saved = currentScope;
        currentScope = currentScope.enterScope();
        scopeMap.put(ts, currentScope);
        currentScope.define(ts.catchVar(),
            new Symbol.VariableSymbol(ts.catchVar(),
                Types.classType("Error", ""), true));
        walkBlock(ts.catchBlock());
        currentScope = saved;
    }

    // F8: Validate break/continue inside loops
    private void walkBreak(BreakStatement bs) {
        if (loopDepth == 0) {
            error("E2000", "'break' must be inside a loop", bs.span());
        }
    }

    private void walkContinue(ContinueStatement cs) {
        if (loopDepth == 0) {
            error("E2000", "'continue' must be inside a loop", cs.span());
        }
    }

    // =======================================================================
    // Type resolution from TypeNode
    // =======================================================================

    /**
     * Resolves an AST {@link TypeNode} to an internal {@link Type}.
     */
    public Type resolveTypeNode(TypeNode tn) {
        return switch (tn) {
            case NamedType nt -> resolveNamedType(nt);
            case deal.ast.ArrayType at -> {
                Type elem = resolveTypeNode(at.elementType());
                if (elem == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                yield Types.array(elem);
            }
            case deal.ast.NullableType nt -> {
                Type inner = resolveTypeNode(nt.innerType());
                if (inner == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                try {
                    yield Types.nullable(inner);
                } catch (IllegalArgumentException e) {
                    error("E3005", "Invalid nullable type: " + e.getMessage(), tn.span());
                    yield Type.Error.INSTANCE;
                }
            }
            case deal.ast.FunctionType ft -> {
                List<Type> paramTypes = new ArrayList<>();
                for (FunctionTypeParam p : ft.params()) {
                    Type pt = resolveTypeNode(p.type());
                    if (pt == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                    paramTypes.add(pt);
                }
                Optional<Type.Array> restType = Optional.empty();
                if (ft.rest().isPresent()) {
                    Type rt = resolveTypeNode(ft.rest().get().type());
                    if (rt == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                    if (rt instanceof Type.Array arr) {
                        restType = Optional.of(arr);
                    } else {
                        error("E3005", "Rest parameter type must be an array type", tn.span());
                        yield Type.Error.INSTANCE;
                    }
                }
                Type ret = resolveTypeNode(ft.returnType());
                if (ret == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                yield new Type.Func(paramTypes, restType, ret);
            }
        };
    }

    private Type resolveNamedType(NamedType nt) {
        String name = nt.name();
        return switch (name) {
            case "null"      -> Type.Null.INSTANCE;
            case "boolean"   -> Type.Boolean.INSTANCE;
            case "int"       -> Type.Int.INSTANCE;
            case "number"    -> Type.Number.INSTANCE;
            case "string"    -> Type.String.INSTANCE;
            case "table"     -> Type.Table.INSTANCE;
            case "coroutine" -> Type.Coroutine.INSTANCE;
            case "void"      -> Type.Void.INSTANCE;
            case "Error"     -> Types.classType("Error", "");
            default -> {
                Symbol sym = currentScope.resolve(name);
                if (sym instanceof Symbol.ClassSymbol cs) {
                    yield Types.classType(cs.name(), cs.modulePath());
                }
                error("E3004", "Unknown type '" + name + "'", nt.span());
                yield Type.Error.INSTANCE;
            }
        };
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    private void error(String code, String message, Span span) {
        diagnostics.add(new Diagnostic(code, "error", message,
            span.file(), span.startLine(), span.startColumn()));
    }
}
