package deal.checker;

import deal.ast.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalModuleIdentity;
import deal.identity.ProjectModuleIdentity;
import deal.types.Type;
import deal.types.Types;

import java.util.function.Function;

import java.util.*;
import deal.diagnostics.DiagnosticCode;

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
 * <p>Errors produced: E2001–E2008, E4006.</p>
 */
public final class NameResolver {

    private final String modulePath;
    private final ModuleResolver moduleResolver;

    /**
     * The module-path classification supplied by the module-identity
     * layer (E2's producer): checked module path &rarr; the canonical
     * public module identity, {@code null} for a module the layer
     * classified without one.  Every class type and ClassSymbol this
     * resolver builds obtains its {@link CanonicalClassIdentity}
     * exclusively from this function — never from dotted-path
     * reconstruction (descriptor-identity-propagation D1).
     */
    private final Function<String, CanonicalModuleIdentity> moduleClassification;

    private final SymbolTable root;
    private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();

    private SymbolTable currentScope;

    /** Maps scoped AST nodes to their symbol table scope. */
    private final Map<StatementNode, SymbolTable> scopeMap = new HashMap<>();

    /**
     * The exact AST declaration behind every class symbol this resolver
     * defined (module-level hoists and nested block declarations), keyed
     * by symbol identity. Imported classes and synthetic symbols have no
     * entry — their declarations live in another module or in declaration
     * metadata. The checker publishes the entry for an object literal's
     * construction declaration, so consumers select that declaration's
     * own artifact instead of re-resolving the (possibly shadowed)
     * lexical class name (ISSUE-0717 review 0007).
     */
    private final Map<Symbol.ClassSymbol, ClassDeclaration> classDeclarationNodes =
        new IdentityHashMap<>();

    /** Loop nesting depth — used to validate break/continue (F8). */
    private int loopDepth = 0;

    private final Set<String> modulesInProgress;

    public NameResolver(String modulePath, ModuleResolver moduleResolver) {
        this(modulePath, moduleResolver, new HashSet<>());
    }

    public NameResolver(String modulePath, ModuleResolver moduleResolver,
                 Set<String> modulesInProgress) {
        this(modulePath, moduleResolver, modulesInProgress,
            standaloneClassification(modulePath));
    }

    /**
     * The module-identity-layer-seeded constructor: the production
     * orchestrator and identity-aware harnesses supply the compilation's
     * module-path classification so every class identity this resolver
     * assigns is the layer's own (descriptor-identity-propagation D1).
     * The two-argument constructor keeps the single-module standalone
     * adapter default for direct test callers.
     */
    public NameResolver(String modulePath, ModuleResolver moduleResolver,
                 Set<String> modulesInProgress,
                 Function<String, CanonicalModuleIdentity> moduleClassification) {
        this.modulePath = modulePath;
        this.moduleResolver = moduleResolver;
        this.moduleClassification = Objects.requireNonNull(
            moduleClassification, "moduleClassification must not be null");
        this.root = new SymbolTable();
        this.currentScope = root;
        this.modulesInProgress = modulesInProgress;
    }

    /**
     * The single-module standalone classification adapter (the
     * standalone-surface convention used by JsBackend): the empty
     * module path is the intrinsic builtin Error module and the module
     * path itself is a project module whose configured root text is the
     * path — byte-identical to the pre-carriage
     * {@code @<modulePath>/<Name>} single-module shape.
     */
    private static Function<String, CanonicalModuleIdentity> standaloneClassification(
            String modulePath) {
        Map<String, CanonicalModuleIdentity> map = new HashMap<>();
        map.put("", CanonicalModuleIdentity.BuiltinModule.INSTANCE);
        String effective = modulePath == null ? "" : modulePath;
        if (!effective.isEmpty()) {
            map.put(effective,
                new CanonicalModuleIdentity.ProjectModule(
                    new ProjectModuleIdentity(effective, effective, List.of())));
        }
        return map::get;
    }

    /**
     * The intrinsic builtin {@code Error} synthesis pinned by E2's
     * identity layer: {@code CanonicalClassIdentity(BuiltinModule,
     * "Error")} — the only intrinsic class identity.  Never derived
     * from text.
     */
    public static CanonicalClassIdentity intrinsicErrorIdentity() {
        return new CanonicalClassIdentity(
            CanonicalModuleIdentity.BuiltinModule.INSTANCE, "Error");
    }

