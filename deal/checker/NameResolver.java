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
 * (Block, FunctionDeclaration, ForStatement, TryStatement) to its
 * {@link SymbolTable} scope, used by the TypeChecker for scope-aware
 * name resolution.</p>
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

    public NameResolver(String modulePath, ModuleResolver moduleResolver) {
        this.modulePath = modulePath;
        this.moduleResolver = moduleResolver;
        this.root = new SymbolTable();
        this.currentScope = root;
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
        root.define("int", new Symbol.IntrinsicSymbol("int",
            Type.Int.INSTANCE, IntrinsicResolvers.INT));
        root.define("number", new Symbol.IntrinsicSymbol("number",
            Type.Number.INSTANCE, IntrinsicResolvers.NUMBER));
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

        Symbol existing = root.resolveLocal(alias);
        if (existing != null) {
            error("E2006", "Import '" + alias + "' shadows module-level declaration",
                imp.span());
            return;
        }

        try {
            Map<String, Type> exports = moduleResolver.resolveModule(path, modulePath);
            root.define(alias, new Symbol.ModuleSymbol(alias, exports));
        } catch (ModuleResolver.ModuleNotFoundException e) {
            error("E2003", "Module not found: '" + path + "'", imp.span());
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
            error("E2002", "Redeclaration of '" + name + "'", cd.span());
            return;
        }
        root.define(name, new Symbol.ClassSymbol(name, cd.fields(), modulePath));
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
            case ClassDeclaration cd     -> { /* already hoisted */ }
            case Block b                 -> walkBlock(b);
            case IfStatement is          -> walkIf(is);
            case WhileStatement ws       -> walkWhile(ws);
            case ForStatement fs         -> walkFor(fs);
            case TryStatement ts         -> walkTry(ts);
            case ImportDeclaration id    -> { /* already processed */ }
            case ExportDeclaration ed    -> { /* delegate to inner decl */ }
            default                      -> { /* no declarations */ }
        }
    }

    private void walkVarDecl(VariableDeclaration vd) {
        String name = vd.name();
        Type type = vd.typeAnnotation().map(this::resolveTypeNode).orElse(null);
        if (currentScope.containsLocally(name)) {
            error("E2002", "Redeclaration of '" + name + "'", vd.span());
            return;
        }
        currentScope.define(name, new Symbol.VariableSymbol(name, type, false));
    }

    private void walkFuncDecl(FunctionDeclaration fd) {
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
        walkBlock(is.thenBlock());
        is.elseBranch().ifPresent(eb -> {
            switch (eb) {
                case Either.Left<IfStatement, Block> left -> walkIf(left.value());
                case Either.Right<IfStatement, Block> right -> walkBlock(right.value());
            }
        });
    }

    private void walkWhile(WhileStatement ws) {
        walkBlock(ws.body());
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
            }
        });

        walkBlock(fs.body());
        currentScope = saved;
    }

    private void walkTry(TryStatement ts) {
        walkBlock(ts.tryBlock());

        SymbolTable saved = currentScope;
        currentScope = currentScope.enterScope();
        scopeMap.put(ts, currentScope);
        currentScope.define(ts.catchVar(),
            new Symbol.VariableSymbol(ts.catchVar(), Type.Error.INSTANCE, true));
        walkBlock(ts.catchBlock());
        currentScope = saved;
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
            case "Error"     -> Type.Error.INSTANCE;
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