    /**
     * The canonical public module identity of the module being resolved
     * (the classification of {@link #modulePath}), or {@code null} for a
     * module the layer classified without one.
     */
    public CanonicalModuleIdentity moduleIdentity() {
        return moduleClassification.apply(modulePath);
    }

    /**
     * Builds the canonical class identity for a class declared in the
     * module with the given wiring path, exclusively through the
     * module-identity layer's classification.  A module without a public
     * identity fails closed here (the pinned invariant violation — the
     * E2010 declaration gate fires before this for published projects).
     */
    private CanonicalClassIdentity classIdentityFor(String wiringPath,
                                                    String className) {
        String mp = wiringPath == null ? "" : wiringPath;
        CanonicalModuleIdentity moduleIdentity = moduleClassification.apply(mp);
        if (moduleIdentity == null) {
            throw new IllegalStateException(
                "no canonical public module identity for module path '" + mp
                    + "': a class there can never carry an identity "
                    + "(internal invariant violation — the identity "
                    + "representability gate precedes class typing)");
        }
        return new CanonicalClassIdentity(moduleIdentity, className);
    }

    /** A Class type carrying the layer-resolved identity. */
    private Type.Class classTypeFor(String name, String wiringPath) {
        return Types.classType(name, classIdentityFor(wiringPath, name));
    }

    /** The intrinsic Error class type (E2's synthesis). */
    private Type.Class errorClassType() {
        return Types.classType("Error", intrinsicErrorIdentity());
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

    /**
     * Positions the resolver's scope pointer for Pass-2 type
     * resolution: the type checker walks statement scopes during
     * checking and keeps this pointer in sync with its own
     * {@code currentScope}, so {@link #resolveTypeNode} resolves
     * nested class names against the scope that lexically contains the
     * resolved annotation (a class declared inside a function body
     * resolves there, never against the stale module root).
     */
    public void setCurrentScope(SymbolTable scope) {
        this.currentScope = scope;
    }

    public List<CompilerDiagnostic> diagnostics() {
        return diagnostics;
    }

    // =======================================================================
    // Intrinsics
    // =======================================================================

    private void seedIntrinsics() {
        // int intrinsic: (number) => int  (with intrinsic resolver for overloads)
        Type.Func intFuncType = new Type.Func(
            List.of(Type.Number.INSTANCE), Type.Int.INSTANCE);
        root.define("int", new Symbol.IntrinsicSymbol("int",
            intFuncType, IntrinsicResolvers.INT));

        // number intrinsic: (int) => number  (with intrinsic resolver for overloads)
        Type.Func numFuncType = new Type.Func(
            List.of(Type.Int.INSTANCE), Type.Number.INSTANCE);
        root.define("number", new Symbol.IntrinsicSymbol("number",
            numFuncType, IntrinsicResolvers.NUMBER));

        // bytes intrinsic (DEAL v1.2, js-v12-int32-bytes D3/D4):
        // bytes(int) => bytes — the zero-filled buffer allocation site.
        // The intrinsic resolver enforces exactly one int argument
        // (IntrinsicResolvers.BYTES); the backend lowers the call site.
        // `bytes` is not a DEAL keyword, and compiler intrinsics sit at
        // the bottom of the spec's name-resolution order
        // (docs/spec-v1.3.md §Name resolution: module-level declarations
        // resolve at step 3, compiler intrinsics only at step 5), so a module-level user
        // declaration named `bytes` (class, function, let, or import)
        // shadows this root binding — the declaration sites remove the
        // intrinsic binding before defining their own symbol
        // (see {@link #isShadowableIntrinsic}).
        Type.Func bytesFuncType = new Type.Func(
            List.of(Type.Int.INSTANCE), Type.Bytes.INSTANCE);
        root.define("bytes", new Symbol.IntrinsicSymbol("bytes",
            bytesFuncType, IntrinsicResolvers.BYTES));

        // F7: The `has` intrinsic is handled by HasExpr in the type checker,
        // not via call-intrinsic path.  Store it with a boolean type for
        // symbol-table correctness; the intrinsic resolver is dead code
        // but kept for robustness.
        root.define("has", new Symbol.IntrinsicSymbol("has",
            Type.Boolean.INSTANCE, IntrinsicResolvers.HAS));

        Span synth = Span.synthetic(modulePath);

        BuiltinErrorDeclaration builtinError =
            BuiltinErrorDeclaration.synthesized(synth);
        List<ClassField> errorFields = new ArrayList<>();
        for (BuiltinErrorDeclaration.Field field : builtinError.fields()) {
            errorFields.add(field.declaration());
        }
        root.define("Error", new Symbol.ClassSymbol("Error", errorFields, "",
            builtinError.identity()));
    }

    /**
     * True when the root-local binding with the given name is the
     * {@code bytes} compiler intrinsic and a user module-level
     * declaration may shadow it.
     *
     * <p>DEAL v1.3 resolution order (docs/spec-v1.3.md §Name resolution):
     * module-level declarations resolve at step 3 and imported module
     * bindings at step 4, before the compiler intrinsics
     * ({@code int}/{@code number}/{@code bytes}/{@code has}) at step 5.
     * {@code bytes} is the only one of those four a user identifier can
     * spell (the others are keywords or are shadow-rejected as before),
     * so it is the only intrinsic a module-level declaration may
     * shadow: the declaration sites remove the intrinsic root binding
     * and define the user symbol in its place. The {@code bytes} type
     * annotation keeps its class-symbol-first guard
     * ({@link #resolveNamedType}): a checker-accepted user class named
     * {@code bytes} resolves to its {@link Symbol.ClassSymbol} type and
     * wins over the primitive, exactly like the retired JS-backend
     * defensive arm's guard.</p>
     */
    private boolean isShadowableIntrinsic(String name) {
        return name.equals("bytes")
            && root.resolveLocal(name) instanceof Symbol.IntrinsicSymbol;
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

        // Check for $ in import alias (E2008)
        checkNoDollar(alias, imp.span());

        // F2: Circular import detection — check if the IMPORTED module
        // is already being resolved (not the importing module). Every
        // import cycle is a compile-time error (v1.3); the
        // orchestrator's phase-2 complete-graph admission owns the
        // production rejection, and this recursion guard keeps the
        // frontend entrypoints fail-closed when a resolver recurses.
        if (modulesInProgress.contains(path)) {
            error(DiagnosticCode.E2005, "Circular import: '" + path + "'",
                imp.span());
            return;
        }

        try {
            // F2: Track the IMPORTED module, not the importing module.
            modulesInProgress.add(path);
            Map<String, Type> exports = moduleResolver.resolveModule(
                path, modulePath, modulesInProgress);
            Map<String, Symbol.ClassSymbol> importedClasses =
                moduleResolver.resolveModuleClassSymbols(
                    path, modulePath, modulesInProgress);
            // An import binding named `bytes` shadows the lowest-tier
            // bytes intrinsic (imports resolve at step 4, intrinsics at
            // step 5); the intrinsic root binding must be removed before
            // the ModuleSymbol defines, or the define would collide.
            if (isShadowableIntrinsic(alias)) {
                root.remove(alias);
            }
            root.define(alias, new Symbol.ModuleSymbol(alias, exports, imp.span(),
                importedClasses));
        } catch (ModuleResolver.CffiImportWithoutNativeLibraryException e) {
            // The C FFI manifest policy (docs/spec-v1.3.md §Package manifest):
            // importing a C FFI declaration file without an externals
            // entry specifying nativeLibrary is an invalid project
            // configuration — E2010 at the import span, never a
            // module-not-found E2003.
            error(DiagnosticCode.E2010,
                "C FFI declaration file '" + e.modulePath()
                    + "' is imported without an externals entry "
                    + "specifying nativeLibrary (a C FFI entry must "
                    + "include nativeLibrary)",
                imp.span());
        } catch (ModuleResolver.ModuleNotFoundException e) {
            error(DiagnosticCode.E2003, "Module not found: '" + path + "'", imp.span());
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

        // Check for $ in class name (E2008)
        checkNoDollar(name, cd.span());

        // Check for user-declared class Error (E4006)
        if (name.equals("Error")) {
            error(DiagnosticCode.E4006, "Cannot declare class 'Error': 'Error' is a built-in type", cd.span());
            return;
        }

        if (root.containsLocally(name)) {
            if (isShadowableIntrinsic(name)) {
                // A user module-level class named `bytes` shadows the
                // lowest-tier bytes intrinsic (step 3 before step 5):
                // drop the intrinsic binding and let the ClassSymbol
                // define below — the class then wins over the primitive
                // in every later resolution.
                root.remove(name);
            } else if (shadowsImport(name, cd.span())) {
                return;
            } else {
                error(DiagnosticCode.E2002, "Redeclaration of '" + name + "'", cd.span());
                return;
            }
        }
        Symbol.ClassSymbol classSymbol = new Symbol.ClassSymbol(name, cd.fields(),
            modulePath, classIdentityFor(modulePath, name));
        classDeclarationNodes.put(classSymbol, cd);
        root.define(name, classSymbol);

        for (ClassField cf : cd.fields()) {
            checkNoDollar(cf.name(), cf.span());
        }

        // D6: Add synthetic C$fromJson and C$toJson function symbols for @jsonable classes.
        // These are added programmatically and never pass through the user-identifier
        // $ prohibition check (checkNoDollar is not called for these names).
        if (cd.isJsonable()) {
            Type clsType = classTypeFor(cd.name(), modulePath);

            Type.Func fromJsonType = new Type.Func(
                List.of(Type.String.INSTANCE),
                Types.nullable(clsType));
            root.define(cd.name() + "$fromJson",
                new Symbol.FunctionSymbol(cd.name() + "$fromJson", fromJsonType));

            Type.Func toJsonType = new Type.Func(
                List.of(clsType),
                Type.String.INSTANCE);
            root.define(cd.name() + "$toJson",
                new Symbol.FunctionSymbol(cd.name() + "$toJson", toJsonType));
        }
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

        // Check for $ in function name (E2008). A declaration file may
        // explicitly declare the generated @jsonable helper signatures
        // (spec §Declaration-file format: "Declaration files may
        // explicitly declare compatible C$fromJson and C$toJson
        // functions") — the reserved sigil is legitimate exactly there
        // (ISSUE-0717 defect snippet 51).
        if (!(isDeclarationFile() && isJsonHelperFunctionName(name))) {
            checkNoDollar(name, fd.span());
        }

        if (root.containsLocally(name)) {
            if (isShadowableIntrinsic(name)) {
                // A user module-level function named `bytes` shadows the
                // lowest-tier bytes intrinsic (step 3 before step 5):
                // drop the intrinsic binding and let the FunctionSymbol
                // define below — calls then resolve to the user function.
                root.remove(name);
            } else if (shadowsImport(name, fd.span())) {
                return;
            } else {
                error(DiagnosticCode.E2002, "Redeclaration of '" + name + "'", fd.span());
                return;
            }
        }

        Type funcType = resolveTypeNode(fd.returnType());
        List<Type> paramTypes = new ArrayList<>();
        for (Parameter p : fd.params()) {
            paramTypes.add(resolveTypeNode(p.type()));
        }

        Type.Func ft = new Type.Func(paramTypes, funcType, fd.isAsync());
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
                error(DiagnosticCode.E2006,
                    "Import '" + name + "' shadows module-level declaration",
                    ms.importSpan());
                root.remove(name);
                return false; // allow the declaration to be added below
            }
            // Declaration came after import → E2007
            error(DiagnosticCode.E2007,
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
            case ForOfStatement fos      -> walkForOf(fos);
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
            case TemplateLiteralExpr tl -> {
                for (ExpressionNode part : tl.parts()) {
                    walkExpression(part);
                }
            }
            case AwaitExpression await -> walkExpression(await.callee());
            default -> { /* leaf expression — no nested FunctionExpr possible */ }
        }
    }

    private void walkVarDecl(VariableDeclaration vd) {
        String name = vd.name();

        // Check for $ in let-variable name (E2008)
        checkNoDollar(name, vd.span());

        Type type = vd.typeAnnotation().map(this::resolveTypeNode).orElse(null);
        Symbol.ClassSymbol declaredClass =
            vd.typeAnnotation().map(this::declaredClassSymbolOf).orElse(null);
        if (currentScope.containsLocally(name)) {
            if (currentScope == root && isShadowableIntrinsic(name)) {
                // A module-level let named `bytes` shadows the
                // lowest-tier bytes intrinsic (step 3 before step 5):
                // drop the intrinsic binding and let the variable
                // define below.
                root.remove(name);
            } else if (currentScope == root && shadowsImport(name, vd.span())) {
                return;
            } else {
                error(DiagnosticCode.E2002, "Redeclaration of '" + name + "'", vd.span());
                return;
            }
        }
        // v1.2 declaration order: `let` declarations are not hoisted and
        // are visible only after their declaration, so the initializer is
        // resolved before the name is defined (a forward reference is
        // E2001; a self reference resolves against an enclosing binding,
        // never against the declaration being initialized).
        // F1: Walk the initializer expression to find any FunctionExpr nodes
        walkExpression(vd.initializer());

        currentScope.define(name,
            new Symbol.VariableSymbol(name, type, false, declaredClass,
                vd.typeAnnotation().orElse(null)));
    }

    /**
     * The local class declaration a type annotation names, or
     * {@code null} when the annotation does not pin one (primitives,
     * imported classes, function types). The pin travels on the declared
     * variable so the assignment gate can tell two same-named class
     * declarations of one module apart (ISSUE-0717 defect snippet 47).
     */
    public Symbol.ClassSymbol declaredClassSymbolOf(TypeNode tn) {
        if (tn instanceof NamedType nt) {
            Symbol sym = currentScope.resolve(nt.name());
            if (sym instanceof Symbol.ClassSymbol cs) {
                return cs;
            }
            return null;
        }
        if (tn instanceof NullableType nt) {
            return declaredClassSymbolOf(nt.innerType());
        }
        return null;
    }

    /**
     * The exact AST declaration a class symbol this resolver defined was
     * built from, or {@code null} for imported and synthetic class
     * symbols (whose declarations are not in this module's AST). Keyed by
     * symbol identity: two same-named declarations of one module stay
     * distinct symbols and distinct nodes.
     */
    public ClassDeclaration classDeclarationNode(Symbol.ClassSymbol cs) {
        return classDeclarationNodes.get(cs);
    }

    /**
     * Walk a class declaration in statement context.
     * At module scope, classes are already hoisted.
     * Inside a function or block, we must add them to the current scope.
     */
    private void walkClassDecl(ClassDeclaration cd) {
        String name = cd.name();

        // Check for $ in nested class name (E2008)
        checkNoDollar(name, cd.span());

        // Check for user-declared class Error in nested context (E4006)
        if (name.equals("Error")) {
            error(DiagnosticCode.E4006, "Cannot declare class 'Error': 'Error' is a built-in type", cd.span());
            return;
        }

        // Check for $ in class field names (E2008), and record ordinary
        // function-expression scopes inside defaults before type checking.
        for (ClassField cf : cd.fields()) {
            checkNoDollar(cf.name(), cf.span());
            cf.defaultExpr().ifPresent(this::walkExpression);
        }

        if (!currentScope.containsLocally(name)) {
            // Not already hoisted (nested class inside a function/block).
            // ISSUE-0717 (defect snippet 47): a nested class may shadow an
            // enclosing class of the same name (the JS scope-local
            // predeclare/assign shape and the declaration-keyed plans rely
            // on it); the two declarations stay distinct symbols. The
            // cross-declaration value assignment is rejected at the
            // assignment gate (E3019), never the declaration.
            Symbol.ClassSymbol classSymbol = new Symbol.ClassSymbol(name,
                cd.fields(), modulePath, classIdentityFor(modulePath, name));
            classDeclarationNodes.put(classSymbol, cd);
            currentScope.define(name, classSymbol);
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

        bindParameters(fe.params());

        // DEAL v1.2: function expressions have no rest parameters.

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

            Type.Func ft = new Type.Func(paramTypes, funcType, fd.isAsync());
            currentScope.define(fd.name(), new Symbol.FunctionSymbol(fd.name(), ft));
        }

        SymbolTable saved = currentScope;
        currentScope = currentScope.enterScope();
        scopeMap.put(fd, currentScope);

        bindParameters(fd.params());

        // DEAL v1.2: function declarations have no rest parameters.

        walkStatement(fd.body());
        currentScope = saved;
    }

    /**
     * Defines one function's parameters in the current scope: the E2008
     * dollar check and the E2002 duplicate-name rejection in declaration
     * order, then the parameter symbol.
     */
    private void bindParameters(List<Parameter> params) {
        Set<String> paramNames = new HashSet<>();
        for (Parameter p : params) {
            String name = p.name();

            // Check for $ in parameter name (E2008)
            checkNoDollar(name, p.span());

            if (paramNames.contains(name)) {
                error(DiagnosticCode.E2002, "Duplicate parameter '" + name + "'", p.span());
                continue;
            }
            paramNames.add(name);
            Type paramType = resolveTypeNode(p.type());
            currentScope.define(name, new Symbol.VariableSymbol(name, paramType,
                true, declaredClassSymbolOf(p.type()), p.type()));
        }
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

                // Check for $ in for-loop variable name (E2008)
                checkNoDollar(name, decl.span());

                Type type = decl.typeAnnotation().map(this::resolveTypeNode).orElse(null);
                Symbol.ClassSymbol declaredClass = decl.typeAnnotation()
                    .map(this::declaredClassSymbolOf).orElse(null);
                // The loop-head let is visible only after its declaration
                // (docs/spec-v1.3.md §Name resolution — Declaration order):
                // its initializer is walked before the loop variable is defined, so the
                // initializer resolves to an enclosing binding of the
                // same name (the C-style for desugars to block-local
                // scope plus a while loop).
                walkExpression(decl.initializer());
                currentScope.define(name,
                    new Symbol.VariableSymbol(name, type, false, declaredClass,
                        decl.typeAnnotation().orElse(null)));
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

    /**
     * D13: Walk a for-of statement.
     * The iterable expression and var type are resolved in the PARENT scope.
     * The loop variable is defined in a new child scope.
     * The body Block creates its own scope via walkBlock().
     * ForOfStatement is NOT added to scopeMap — only the body Block.
     */
    private void walkForOf(ForOfStatement fos) {
        // Walk iterable expression in PARENT scope (before scope entry)
        walkExpression(fos.iterable());
        // Resolve var type in PARENT scope
        Type varType = resolveTypeNode(fos.varType());
        Symbol.ClassSymbol varDeclaredClass = declaredClassSymbolOf(fos.varType());

        // Check for $ in for-of loop variable name (E2008)
        checkNoDollar(fos.varName(), fos.span());

        // Enter new scope for loop variable and body
        SymbolTable saved = currentScope;
        currentScope = currentScope.enterScope();
        // NOTE: ForOfStatement is NOT added to scopeMap.
        // Only the body Block gets a scopeMap entry via walkBlock().

        // Define the loop variable in the new scope
        currentScope.define(fos.varName(),
            new Symbol.VariableSymbol(fos.varName(), varType, false,
                varDeclaredClass, fos.varType()));

        loopDepth++;
        walkBlock(fos.body()); // Creates block scope, records in scopeMap
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
                errorClassType(), true));
        walkBlock(ts.catchBlock());
        currentScope = saved;
    }

    // F8: Validate break/continue inside loops
    private void walkBreak(BreakStatement bs) {
        if (loopDepth == 0) {
            error(DiagnosticCode.E2000, "'break' must be inside a loop", bs.span());
        }
    }

    private void walkContinue(ContinueStatement cs) {
        if (loopDepth == 0) {
            error(DiagnosticCode.E2000, "'continue' must be inside a loop", cs.span());
        }
    }

    // =======================================================================
    // $ prohibition helper
    // =======================================================================

    /**
     * Validates that a user-declared identifier does not contain '$'.
     * Emits E2008 if it does.
     */
    private void checkNoDollar(String name, Span span) {
        if (name.indexOf('$') >= 0) {
            error(DiagnosticCode.E2008, "Identifier '" + name + "' must not contain '$'", span);
        }
    }

    /** Whether this module is a {@code .d.deal} declaration file. */
    private boolean isDeclarationFile() {
        return modulePath != null && modulePath.endsWith(".d.deal");
    }

    /**
     * Whether one declared function name is the generated
     * {@code C$fromJson}/{@code C$toJson} helper form of a declaration
     * file (a plain identifier class part plus the reserved helper
     * suffix).
     */
    private static boolean isJsonHelperFunctionName(String name) {
        int sigil = name.lastIndexOf('$');
        if (sigil <= 0 || sigil == name.length() - 1) {
            return false;
        }
        String suffix = name.substring(sigil + 1);
        if (!suffix.equals("fromJson") && !suffix.equals("toJson")) {
            return false;
        }
        char first = name.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (int i = 1; i < sigil; i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    // =======================================================================
    // Type resolution from TypeNode
    // =======================================================================

    /**
     * Resolves an AST {@link TypeNode} to an internal {@link Type}
     * against the resolver's current lexical scope.
     */
    public Type resolveTypeNode(TypeNode tn) {
        return switch (tn) {
            case NamedType nt -> resolveNamedType(nt);
            case QualifiedType qt -> resolveQualifiedType(qt);
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
                    error(DiagnosticCode.E3005, "Invalid nullable type: " + e.getMessage(), tn.span());
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
                Type ret = resolveTypeNode(ft.returnType());
                if (ret == Type.Error.INSTANCE) yield Type.Error.INSTANCE;
                yield new Type.Func(paramTypes, ret, ft.isAsync());
            }
        };
    }

    /**
     * Resolves a qualified type reference like {@code ModuleAlias.ClassName}.
     * Looks up the module alias in scope, then the class in that module's exports.
     */
    private Type resolveQualifiedType(QualifiedType qt) {
        Symbol sym = currentScope.resolve(qt.moduleName());
        if (!(sym instanceof Symbol.ModuleSymbol ms)) {
            error(DiagnosticCode.E3004, "Unknown module '" + qt.moduleName() + "'", qt.span());
            return Type.Error.INSTANCE;
        }
        Type exportType = ms.exports().get(qt.typeName());
        if (exportType == null) {
            error(DiagnosticCode.E2004, "Export '" + qt.typeName() + "' not found in module '"
                + qt.moduleName() + "'. Available: "
                + String.join(", ", ms.exports().keySet()), qt.span());
            return Type.Error.INSTANCE;
        }
        return exportType;
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
            case "Error"     -> errorClassType();

            case "bytes" -> {
                Symbol sym = currentScope.resolve(name);
                if (sym instanceof Symbol.ClassSymbol cs) {
                    yield Types.classType(cs.name(), cs.identity());
                }
                yield Type.Bytes.INSTANCE;
            }
            default -> {
                Symbol sym = currentScope.resolve(name);
                if (sym instanceof Symbol.ClassSymbol cs) {
                    yield Types.classType(cs.name(), cs.identity());
                }
                diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E3004, "Unknown type '" + name + "'", nt.span())
                        .withMissingSymbol("TYPE", name, modulePath, List.of("class")));
                yield Type.Error.INSTANCE;
            }
        };
    }

    /**
     * Resolves the class symbol a {@link Type.Class} names through the
     * carried canonical identity (descriptor-identity-propagation D1):
     * a class whose identity's module equals this module's resolves
     * locally; every other identity routes through the module
     * resolver's identity-keyed lookup (imported classes carry the
     * declaring source's identity — never a reconstructed dotted
     * path).
     *
     */
    public Symbol.ClassSymbol resolveClassSymbol(Type.Class cls) {
        CanonicalModuleIdentity mine = moduleIdentity();
        if (mine != null
                && cls.identity().moduleIdentity().equals(mine)) {
            // Local class — look it up in the current scope first (the
            // name-based local precedence today's behavior has).
            Symbol sym = currentScope.resolve(cls.name());
            if (sym instanceof Symbol.ClassSymbol cs) return cs;
        }
        // Fall through to the module resolver's identity-keyed routing:
        // two files in one directory share a module identity (the
        // relative components exclude the file stem), so a foreign
        // class's identity can equal this module's — the declaring
        // module's symbol table is the only authority for it.
        try {
            return moduleResolver.resolveClassSymbol(
                cls.name(), cls.identity().moduleIdentity(), this.modulePath);
        } catch (ModuleResolver.ModuleNotFoundException e) {
            return null;
        }
    }

    /**
     * Resolves a class symbol from another module by its wiring path
     * (retained for the declaration-metadata and legacy routing
     * consumers that hold the private deployment module id, never
     * public text).
     *
     */
    public Symbol.ClassSymbol resolveClassSymbol(String className, String modulePath) {
        if (modulePath == null || modulePath.isEmpty()
                || modulePath.equals(this.modulePath)) {
            // Local class — look it up in the current scope
            Symbol sym = currentScope.resolve(className);
            if (sym instanceof Symbol.ClassSymbol cs) return cs;
            return null;
        }
        try {
            return moduleResolver.resolveClassSymbol(className, modulePath, this.modulePath);
        } catch (ModuleResolver.ModuleNotFoundException e) {
            return null;
        }
    }

    /**
     * Resolves a type node against the scope of another module.
     *
     * <p>Delegates to the module resolver; used by the type checker for
     * cross-module class-field type annotations. Returns {@code null}
     * when the target module cannot be found or does not support the
     * resolution (callers fall back to local resolution).</p>
     *
     */
    public Type resolveTypeNodeInModule(TypeNode typeNode, String modulePath) {
        if (modulePath == null || modulePath.isEmpty()
                || modulePath.equals(this.modulePath)) {
            return resolveTypeNode(typeNode);
        }
        try {
            return moduleResolver.resolveTypeNodeInModule(
                typeNode, modulePath, this.modulePath);
        } catch (ModuleResolver.ModuleNotFoundException e) {
            return null;
        }
    }

    /**
     * Resolves the exact module-level class declaration a class-typed
     * annotation names in this module's scope, or {@code null} for a
     * primitive/function/foreign-surfaceless annotation: a qualified
     * spelling resolves the alias's own declared class symbol (the
     * imported module's class surface), a bare name its local symbol.
     * Two same-named classes of sibling modules in one directory share
     * one canonical identity, so the declared symbol — never an
     * identity-first or textual lookup — names the declaration the
     * annotation selected.
     */
    public Symbol.ClassSymbol resolveTypeNodeClassSymbol(TypeNode typeNode) {
        return classSymbolInScope(root, typeNode);
    }

    /**
     * The declaring module's exact declared class symbol of one
     * annotation: this module's scope for a same-module (or empty)
     * declaring module, the module resolver's declared-symbol surface
     * otherwise (implementation modules carry their own checked symbol
     * tables; declaration modules resolve through their synthesized
     * resolver). {@code null} when no exact class declaration resolves.
     */
    public Symbol.ClassSymbol resolveTypeNodeClassSymbolInModule(
            TypeNode typeNode, String modulePath) {
        if (modulePath == null || modulePath.isEmpty()
                || modulePath.equals(this.modulePath)) {
            return resolveTypeNodeClassSymbol(typeNode);
        }
        try {
            return moduleResolver.resolveTypeNodeClassSymbolInModule(
                typeNode, modulePath, this.modulePath);
        } catch (ModuleResolver.ModuleNotFoundException e) {
            return null;
        }
    }

    /**
     * The exact class symbol one annotation names in the supplied scope
     * (the same shape {@code TypeChecker#exactAnnotationClassSymbol}
     * consumes): the local declaration of a bare name, the imported
     * module's own class symbol of a qualified name, and the recursive
     * inner forms of nullable/array annotations.
     */
    public static Symbol.ClassSymbol classSymbolInScope(SymbolTable scope,
                                                        TypeNode typeNode) {
        if (scope == null || typeNode == null) {
            return null;
        }
        return switch (typeNode) {
            case NamedType nt -> {
                Symbol sym = scope.resolve(nt.name());
                yield sym instanceof Symbol.ClassSymbol cs ? cs : null;
            }
            case QualifiedType qt -> {
                Symbol sym = scope.resolve(qt.moduleName());
                yield sym instanceof Symbol.ModuleSymbol ms
                    ? ms.classSymbols().get(qt.typeName()) : null;
            }
            case NullableType nt -> classSymbolInScope(scope, nt.innerType());
            case ArrayType at -> classSymbolInScope(scope, at.elementType());
            default -> null;
        };
    }

    /**
     * Checks whether a function is exported from a given module.
     *
     * <p>For same-module queries ({@code modulePath} is null, empty, or
     * equals {@code this.modulePath}), the root symbol table is checked
     * directly.  For cross-module queries, the module resolver is used
     * to check the target module's export map.
     *
     * <p>This is used by the type checker ({@link TypeChecker}) to
     * validate that a class type used as a jsonable field has the
     * required {@code C$fromJson} / {@code C$toJson} exports.
     *
     */
    public boolean isFunctionExportedFromModule(String modulePath, String functionName) {
        if (modulePath == null || modulePath.isEmpty()
                || modulePath.equals(this.modulePath)) {
            // Same-module: check the root symbol table directly
            return root.resolve(functionName) != null;
        }
        try {
            Map<String, Type> exports = moduleResolver.resolveModule(
                modulePath, this.modulePath, new HashSet<>());
            return exports.containsKey(functionName);
        } catch (ModuleResolver.ModuleNotFoundException e) {
            return false;
        } catch (ModuleResolver.CffiImportWithoutNativeLibraryException e) {
            // Unreachable for a module whose import already passed the
            // manifest-policy check; an unexported function is simply
            // not found.
            return false;
        }
    }

    /**
     * Identity-keyed variant of
     * {@link #isFunctionExportedFromModule(String, String)} for a
     * class type's declaring module: same-module identities check the
     * root symbol table; foreign identities route through the module
     * resolver's identity-keyed export lookup.
     */
    public boolean isFunctionExportedFromModule(
            CanonicalModuleIdentity declaringModule, String functionName) {
        CanonicalModuleIdentity mine = moduleIdentity();
        if (mine != null && declaringModule.equals(mine)
                && root.resolve(functionName) != null) {
            return true;
        }
        // Two files in one directory share the module identity: keep
        // routing to the module resolver (the declaring file's exports).
        try {
            return moduleResolver.isFunctionExportedFromModule(
                declaringModule, functionName, this.modulePath);
        } catch (ModuleResolver.ModuleNotFoundException e) {
            return false;
        }
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    private void error(DiagnosticCode code, String message, Span span) {
        diagnostics.add(CompilerDiagnostic.error(code, message, span));
    }
}
