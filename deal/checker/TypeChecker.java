package deal.checker;

import deal.ast.*;
import deal.diagnostics.CompilerDiagnostic;
import deal.identity.CanonicalClassIdentity;
import deal.identity.CanonicalModuleIdentity;
import deal.types.Type;
import deal.types.Types;

import java.util.*;
import deal.diagnostics.DiagnosticCode;

/**
 * Pass 2 of the type checker: type checking.
 *
 * <p>Walks the AST, computes the type of every expression (storing in a
 * {@code Map<ExpressionNode, Type>}), verifies statement rules, manages
 * null narrowing, contextual typing for table reads, and class construction
 * checking.
 *
 * <p>Errors produced: E3001–E3018, E4001–E4008, E5001–E5004.</p>
 */
public final class TypeChecker {

    private final String modulePath;
    private final SymbolTable rootTable;
    private final NameResolver nameResolver;
    private final Map<StatementNode, SymbolTable> scopeMap;
    private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();

    // -- Accumulated results --
    private final Map<ExpressionNode, Type> typeMap = new HashMap<>();

    // -- Null narrowing (mutable during checking) --
    private NullNarrowing narrowing = new NullNarrowing();

    // -- Contextual expected type (for table reads and class construction) --
    private Type expectedType = null;

    // -- Direct contextual-read position: true only while the expression
    //    being checked is the outermost expression of one of the five
    //    admitted positions (docs/spec-v1.3.md §Reading table fields — a
    //    let initializer, an assignment value, a return expression, a call
    //    argument, an if condition). A table read the check descends into
    //    (a literal element or class-literal field value, a template
    //    interpolation part, a composite operand, an index key, a callee)
    //    is not the position's own expression and takes no contextual
    //    target type. --
    private boolean contextualReadPosition = false;

    // -- The one-shot marker a read position sets for its own outermost
    //    expression: the next checked expression is that position's
    //    expression, every later one is nested. --
    private boolean pendingContextualReadPosition = false;

    // -- The exact class declaration that supplies the current contextual
    //    type. Set only where a binding or an annotated declaration is the
    //    context's source: an object literal constructed under that context
    //    is laid out by that declaration, never by an unrelated same-named
    //    declaration the lexical scope happens to resolve (a harness-shared
    //    identity's two declarations are indistinguishable in Type.Class
    //    alone). --
    private Symbol.ClassSymbol contextualClassDecl = null;

    // -- Checked expression facts: the exact class declaration a
    //    contextually constructed object literal was laid out by, keyed by
    //    the literal node. The E3019 cross-declaration gate consumes it at
    //    initialization and assignment-expression boundaries, so a value
    //    that crosses two distinct same-named declarations is rejected
    //    before any backend sees the duplicate layout. --
    private final Map<ExpressionNode, Symbol.ClassSymbol> constructedClassDecls =
        new IdentityHashMap<>();

    // -- Checked expression facts: the exact module-level class declaration
    //    a class-typed expression's own binding/field/context annotation
    //    names, keyed by the expression node. The canonical identity names
    //    the defining file, but a harness-shared identity's two declarations
    //    are still indistinguishable in Type.Class alone, so the one lowering
    //    consumes this record to select the exact declaring provider of a
    //    field operation (never a unit-local or identity-keyed first
    //    match). --
    private final Map<ExpressionNode, Symbol.ClassSymbol> exactClassDecls =
        new IdentityHashMap<>();

    // -- Checked expression facts: the AST declaration node behind the
    //    symbol above (never the symbol alone: two same-named declarations
    //    with identical fields are equal records yet distinct
    //    declarations). Published through CheckResult so the JS emitter
    //    selects that declaration's own factory binding instead of
    //    re-resolving the lexical class name, whose innermost binding may
    //    be the shadowing declaration (ISSUE-0717 review 0007). --
    private final Map<ExpressionNode, ClassDeclaration> constructedDeclNodes =
        new IdentityHashMap<>();

    // -- Checked expression facts: the identifier reads whose checked type
    //    is a null-narrowing refinement of the binding's declared nullable
    //    type (declared T | null read as T), keyed by the read node. The
    //    refinement is a flow-sensitive claim about a mutable binding: an
    //    indirect mutation (a capturing closure call) can leave the binding
    //    null while the claim still holds, so the one lowering validates
    //    every such read at an operator operand/condition, index
    //    receiver/key and table member receiver position (the positions
    //    with no owning typed boundary) and reports the standard
    //    typed-boundary projection instead of coercing the value or letting
    //    a raw backend error escape. The fact is recorded here because the
    //    checker owns the narrowing analysis and its scope-correct binding
    //    resolution (a shadowing block-local declaration never contributes).
    private final Set<ExpressionNode> narrowedReads = new HashSet<>();
    private final Map<InstanceofExpr, Type.Class> instanceofTargets = new HashMap<>();

    // -- Assignment tracking for the open try regions of the current
    //    function: one frame per open try block, holding the resolved
    //    bindings assigned inside that region. Entries are binding
    //    symbols (identity, never value equality: two same-named
    //    declarations are distinct bindings), so a try-block declaration,
    //    a catch binding or a catch-local declaration that shadows an
    //    enclosing binding and a write inside a nested function body never
    //    revert another binding's refinement
    //    (docs/spec-v1.3.md §Narrowing across try/catch). The stack is
    //    swapped at function boundaries: declaring a function does not
    //    execute its body, so its writes are the nested function's own
    //    flow and the enclosing try region never records them. --
    private Deque<Set<Symbol>> tryAssignments = new ArrayDeque<>();

    // -- Async/await tracking --
    private boolean insideAsyncFunction = false;
    private CallExpr directAwaitCallee = null;

    // -- Current function return type (for return checking) --
    private Type currentReturnType = null;

    // -- The exact class declaration the current function's declared
    //    return annotation names (resolved in the scope of the function's
    //    own declaration). A returned object literal is laid out by that
    //    declaration, so an annotated return of a same-identity sibling
    //    module's class keeps the declared provider instead of the
    //    scope's same-named local declaration. --
    private Symbol.ClassSymbol currentReturnContext = null;

    // -- Current scope for name resolution --
    private SymbolTable currentScope;

    // -- Loop depth for break/continue validation (F8) --
    private int loopDepth = 0;

    // -- Variables assigned inside the innermost loop body: the loop
    //    back-edge invalidates their narrowed types after the loop (v1.2
    //    "narrowing does not cross loop back-edges"). Entries are the
    //    resolved binding symbols (identity, never value equality: two
    //    same-named declarations are distinct bindings). --
    private Deque<Set<Symbol>> loopAssignments = new ArrayDeque<>();

    // -- Saved pre-loop narrowing states of the open loops: a loop body
    //    is checked under a fresh narrowing state, so an await inside a
    //    body must invalidate the enclosing function's saved states too
    //    (docs/spec-v1.5.md §Nullable semantics: after an await every
    //    narrowed variable of the enclosing async function reverts to
    //    its declared type, and a loop exit must not restore an entry
    //    the suspension invalidated). The stack is swapped at function
    //    boundaries exactly like the narrowing object itself, so a
    //    nested function's suspension never invalidates the enclosing
    //    function's refinements. --
    private Deque<NullNarrowing> loopSavedNarrowing = new ArrayDeque<>();

    // -- Non-hoisting let visibility (v1.2 declaration order): one frame
    //    per open block scope, holding the block's own let-declared names
    //    that have not been reached yet. A reference to such a name
    //    resolves against the enclosing scopes until its declaration is
    //    walked. --
    private static final class PendingScope {
        final SymbolTable scope;
        final Set<String> remaining;
        PendingScope(SymbolTable scope, Set<String> remaining) {
            this.scope = scope;
            this.remaining = remaining;
        }
    }

    private final ArrayDeque<PendingScope> pendingScopes = new ArrayDeque<>();

    // -- Write-context flag: when true, table member/index access is
    //    treated as a write target (no contextual typing required) --
    private boolean assignmentTargetMode = false;

    private final Map<ClassDeclaration, SymbolTable> classDeclarationScopes =
        new LinkedHashMap<>();

    // -- Jsonable dependency graph (D3): nodes and edges are canonical
    //    class identities, so same-named classes in different modules
    //    never conflate (identity, not name, establishes identity). --
    private final Map<CanonicalClassIdentity, Set<CanonicalClassIdentity>>
        jsonableClassDeps = new LinkedHashMap<>();
    // Maps @jsonable class identity → its class declaration span (E4008 anchor)
    private final Map<CanonicalClassIdentity, Span> jsonableClassSpans = new LinkedHashMap<>();

    private TypeChecker(String modulePath, SymbolTable rootTable,
                        NameResolver nameResolver,
                        Map<StatementNode, SymbolTable> scopeMap) {
        this.modulePath = modulePath;
        this.rootTable = rootTable;
        this.nameResolver = nameResolver;
        this.scopeMap = scopeMap;
        this.currentScope = rootTable;
    }

    // =======================================================================
    // Public API
    // =======================================================================

    /**
     * Type-checks a program and returns the result.
     */
    public static CheckResult check(String modulePath, SymbolTable symbolTable,
                                     NameResolver nameResolver, ProgramNode program) {
        Map<StatementNode, SymbolTable> scopeMap = nameResolver.scopeMap();
        TypeChecker checker = new TypeChecker(modulePath, symbolTable,
            nameResolver, scopeMap);
        // The module scope is a block scope for the non-hoisting rule: a
        // module-level let is visible only after its declaration.
        checker.pushPendingScope(program.statements(), symbolTable);
        checker.walkStatements(program.statements());
        checker.detectJsonableCycles();
        return new CheckResult(
            Map.copyOf(checker.typeMap),
            checker.rootTable,
            Map.copyOf(scopeMap),
            List.copyOf(checker.diagnostics),
            Map.copyOf(checker.classDeclarationScopes),
            Map.copyOf(checker.constructedDeclNodes),
            Map.copyOf(checker.exactClassDecls),
            Set.copyOf(checker.narrowedReads),
            Map.copyOf(checker.instanceofTargets)
        );
    }

    // =======================================================================
    // Context interface for intrinsic resolvers
    // =======================================================================

    public interface Context {
        Type typeOf(ExpressionNode expr);
        Symbol resolveSymbol(String name);
        void error(DiagnosticCode code, String message, Span span);
    }

    private final Context ctx = new Context() {
        @Override public Type typeOf(ExpressionNode expr) {
            return TypeChecker.this.typeOf(expr);
        }
        @Override public Symbol resolveSymbol(String name) {
            return currentScope.resolve(name);
        }
        @Override public void error(DiagnosticCode code, String message, Span span) {
            TypeChecker.this.error(code, message, span);
        }
    };

    // =======================================================================
    // Statement walking (scope-aware)
    // =======================================================================

    private void walkStatements(List<StatementNode> statements) {
        for (StatementNode stmt : statements) {
            walkStatement(stmt);
        }
    }

    private void walkStatement(StatementNode stmt) {
        // Block-local declarations restore outer narrowing when the block exits.
        boolean blockScope = stmt instanceof Block;
        boolean shadowScope = blockScope;
        if (shadowScope) narrowing.pushShadowFrame();

        // Check for scope change
        SymbolTable savedScope = currentScope;
        SymbolTable stmtScope = scopeMap.get(stmt);
        if (blockScope) pushPendingScope((Block) stmt, stmtScope);
        if (stmtScope != null) {
            currentScope = stmtScope;

            nameResolver.setCurrentScope(stmtScope);
        }

        switch (stmt) {
            case VariableDeclaration vd -> checkVariableDeclaration(vd);
            case FunctionDeclaration fd  -> checkFunctionDeclaration(fd);
            case ClassDeclaration cd     -> checkClassDeclaration(cd);
            case ReturnStatement rs      -> checkReturnStatement(rs);
            case IfStatement is          -> checkIfStatement(is);
            case WhileStatement ws       -> checkWhileStatement(ws);

            case ForOfStatement fos      -> checkForOfStatement(fos);
            case BreakStatement bs       -> checkBreak(bs);
            case ContinueStatement cs    -> checkContinue(cs);
            case ExpressionStatement es  -> checkExpression(es.expr());
            case ImportDeclaration id    -> { /* already resolved */ }
            case ExportDeclaration ed    -> walkStatement(ed.declaration());
            case DeleteStatement ds      -> checkDeleteStatement(ds);
            case TryStatement ts         -> checkTryStatement(ts);
            case ThrowStatement ts2      -> checkThrowStatement(ts2);
            case Block b                 -> walkStatements(b.statements());
            default                      -> { /* ignore */ }
        }

        currentScope = savedScope;
        nameResolver.setCurrentScope(savedScope);

        if (shadowScope) narrowing.popShadowFrame();
        if (blockScope) pendingScopes.pop();
    }

    /** Pushes one block scope's not-yet-declared let names. */
    private void pushPendingScope(Block block, SymbolTable scope) {
        pushPendingScope(block.statements(), scope);
    }

    /** Pushes one statement list's not-yet-declared let names. */
    private void pushPendingScope(List<StatementNode> statements, SymbolTable scope) {
        Set<String> names = new LinkedHashSet<>();
        if (scope != null) {
            for (StatementNode child : statements) {
                if (child instanceof VariableDeclaration vd
                        && scope.containsLocally(vd.name())) {
                    names.add(vd.name());
                } else if (scope != rootTable && child instanceof FunctionDeclaration fd) {
                    names.add(fd.name());
                }
            }
        }
        pendingScopes.push(new PendingScope(scope, names));
    }

    // =======================================================================
    // Class declaration — also handles @jsonable field validation (D3)
    // =======================================================================

    private void checkClassDeclaration(ClassDeclaration cd) {

        classDeclarationScopes.put(cd, currentScope);

        // Structural declarations still own their annotation diagnostics,
        // even when no construction ever demands the field layout.
        Map<ClassField, Type> fieldTypes = new IdentityHashMap<>();
        for (ClassField field : cd.fields()) {
            fieldTypes.put(field, nameResolver.resolveTypeNode(field.type()));
        }

        if (!cd.isJsonable()) {
            return;
        }

        CanonicalClassIdentity identity = jsonableIdentityOf(cd);
        if (identity == null) {
            // Defensive: a @jsonable declaration whose class symbol and
            // module identity are both unresolvable carries no canonical
            // identity and can neither be a dependency node nor close a
            // cycle; the unresolved-name diagnostics already fired.
            return;
        }

        // Store the class declaration span for later use in E4008 emission
        jsonableClassSpans.put(identity, cd.span());

        // Validate each field type and collect the carried jsonable
        // class dependencies (canonical identities; declaration-only
        // classes generate no helpers and contribute no edge)
        Set<CanonicalClassIdentity> deps = new LinkedHashSet<>();
        for (ClassField cf : cd.fields()) {
            Type fieldType = fieldTypes.get(cf);
            if (fieldType == Type.Error.INSTANCE) continue;

            if (!isJsonableType(fieldType)) {
                error(DiagnosticCode.E4007,
                    "Field type is not jsonable: " + typeName(fieldType),
                    cf.span());
            }

            collectJsonableClassDeps(fieldType, deps);
        }

        if (!deps.isEmpty()) {
            jsonableClassDeps.put(identity, deps);
        }
    }

    // =======================================================================
    // Jsonable type validation (D3)
    // =======================================================================

    /**
     * Checks whether a type is valid for a @jsonable field (D3).
     * Recursively unwraps Array and Nullable wrappers. A class type is
     * jsonable by its carried canonical identity: the class's module
     * must expose both generated-signature helpers (the declaration-file
     * case declares them explicitly; the ignored declaration-only
     * marker contributes nothing).
     */
    private boolean isJsonableType(Type t) {
        return switch (t) {
            case Type.Null ignored       -> true;
            case Type.Undefined ignored  -> true;
            case Type.Unknown ignored -> false;
            case Type.Void ignored -> false;
            case Type.Boolean ignored    -> true;

            case Type.Number ignored     -> true;
            case Type.String ignored     -> true;
            case Type.Table ignored      -> true;
            case Type.Array arr          -> isJsonableType(arr.element());
            case Type.Nullable n         -> isJsonableType(n.inner());
            case Type.Class cls          -> isJsonableClass(cls);
            default -> false;
        };
    }

    /**
     * True when the class type carries both generated-signature helper
     * exports ({@code C$fromJson} and {@code C$toJson}) — the class arm
     * of {@link #isJsonableType} and the dependency-edge membership of
     * {@link #collectJsonableClassDeps}. The query is keyed by the
     * carried canonical identity (never the bare class name), so a
     * same-named class in another module is never mistaken for this
     * one.
     */
    private boolean isJsonableClass(Type.Class cls) {
        return isFunctionExportedFromClassModule(cls, cls.name() + "$fromJson")
            && isFunctionExportedFromClassModule(cls, cls.name() + "$toJson");
    }

    /**
     * The canonical identity of one walked {@code @jsonable} class
     * declaration: the resolved class symbol's identity (the module
     * identity layer's single allocation), falling back to the
     * resolver module's identity plus the declared name, or
     * {@code null} when neither carries one.
     */
    private CanonicalClassIdentity jsonableIdentityOf(ClassDeclaration cd) {
        Symbol sym = currentScope.resolve(cd.name());
        if (sym instanceof Symbol.ClassSymbol classSymbol) {
            return classSymbol.identity();
        }
        CanonicalModuleIdentity moduleIdentity = nameResolver.moduleIdentity();
        return moduleIdentity == null ? null
            : new CanonicalClassIdentity(moduleIdentity, cd.name());
    }

    /**
     * The C$fromJson/C$toJson export check of {@link #isJsonableType}:
     * same-module classes check the root symbol table, imported classes
     * route through the resolver's identity-keyed export lookup.
     */
    private boolean isFunctionExportedFromClassModule(Type.Class cls,
                                                      String functionName) {
        return nameResolver.isFunctionExportedFromModule(
            cls.identity().moduleIdentity(), functionName);
    }

    /**
     * Resolves the class symbol a {@link Type.Class} names: the exact
     * local declaration when the lexical class-symbol named
     * {@code cls.name()} carries {@code cls}'s canonical identity, then
     * the identity-keyed cross-module routing
     * (descriptor-identity-propagation D1). A same-named declaration of
     * another identity never captures the lookup (no textual class-name
     * search: an imported class stays the imported provider's
     * declaration even when the importing module declares its own class
     * of that spelling).
     */
    private Symbol.ClassSymbol resolveClassSymbol(Type.Class cls) {
        if (cls.providerModule() != null) {
            // A bound carrier names its defining provider: the exact
            // declaration resolves in that module's own scope (the
            // imported alias/export route needs no local AST
            // declaration, and a same-named local declaration of the
            // same canonical identity never captures it). A harness-shared
            // identity gives a name/identity scan no single answer, so it
            // is never consulted for a bound carrier.
            return resolveProviderClassSymbol(cls);
        }
        Symbol.ClassSymbol local = exactLocalClassSymbol(cls);
        if (local != null) {
            return local;
        }
        return nameResolver.resolveClassSymbol(cls);
    }

    /**
     * The exact declaration one bound class carrier names: the class
     * symbol of the carrier's own defining provider module, resolved by
     * its wiring path and declared name (the actual alias/export/provider
     * route, never a local AST declaration or a textual name search).
     * {@code null} for the unbound carrier, a provider that names no
     * declaration, or a declaration whose canonical identity contradicts
     * the carrier — a contradictory provider fact fails closed instead of
     * falling back to a same-identity declaration.
     */
    private Symbol.ClassSymbol resolveProviderClassSymbol(Type.Class cls) {
        String provider = cls.providerModule();
        if (provider == null || provider.isEmpty()) {
            return null;
        }
        Symbol.ClassSymbol sym =
            nameResolver.resolveClassSymbol(cls.name(), provider);
        if (sym == null || cls.identity() == null || sym.identity() == null
                || !cls.identity().equals(sym.identity())) {
            return null;
        }
        return sym;
    }

    /**
     * The current scope's class symbol for {@code cls.name()} exactly
     * when it is the declaration {@code cls}'s canonical identity names,
     * or {@code null}: a local class of the same spelling but a distinct
     * identity is never the resolved declaration of a construction, a
     * field access, or a {@code has} check.
     */
    private Symbol.ClassSymbol exactLocalClassSymbol(Type.Class cls) {
        Symbol sym = currentScope.resolve(cls.name());
        if (sym instanceof Symbol.ClassSymbol cs
                && cs.identity() != null
                && cs.identity().equals(cls.identity())) {
            return cs;
        }
        return null;
    }

    /**
     * The exact declaration a class-typed receiver's own binding
     * annotation names, or {@code null}: a qualified annotation carries
     * the alias's declared class symbol, so a member access or
     * {@code has} check on a sibling-pair class selects the declared
     * provider's declaration rather than an identity-first match.
     */
    private Symbol.ClassSymbol exactReceiverClassSymbol(ExpressionNode receiver,
                                                        Type.Class cls) {
        // The receiver expression's own recorded exact declaration first
        // (a chained receiver's field annotation resolved in the declaring
        // module's scope, a constructed literal, an array element): the
        // expression-level fact names the declaring provider even when the
        // receiver is not a plain identifier.
        Symbol.ClassSymbol recorded = receiver == null
            ? null : exactClassDecls.get(receiver);
        if (recorded != null && cls.identity() != null
                && recorded.identity() != null
                && recorded.identity().equals(cls.identity())) {
            return recorded;
        }
        if (!(receiver instanceof IdentifierExpr id)) {
            return null;
        }
        Symbol sym = resolveVisibleBinding(id.name());
        if (!(sym instanceof Symbol.VariableSymbol variable)) {
            return null;
        }
        Symbol.ClassSymbol exact = exactBindingClassSymbol(variable);
        if (exact != null && cls.identity() != null
                && exact.identity().equals(cls.identity())) {
            return exact;
        }
        return null;
    }

    /**
     * The exact class declaration a class-typed binding's own annotation
     * names, or {@code null}: the recorded contextual declaration when the
     * binding carries one, otherwise the annotation's declared class
     * symbol resolved in the current scope.
     */
    private Symbol.ClassSymbol exactBindingClassSymbol(
            Symbol.VariableSymbol variable) {
        if (variable.declaredClass() != null) {
            return variable.declaredClass();
        }
        return exactAnnotationClassSymbol(currentScope, variable.declaredType());
    }

    /**
     * Records the exact declaring class declaration of one class-typed
     * expression (a class-typed value, an array of one class identity, or
     * a nullable class): the exact declaration must carry the expression's
     * own innermost class identity (a declaration of another identity is
     * never the expression's declared class). The one lowering consumes
     * the record to select the exact declaring provider of a field
     * operation.
     */
    private void recordExactClassDecl(ExpressionNode expr, Type type,
                                      Symbol.ClassSymbol decl) {
        if (expr == null || decl == null) {
            return;
        }
        Type element = innermostElementType(type);
        if (!(element instanceof Type.Class cls)) {
            return;
        }
        if (cls.identity() == null || decl.identity() == null
                || !cls.identity().equals(decl.identity())) {
            return;
        }
        exactClassDecls.put(expr, decl);
    }

    /**
     * Records the exact declaring declaration of a class-typed expression
     * the checker did not already attribute through an annotation: the
     * expression's own checked type is the fact (a call result, an
     * awaited value, a recovered expression), and a bound carrier names
     * its defining provider, so the declared declaration is resolved
     * through that provider — never a same-identity name/identity scan.
     * The unbound carrier (public descriptor synthesis, retained
     * harnesses) records nothing. An already-recorded expression keeps
     * its annotation-derived declaration.
     */
    private void recordInferredClassDecl(ExpressionNode expr, Type type) {
        if (expr == null || exactClassDecls.containsKey(expr)) {
            return;
        }
        Type element = innermostElementType(type);
        if (!(element instanceof Type.Class cls)
                || cls.providerModule() == null) {
            return;
        }
        recordExactClassDecl(expr, type, resolveProviderClassSymbol(cls));
    }

    /**
     * The exact declaration of one class-typed parameter position named
     * by the parameter type itself: a function-typed callee carries no
     * declared parameter annotation, but its bound parameter carrier
     * names the defining provider, so the argument literal's construction
     * context still resolves the exact declaration. {@code null} entries
     * are positions whose type names no bound class declaration.
     */
    private List<Symbol.ClassSymbol> providerClassDeclsOf(List<Type> paramTypes) {
        if (paramTypes == null) {
            return null;
        }
        List<Symbol.ClassSymbol> decls = new ArrayList<>(paramTypes.size());
        for (Type paramType : paramTypes) {
            Type element = paramType == null ? null
                : innermostElementType(paramType);
            decls.add(element instanceof Type.Class cls
                ? resolveProviderClassSymbol(cls) : null);
        }
        return decls;
    }

    /**
     * The exact declaration of a class-typed field annotation resolved in
     * the declaring class's own module scope (a qualified spelling names
     * that module's alias, so the annotation's own declared symbol — never
     * an identity-keyed lookup — selects the declaration), or {@code null}
     * when the field's innermost element type is not a class or no exact
     * declaration resolves.
     */
    private Symbol.ClassSymbol exactFieldClassSymbol(Symbol.ClassSymbol owner,
                                                     TypeNode fieldTypeNode,
                                                     Type resolvedFieldType) {
        Type element = innermostElementType(resolvedFieldType);
        if (!(element instanceof Type.Class cls)) {
            return null;
        }
        Symbol.ClassSymbol decl = nameResolver
            .resolveTypeNodeClassSymbolInModule(fieldTypeNode, owner.modulePath());
        if (decl == null || cls.identity() == null
                || !cls.identity().equals(decl.identity())) {
            return null;
        }
        return decl;
    }

    /**
     * The innermost element type of an array/nullable-nested type (the
     * class-typed position of an array or nullable of one class identity).
     */
    private static Type innermostElementType(Type type) {
        Type current = type;
        while (current != null) {
            if (current instanceof Type.Array array) {
                current = array.element();
                continue;
            }
            if (current instanceof Type.Nullable nullable) {
                current = nullable.inner();
                continue;
            }
            return current;
        }
        return null;
    }

    /**
     * The exact declaration one type annotation names in the given scope,
     * or {@code null} for primitive/function/foreign-surfaceless
     * annotations: a qualified spelling resolves the alias's own declared
     * class symbol (the alias — never an identity-first match — selects the
     * declaration; only a harness-shared identity needs that exactness), a
     * bare name its local symbol.
     */
    private Symbol.ClassSymbol exactAnnotationClassSymbol(SymbolTable scope,
                                                          TypeNode tn) {
        if (scope == null || tn == null) {
            return null;
        }
        return switch (tn) {
            case NamedType nt -> {
                Symbol sym = scope.resolve(nt.name());
                yield sym instanceof Symbol.ClassSymbol cs ? cs : null;
            }
            case QualifiedType qt -> {
                Symbol sym = scope.resolve(qt.moduleName());
                yield sym instanceof Symbol.ModuleSymbol ms
                    ? ms.classSymbols().get(qt.typeName()) : null;
            }
            case NullableType nt -> exactAnnotationClassSymbol(scope, nt.innerType());
            case ArrayType at -> exactAnnotationClassSymbol(scope, at.elementType());
            default -> null;
        };
    }

    /**
     * Recursively extracts the jsonable class identities a resolved
     * field type references, unwrapping Array and Nullable wrappers
     * (D3 cycle 7).
     *
     * <p>Membership is the identity-keyed helper visibility of
     * {@link #isJsonableClass}: local and imported generated classes
     * contribute their canonical identity, so two same-named classes
     * stay distinct nodes. A declaration-only class generates no
     * helpers (its source marker is ignored) and contributes no edge;
     * its explicitly declared helper schema is still accepted by
     * {@link #isJsonableType}.</p>
     */
    private void collectJsonableClassDeps(Type t,
                                          Set<CanonicalClassIdentity> deps) {
        switch (t) {
            case Type.Class cls -> {
                if (isJsonableClass(cls)) {
                    deps.add(cls.identity());
                }
            }
            case Type.Array arr ->
                collectJsonableClassDeps(arr.element(), deps);
            case Type.Nullable n ->
                collectJsonableClassDeps(n.inner(), deps);
            default -> { /* stop: no deeper class references possible */ }
        }
    }

    // =======================================================================
    // Jsonable cycle detection (D3)
    // =======================================================================

    /**
     * DFS-based cycle detection on the @jsonable class dependency graph,
     * keyed throughout by canonical class identity. Emits E4008 for
     * each cycle found.
     *
     * <p>Called after all statements have been walked, so
     * {@link #jsonableClassDeps} is fully populated. A dependency on an
     * imported generated class is a graph node without outgoing edges
     * in this module's walk: a cross-module class cycle is a module
     * import cycle, rejected with E2005 before checking, so no
     * cross-module generated cycle can reach this detector.</p>
     */
    private void detectJsonableCycles() {
        if (jsonableClassDeps.isEmpty()) return;

        // Colors: 0 = white (unvisited), 1 = gray (in current path), 2 = black (done)
        Map<CanonicalClassIdentity, Integer> color = new HashMap<>();
        Map<CanonicalClassIdentity, CanonicalClassIdentity> parent = new HashMap<>();
        for (CanonicalClassIdentity identity : jsonableClassDeps.keySet()) {
            color.put(identity, 0);
        }

        for (CanonicalClassIdentity start : jsonableClassDeps.keySet()) {
            if (color.get(start) == 0) {
                dfsDetectCycle(start, color, parent);
            }
        }
    }

    private void dfsDetectCycle(CanonicalClassIdentity node,
                                Map<CanonicalClassIdentity, Integer> color,
                                Map<CanonicalClassIdentity,
                                    CanonicalClassIdentity> parent) {
        color.put(node, 1); // gray — in current path

        Set<CanonicalClassIdentity> deps = jsonableClassDeps.getOrDefault(
            node, Set.of());
        for (CanonicalClassIdentity neighbor : deps) {
            Integer neighborColor = color.get(neighbor);
            if (neighborColor == null) {
                // Neighbor is not a generated jsonable class (should not
                // happen, but safe)
                continue;
            }
            if (neighborColor == 1) {
                // Cycle detected: node → neighbor (back edge).
                // Walk the parent chain from node back to neighbor to
                // reconstruct the full cycle path.
                List<String> cyclePath = new ArrayList<>();
                cyclePath.add(neighbor.className());
                CanonicalClassIdentity cur = node;
                while (cur != null && !cur.equals(neighbor)) {
                    cyclePath.add(cur.className());
                    cur = parent.get(cur);
                }
                cyclePath.add(neighbor.className()); // close the cycle

                // Build the cycle path string
                StringBuilder pathStr = new StringBuilder();
                for (int i = 0; i < cyclePath.size(); i++) {
                    if (i > 0) pathStr.append(" → ");
                    pathStr.append(cyclePath.get(i));
                }

                // Use the class declaration span for the node that closes
                // the cycle. When no span is recorded (defensive), the D5
                // explicit synthetic factory names the cycle-node class —
                // never a Span.synthetic passthrough to the span factory.
                Span span = jsonableClassSpans.get(node);
                if (span != null) {
                    error(DiagnosticCode.E4008,
                        "Circular @jsonable class dependency: " + pathStr,
                        span);
                } else {
                    diagnostics.add(CompilerDiagnostic.syntheticError(
                        DiagnosticCode.E4008,
                        "Circular @jsonable class dependency: " + pathStr,
                        modulePath,
                        "missing anchor: class declaration span for cycle node '"
                            + node.className() + "'"));
                }
            } else if (neighborColor == 0) {
                parent.put(neighbor, node);
                dfsDetectCycle(neighbor, color, parent);
            }
            // black (2): already fully processed, no cycle through this neighbor
        }

        color.put(node, 2); // black — done
    }

    // =======================================================================
    // Variable declaration
    // =======================================================================

    private void checkVariableDeclaration(VariableDeclaration vd) {
        Type targetType;
        if (vd.typeAnnotation().isPresent()) {
            targetType = nameResolver.resolveTypeNode(vd.typeAnnotation().get());
        } else {
            targetType = null;
        }

        Type savedExpected = expectedType;
        Symbol.ClassSymbol savedContextualClass = contextualClassDecl;
        expectedType = targetType;
        // An annotated declaration supplies its own exact class
        // declaration as the construction context: an initializer object
        // literal is laid out by the declaration the annotation names, so
        // the value's provenance is that declaration even when a
        // same-named local binding would resolve under the literal's own
        // lexical scope.
        contextualClassDecl = null;
        if (vd.typeAnnotation().isPresent()) {
            Symbol declSym = currentScope.resolveLocal(vd.name());
            if (declSym instanceof Symbol.VariableSymbol vs) {
                contextualClassDecl = vs.declaredClass() != null
                    ? vs.declaredClass()
                    : exactAnnotationClassSymbol(currentScope, vd.typeAnnotation().get());
            }
        }
        Type exprType = checkContextualReadPosition(vd.initializer());
        if (exprType instanceof Type.Void) {
            error(DiagnosticCode.E3001, "Void results cannot initialize bindings", vd.initializer().span());
            exprType = Type.Error.INSTANCE;
        }
        expectedType = savedExpected;
        contextualClassDecl = savedContextualClass;

        // The initializer's class provenance is resolved while the
        // declaration is still pending, so a self reference names the
        // enclosing binding exactly like the type check above.
        Symbol.ClassSymbol initDeclaredClass = declaredClassOf(vd.initializer());

        // The declaration is complete: the name is now visible to the
        // following statements of its block. Only the declaration's own
        // scope stops treating it as pending — an inner block's
        // same-name declaration never makes an enclosing scope's pending
        // declaration visible early (docs/spec-v1.5.md §Name resolution —
        // Declaration order: a let declaration is visible only after its declaration
        // and only in its own block).
        for (PendingScope ps : pendingScopes) {
            if (ps.scope == currentScope) {
                ps.remaining.remove(vd.name());
                break;
            }
        }

        // A block-local declaration shadows any outer binding of the same
        // name: the outer binding's narrowing must not apply to the new
        // symbol. The initializer above still resolved against the
        // enclosing binding with its current refinement (the new
        // declaration is not yet visible there), so the entry is cleared
        // only now. It is remembered so the declaring block's exit
        // restores it for the enclosing scope.
        Type outerNarrowed = narrowing.getNarrowedType(vd.name());
        if (outerNarrowed != null) {
            narrowing.onAssignment(vd.name());
            narrowing.recordShadowed(vd.name(), outerNarrowed);
        }

        // A rejected initializer (an error-recovery type) or an
        // un-inferable declaration skips the assignment gate — but the
        // declaration still happened: the name becomes visible to the
        // following statements of its block and the assignment
        // bookkeeping runs, so a later reference resolves to this
        // declaration instead of escaping the pending scope or an
        // enclosing binding (ISSUE-0717: the recoverable E3013 async
        // intrinsic site keeps its loadable type facts).
        if (exprType != Type.Error.INSTANCE) {
            if (targetType == null) {
                targetType = inferTypeFromExpression(vd.initializer(), exprType);
                if (targetType != null) {
                    // Update the symbol table with the inferred type. An
                    // inferred class-typed binding keeps the initializer's
                    // exact declaration provenance (never a dropped
                    // provenance): the value carried into the new binding
                    // was laid out by that declaration, so a later
                    // boundary crossing to a distinct same-named
                    // declaration must still be E3019
                    // (docs/spec-v1.5.md §Nominal typing and
                    // §Equality and comparison operand rules: class types
                    // are equal by declared class symbol only).
                    Symbol sym = currentScope.resolve(vd.name());
                    if (sym instanceof Symbol.VariableSymbol vs && vs.type() == null) {
                        Symbol.ClassSymbol provenance = vs.declaredClass() != null
                            ? vs.declaredClass() : initDeclaredClass;
                        currentScope.defineReplace(vd.name(),
                            new Symbol.VariableSymbol(vd.name(), targetType, false,
                                provenance, vs.declaredType()));
                    }
                }
            }
            if (targetType != null) {
                checkAssignment(targetType, exprType, vd.initializer().span());
                // Initialization assigns a value to the new binding: a
                // value that names a different declaration of the same
                // canonical class identity crosses the two runtime
                // layouts here exactly like an assignment expression.
                Symbol targetSym = currentScope.resolveLocal(vd.name());
                Symbol.ClassSymbol targetDecl =
                    targetSym instanceof Symbol.VariableSymbol vs
                        ? vs.declaredClass() : null;
                checkClassProvenance(targetDecl, targetType,
                    initDeclaredClass, exprType, vd.initializer().span());
            }
        }

        recordDeclaration(vd.name());
    }

    /**
     * Declaration bookkeeping: a new binding neither inherits a
     * same-named binding's narrowing (the shadowing branch above clears
     * the entry) nor assigns an enclosing binding, so it is never
     * recorded as a loop-body or try-block assignment — the revert of an
     * open loop or try statement is keyed by the resolved binding, and a
     * declaration is a different binding.
     */
    private void recordDeclaration(String varName) {
        narrowing.onAssignment(varName);
    }

    /**
     * Assignment bookkeeping shared by assignment expressions: the
     * narrowed entry is cleared immediately, and the resolved binding is
     * recorded for the enclosing loop (a loop back-edge invalidates the
     * entry for the code after the loop) and for the open try blocks of
     * the current function (after a try/catch every binding assigned
     * inside the try region reverts to its declared type). The binding is
     * identified by its resolved symbol, never by name: a loop-local or
     * try-local declaration's same-named binding is not an assignment to
     * the enclosing binding.
     */
    private void recordAssignment(String varName) {
        narrowing.onAssignment(varName);
        if (loopAssignments.isEmpty() && tryAssignments.isEmpty()) {
            return;
        }
        Symbol binding = resolveVisibleBinding(varName);
        if (binding == null) {
            return;
        }
        if (!loopAssignments.isEmpty()) {
            loopAssignments.peek().add(binding);
        }
        if (!tryAssignments.isEmpty()) {
            tryAssignments.peek().add(binding);
        }
    }

    // =======================================================================
    // Function declaration
    // =======================================================================

    private void checkFunctionDeclaration(FunctionDeclaration fd) {
        for (PendingScope ps : pendingScopes) {
            if (ps.scope == currentScope.parent()) { ps.remaining.remove(fd.name()); break; }
        }
        // Resolve the declaration in its exact enclosing scope, excluding
        // the parameter scope: a legal parameter may shadow its function name.
        Type.Func funcType = null;
        Symbol sym = currentScope.parent().resolveLocal(fd.name());
        if (sym instanceof Symbol.FunctionSymbol fs) {
            funcType = fs.funcType();
        }

        Type savedReturnType = currentReturnType;
        Symbol.ClassSymbol savedReturnContext = currentReturnContext;
        boolean savedInsideAsync = insideAsyncFunction;
        if (funcType != null) {
            currentReturnType = funcType.returnType();
            // The return annotation's own declared class symbol, resolved
            // in the scope the function declaration lives in: a returned
            // object literal is constructed by that declaration, never by
            // a same-named local declaration the lexical scope resolves
            // instead (a sibling provider's declaration has its own
            // identity, so the annotation, not a name scan, names it).
            currentReturnContext = exactAnnotationClassSymbol(currentScope,
                fd.returnType());
            insideAsyncFunction = funcType.isAsync();
        }

        // F1: Use walkStatement(fd.body()) instead of walkStatements(fd.body().statements())
        // so that the Block's scope (containing let-declared variables) is entered.
        //
        // Narrowed flow types are not captured and do not cross function
        // boundaries (v1.2 narrowing invalidation); break/continue bind
        // only to loops of the same function body, and the loop and
        // try-region assignment bookkeeping is isolated with the narrowing
        // state (an assignment inside the nested body never invalidates an
        // enclosing loop's or try region's bindings: declaring a function
        // does not execute its body).
        NullNarrowing savedNarrowing = narrowing;
        narrowing = new NullNarrowing();
        Deque<Set<Symbol>> savedLoopAssignments = loopAssignments;
        loopAssignments = new ArrayDeque<>();
        Deque<Set<Symbol>> savedTryAssignments = tryAssignments;
        tryAssignments = new ArrayDeque<>();
        Deque<NullNarrowing> savedLoopSavedNarrowing = loopSavedNarrowing;
        loopSavedNarrowing = new ArrayDeque<>();
        int savedLoopDepth = loopDepth;
        loopDepth = 0;
        walkStatement(fd.body());
        narrowing = savedNarrowing;
        loopAssignments = savedLoopAssignments;
        tryAssignments = savedTryAssignments;
        loopSavedNarrowing = savedLoopSavedNarrowing;
        loopDepth = savedLoopDepth;

        if (funcType != null && !fd.isExternal()) {
            // One definite-return rule serves every compile surface: only
            // void admits normal completion; null is an explicit data result.
            // Analyze reachable statements, including unconditional-return loops.
            Type retType = funcType.returnType();
            // The try/catch-local obligation first: a try block that
            // definitely returns via return statements requires the catch
            // block to definitely return or throw, independently of the
            // enclosing body's termination and of its return type
            // (docs/spec-v1.5.md §try / catch). The deficient catch block
            // carries the diagnostic even when a later statement returns;
            // when the composite rule fires, the whole-body falloff
            // diagnostic is redundant and stays suppressed.
            List<TryStatement> fallthroughs =
                ReturnAnalysis.tryCatchFallthroughs(fd.body());
            for (TryStatement ts : fallthroughs) {
                error(DiagnosticCode.E5002,
                    "Function '" + fd.name() + "' must return on all paths:"
                        + " the catch block of a definitely-returning try"
                        + " must also definitely return or throw",
                    ts.catchBlock().span());
            }
            if (fallthroughs.isEmpty()
                    && !(retType instanceof Type.Void)
                    && !ReturnAnalysis.definitelyReturns(fd.body())) {
                error(DiagnosticCode.E5002,
                    "Function '" + fd.name() + "' must return a value on all paths",
                    fd.span());
            }
        }

        currentReturnType = savedReturnType;
        currentReturnContext = savedReturnContext;
        insideAsyncFunction = savedInsideAsync;
    }

    // =======================================================================
    // Return statement
    // =======================================================================

    private void checkReturnStatement(ReturnStatement rs) {
        if (rs.expr().isPresent()) {
            if (currentReturnType == null) {
                checkExpression(rs.expr().get());
                return;
            }
            Type savedExpected = expectedType;
            Symbol.ClassSymbol savedContextual = contextualClassDecl;
            expectedType = currentReturnType;
            // The declared return annotation is the returned literal's
            // construction context: a `return {}` of an imported class
            // return type is laid out by that provider's declaration,
            // never by a same-named local declaration of the same
            // canonical identity.
            if (currentReturnContext != null
                    && contextualClassDecl == null) {
                contextualClassDecl = currentReturnContext;
            }
            Type exprType = checkContextualReadPosition(rs.expr().get());
            expectedType = savedExpected;
            contextualClassDecl = savedContextual;

            if (exprType == Type.Error.INSTANCE) return;

            if (!isAssignable(currentReturnType, exprType)) {
                if (!reportClassProviderCrossing(currentReturnType, exprType,
                        rs.expr().get().span())) {
                    error(DiagnosticCode.E5003,
                        "Return type mismatch: expected " + typeName(currentReturnType)
                        + ", got " + typeName(exprType),
                        rs.expr().get().span());
                }
            }
        } else {
            if (currentReturnType != null && !(currentReturnType instanceof Type.Void)) {
                error(DiagnosticCode.E5003,
                    "Return type mismatch: expected " + typeName(currentReturnType)
                    + ", got undefined",
                    rs.span());
            }
        }
    }

    // =======================================================================
    // If statement
    // =======================================================================

    private void checkIfStatement(IfStatement is) {
        // F6: Set expectedType = boolean for if conditions. The fifth
        // specification position (`if (E)`) is an admitted direct read;
        // the loop conditions are not.
        Type savedExpected = expectedType;
        expectedType = Type.Boolean.INSTANCE;
        Type condType = checkContextualReadPosition(is.condition());
        expectedType = savedExpected;

        if (condType != Type.Error.INSTANCE && !(condType instanceof Type.Boolean)) {
            error(DiagnosticCode.E3007, "If condition must be boolean, got " + typeName(condType),
                is.condition().span());
        }

        NullNarrowing savedNarrowing = narrowing;
        NullNarrowing thenNarrowing = narrowing.branch();
        NullNarrowing elseNarrowing = narrowing.branch();

        // Apply narrowing for then branch
        narrowing = thenNarrowing;
        narrowCondition(is.condition(), true);
        walkStatement(is.thenBlock());
        thenNarrowing = narrowing;

        // Apply narrowing for else branch
        narrowing = elseNarrowing;
        narrowCondition(is.condition(), false);
        if (is.elseBranch().isPresent()) {
            switch (is.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left ->
                    checkIfStatement(left.value());
                case Either.Right<IfStatement, Block> right ->
                    walkStatement(right.value());
            }
        }
        elseNarrowing = narrowing;

        if (is.elseBranch().isEmpty()) {
            // No else branch: the then arm's condition-derived positive
            // refinements stay inside its block and are not adopted for
            // the statements after the if (the spec's narrowing is
            // block-scoped: `!== null` narrows in its block, `=== null`
            // in the else block, and there is no post-if persistence).
            // An invalidation inside the then arm (an assignment clearing
            // an entry or an await's invalidateAll) must still survive the
            // join: the incoming entries are retained only for the names
            // neither path invalidated, and a snapshot only when the
            // then path still holds it.
            savedNarrowing.merge(thenNarrowing);
            narrowing = savedNarrowing;
        } else {
            thenNarrowing.merge(elseNarrowing);
            narrowing = thenNarrowing;
        }
    }

    /** Apply nominal tests with the same branch polarity as nullish/typeof tests. */
    private void narrowCondition(ExpressionNode condition, boolean matching) {
        narrowing.onIfCondition(condition, matching, this::declaredTypeOf);
        while (condition instanceof UnaryExpr unary && unary.op() == UnaryOp.NOT) {
            condition = unary.expr();
            matching = !matching;
        }
        if (matching && condition instanceof InstanceofExpr test
                && test.value() instanceof IdentifierExpr id) {
            Type.Class target = instanceofTargets.get(test);
            if (target != null) narrowing.narrow(id.name(), target);
        }
    }

    // =======================================================================
    // While statement
    // =======================================================================

    private void checkWhileStatement(WhileStatement ws) {
        // Conditions require boolean. Record reads remain unknown here,
        // just as in all other value positions; use explicit refinement or
        // a checked assertion before consuming one as a condition.
        Type savedExpected = expectedType;
        expectedType = null;
        Type condType = checkExpression(ws.condition());
        expectedType = savedExpected;

        if (condType != Type.Error.INSTANCE && !(condType instanceof Type.Boolean)) {
            error(DiagnosticCode.E3007, "While condition must be boolean, got " + typeName(condType),
                ws.condition().span());
        }

        NullNarrowing savedNarrowing = narrowing;
        loopSavedNarrowing.push(savedNarrowing);
        narrowing = new NullNarrowing();
        narrowCondition(ws.condition(), true);
        loopDepth++;
        loopAssignments.push(newBindingAssignmentSet());
        walkStatement(ws.body());
        // The loop back-edge invalidates every binding assigned in the
        // body (v1.2 narrowing does not cross loop back-edges).
        Set<Symbol> assigned = loopAssignments.pop();
        loopDepth--;
        loopSavedNarrowing.pop();
        closeLoopAssignments(assigned, savedNarrowing);
        narrowing = savedNarrowing;
    }

    // =======================================================================
    // For statement
    // =======================================================================


    /**
     * The fresh binding-identity assignment set of one loop body or one
     * try region (symbols compare by identity, never by value).
     */
    private static Set<Symbol> newBindingAssignmentSet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /**
     * Closes one loop's assignment frame: the recorded bindings
     * propagate to an enclosing loop's frame, and each binding that is
     * still visible after the loop loses its narrowed entry (a loop-local
     * declaration's same-named binding is a distinct binding and never
     * invalidates the enclosing one).
     */
    private void closeLoopAssignments(Set<Symbol> assigned, NullNarrowing saved) {
        if (!loopAssignments.isEmpty()) {
            loopAssignments.peek().addAll(assigned);
        }
        for (Symbol binding : assigned) {
            if (resolveVisibleBinding(binding.name()) == binding) {
                saved.invalidate(binding.name());
            }
        }
    }

    // =======================================================================
    // For-of statement (D8)
    // =======================================================================

    private void checkForOfStatement(ForOfStatement fos) {
        // In parent scope (no scopeMap entry for ForOfStatement)
        Type iterableType = checkExpression(fos.iterable());
        Type varType = iterableType instanceof Type.Array a ? a.element()
            : iterableType instanceof Type.String ? Type.String.INSTANCE : Type.Error.INSTANCE;
        SymbolTable bodyScope = scopeMap.get(fos.body());
        if (bodyScope != null && bodyScope.parent() != null) {
            bodyScope.parent().defineReplace(fos.varName(),
                new Symbol.VariableSymbol(fos.varName(), varType, false));
        }

        if (iterableType instanceof Type.Array arr) {
            if (!Types.equals(arr.element(), varType)) {
                error(DiagnosticCode.E3015,
                    "For-of loop variable type " + typeName(varType)
                    + " does not match array element type " + typeName(arr.element()),
                    fos.span());
            }
        } else if (iterableType instanceof Type.String) {
            // DEAL v1.2: strings are Unicode scalar-value sequences; for-of
            // yields one `string` containing exactly one Unicode scalar
            // value per iteration, so the loop variable must be `string`.
            if (!(varType instanceof Type.String)) {
                error(DiagnosticCode.E3015,
                    "For-of over string requires loop variable type string, got "
                    + typeName(varType), fos.span());
            }
        } else {
            error(DiagnosticCode.E3015,
                "For-of iterable must be an array or string, got "
                + typeName(iterableType), fos.iterable().span());
        }

        NullNarrowing savedNarrowing = narrowing;
        loopSavedNarrowing.push(savedNarrowing);
        narrowing = new NullNarrowing();
        loopDepth++;
        loopAssignments.push(newBindingAssignmentSet());
        walkStatement(fos.body()); // Enters Block scope via scopeMap
        Set<Symbol> assigned = loopAssignments.pop();
        loopDepth--;
        loopSavedNarrowing.pop();
        // The for-of back-edge invalidates every binding assigned in the
        // body exactly like while (call-preserved narrowing does
        // not cross loop back-edges): the recorded bindings propagate to
        // an enclosing loop and the still-visible ones lose their entry.
        closeLoopAssignments(assigned, savedNarrowing);
        narrowing = savedNarrowing;
    }

    // =======================================================================
    // Break / Continue (F8)
    // =======================================================================

    private void checkBreak(BreakStatement bs) {
        if (loopDepth == 0) {
            error(DiagnosticCode.E2000, "'break' must be inside a loop", bs.span());
        }
    }

    private void checkContinue(ContinueStatement cs) {
        if (loopDepth == 0) {
            error(DiagnosticCode.E2000, "'continue' must be inside a loop", cs.span());
        }
    }

    // =======================================================================
    // Try statement
    // =======================================================================

    private void checkTryStatement(TryStatement ts) {
        // The try statement's own catch scope is current here
        // (walkStatement enters scopeMap's entry before dispatch), so the
        // code after the catch block lives in that scope's parent. The
        // revert below tests visibility there, never in the catch scope.
        SymbolTable catchScope = scopeMap.get(ts);
        SymbolTable afterCatchScope = catchScope != null && catchScope.parent() != null
            ? catchScope.parent() : currentScope;

        // The try block's own assignment frame. It is closed before the
        // catch block is walked: an assignment in this try statement's own
        // catch block is its own flow (a direct assignment already cleared
        // the entry), while a try nested in an enclosing try region leaves
        // that enclosing frame open and collects the assignment there.
        Set<Symbol> assigned = newBindingAssignmentSet();
        tryAssignments.push(assigned);
        walkStatement(ts.tryBlock());
        tryAssignments.pop();

        // The catch identifier declares a fresh `Error` binding in the catch
        // scope (docs/spec-v1.3.md §try / catch: the bound variable has
        // type Error), so a same-named enclosing binding's refinement is
        // another binding's flow fact and never types the catch read: an
        // `int`/`null` refinement must not reject a valid Error read, and a
        // `string` refinement must not admit an unsound transfer. The
        // enclosing entry is saved in the catch scope's shadow frame exactly
        // like a block-local declaration's entry, so the catch scope's exit
        // restores it — unless the language's own assignment rule removed
        // it (the entry is saved after the try block, so a try-block
        // assignment to that binding saves nothing) or an await's
        // invalidation cleared the frame.
        narrowing.pushShadowFrame();
        Type outerRefinement = narrowing.getNarrowedType(ts.catchVar());
        if (outerRefinement != null) {
            narrowing.onAssignment(ts.catchVar());
            narrowing.recordShadowed(ts.catchVar(), outerRefinement);
        }

        walkStatement(ts.catchBlock());

        narrowing.popShadowFrame();

        closeTryAssignments(assigned, afterCatchScope);
    }

    /**
     * Closes one try statement's assignment frame
     * (docs/spec-v1.3.md §Narrowing across try/catch: after a try/catch
     * every variable assigned inside the try block reverts to its declared
     * type). The recorded bindings propagate to an enclosing try statement's
     * frame — a re-narrowing later inside that try block must not outlive
     * the enclosing catch — and each binding still visible after the
     * try/catch loses its narrowed entry.
     *
     * <p>Visibility is tested in {@code afterCatchScope}, the scope that
     * receives the code after the catch block, so a binding whose scope
     * ends inside the try statement is never mistaken for the same-named
     * enclosing binding the try did not assign: a try-block declaration
     * and, decisively, the try's own catch binding (a fresh Error binding
     * of the catch scope) are dead there, so reassigning the catch binding
     * inside this try's catch block never drops the enclosing binding's
     * refinement. Resolving in the catch scope would resolve that catch
     * binding to itself and invalidate the entry the catch scope just
     * restored.</p>
     */
    private void closeTryAssignments(Set<Symbol> assigned,
            SymbolTable afterCatchScope) {
        if (!tryAssignments.isEmpty()) {
            tryAssignments.peek().addAll(assigned);
        }
        for (Symbol binding : assigned) {
            if (resolveVisibleBinding(afterCatchScope, binding.name()) == binding) {
                narrowing.invalidate(binding.name());
            }
        }
    }

    // =======================================================================
    // Throw statement
    // =======================================================================

    private void checkThrowStatement(ThrowStatement ts) {
        Type savedExpected = expectedType;
        Type errorType = Types.classType("Error",
            NameResolver.intrinsicErrorIdentity());
        expectedType = errorType;
        Type exprType = checkExpression(ts.expr());
        expectedType = savedExpected;

        if (exprType != Type.Error.INSTANCE && !isAssignable(errorType, exprType)) {
            error(DiagnosticCode.E3001,
                "throw expression must have type Error, got " + typeName(exprType),
                ts.expr().span());
        }
    }

    // =======================================================================
    // Delete statement (F10: handle table delete without false errors)
    // =======================================================================

    private void checkDeleteStatement(DeleteStatement ds) {
        // F10: Use write-context mode so table member/index access
        // doesn't require contextual typing
        boolean savedMode = assignmentTargetMode;
        assignmentTargetMode = true;
        Type targetType = checkExpression(ds.target());
        if (ds.target() instanceof IndexExpr index && typeOf(index.array()) instanceof Type.Array) {
            error(DiagnosticCode.E3017, "Array elements cannot be deleted", ds.span());
        }
        assignmentTargetMode = savedMode;

        if (targetType instanceof Type.Error) return;

        if (ds.target() instanceof MemberAccessExpr mae) {
            Type objType = typeOf(mae.object());
            // D3 (typed-boundary-enforcement): array .length is read-only.
            // Defensive rejection — codegen would otherwise emit invalid
            // `#xs = nil` Lua for a program the checker had accepted.
            if (objType instanceof Type.Array && mae.field().equals("length")) {
                error(DiagnosticCode.E3017, "Array length is read-only",
                    ds.target().span());
                return;
            }
            if (objType instanceof Type.Class cls) {
                // The exact declaring declaration of the delete target's
                // receiver (the receiver's own checked declaration first,
                // then the receiver carrier's defining provider): a
                // same-named local declaration of another identity never
                // owns the required/optional decision of an imported
                // class's field (never a textual class-name search).
                Symbol.ClassSymbol sym =
                    exactReceiverClassSymbol(mae.object(), cls);
                if (sym == null) {
                    sym = resolveClassSymbol(cls);
                }
                if (sym != null) {
                    ClassField field = IntrinsicResolvers.findField(sym.fields(), mae.field());
                    if (field != null && !field.optional()) {
                        error(DiagnosticCode.E4004,
                            "Cannot delete required field '" + mae.field() + "'",
                            ds.span());
                    }
                }
            }
            // Table delete: any field is allowed (no further check needed)
        }
        // A-D10 (assignment-delete-address-chains): table index delete
        // keys must have static type string — the write-context
        // checkIndex gate enforces E3018 for IndexExpr delete targets
        // before lowering; no further check is needed here.
    }

    // =======================================================================
    // Expression checking
    // =======================================================================

    private Type checkExpression(ExpressionNode expr) {
        // Only a read position's own outermost expression is direct: the
        // marker is consumed here, so every expression this check descends
        // into — an array literal element, a class-literal field value, a
        // template interpolation part, a composite operand, an index key —
        // is checked as a nested expression with no direct-read permission
        // (docs/spec-v1.3.md §Reading table fields, whose allowed contexts
        // name the position's whole expression E, never a subexpression).
        boolean direct = pendingContextualReadPosition;
        pendingContextualReadPosition = false;
        boolean savedDirect = contextualReadPosition;
        contextualReadPosition = direct;
        try {
            Type t = computeExpressionType(expr);
            typeMap.put(expr, t);
            // A class-typed expression the enclosing annotations did not
            // already attribute records the exact declaration its own bound
            // carrier names (a call result, an awaited value, an array
            // element read): the one lowering and the field-operation checks
            // then select the declaring provider without a name/identity scan.
            recordInferredClassDecl(expr, t);
            return t;
        } finally {
            contextualReadPosition = savedDirect;
        }
    }

    /**
     * Checks the outermost expression of one admitted contextual-read
     * position (docs/spec-v1.3.md §Reading table fields): the position's
     * own expression E may be a direct table read, and no subexpression
     * it descends into may be.
     */
    private Type checkContextualReadPosition(ExpressionNode expr) {
        boolean savedPending = pendingContextualReadPosition;
        pendingContextualReadPosition = true;
        try {
            return checkExpression(expr);
        } finally {
            pendingContextualReadPosition = savedPending;
        }
    }

    Type typeOf(ExpressionNode expr) {
        Type t = typeMap.get(expr);
        if (t == null) {
            t = checkExpression(expr);
        }
        return t;
    }

    private Type computeExpressionType(ExpressionNode expr) {
        return switch (expr) {
            case LiteralExpr lit       -> checkLiteral(lit);
            case IdentifierExpr id     -> checkIdentifier(id);
            case AssertionExpr assertion -> checkAssertion(assertion);
            case InstanceofExpr test -> checkInstanceof(test);
            case BinaryExpr bin        -> checkBinary(bin);
            case UnaryExpr un          -> checkUnary(un);
            case CallExpr call         -> checkCall(call);
            case MemberAccessExpr mae  -> checkMemberAccess(mae);
            case IndexExpr idx         -> checkIndex(idx);
            case ArrayLiteralExpr arr  -> checkArrayLiteral(arr);
            case ObjectLiteralExpr obj -> checkObjectLiteral(obj);
            case FunctionExpr fe       -> checkFunctionExpr(fe);
            case HasExpr has           -> checkHas(has);
            case AssignmentExpr assign -> checkAssignmentExpr(assign);
            case AwaitExpression await -> checkAwaitExpression(await);
            case TemplateLiteralExpr tl -> {
                for (int i = 0; i < tl.parts().size(); i++) {
                    ExpressionNode part = tl.parts().get(i);
                    Type partType = checkExpression(part);
                    // The interpolation's string requirement is the whole
                    // context of an embedded expression (docs/spec-v1.3.md
                    // §Template literals): it grants no contextual-read
                    // position, and an already-rejected part is not
                    // re-reported against it.
                    if (i % 2 == 1 && partType != Type.Error.INSTANCE
                            && !(partType instanceof Type.String)) {
                        error(DiagnosticCode.E3016,
                            "Template literal interpolation must have type string, got "
                            + typeName(partType), part.span());
                    }
                }
                yield Type.String.INSTANCE;
            }
        };
    }

    // =======================================================================
    // Await expression (async/await type checking)
    // =======================================================================

    private Type checkAwaitExpression(AwaitExpression await) {
        // E3012: await is only allowed inside an async function
        if (!insideAsyncFunction) {
            error(DiagnosticCode.E3012,
                "'await' is only allowed inside an async function",
                await.span());
        }

        // Suppress E3014 for the direct callee of await: the directAwaitCallee
        // reference is set before checkExpression and checked in checkCall().
        // Save the current direct await callee for nested await support.
        CallExpr savedDirectAwaitCallee = directAwaitCallee;

        // Set the direct callee of this await as the exempt CallExpr.
        // Safe cast: parser enforces E1042; instanceof guard below handles malformed input.
        if (await.callee() instanceof CallExpr call) {
            directAwaitCallee = call;
        }

        Type resultType = checkExpression(await.callee());
        directAwaitCallee = savedDirectAwaitCallee;

        // After checkExpression runs, the callee function's type is cached
        // in the type map. Retrieve it to verify async-ness.
        // Guard against non-CallExpr callees: the parser emits E1042 but still
        // constructs an AwaitExpression with the invalid callee during error
        // recovery (e.g., `await 42`). A bare cast would throw ClassCastException.
        if (!(await.callee() instanceof CallExpr call)) {
            // Parser already reported E1042; avoid cascading errors.
            invalidateAllNarrowing();
            return Type.Error.INSTANCE;
        }

        Type calleeFuncType = typeOf(call.callee());

        if (calleeFuncType instanceof Type.Func f && f.isAsync()) {
            // Valid await: resultType is the return type R of the async function.
            // Invalidate narrowing after await.
            invalidateAllNarrowing();
            return resultType;
        }

        if (calleeFuncType != Type.Error.INSTANCE) {
            error(DiagnosticCode.E3013,
                "'await' must be applied to an async function call",
                await.callee().span());
        }
        invalidateAllNarrowing();
        return Type.Error.INSTANCE;
    }

    /**
     * Invalidates every flow-sensitive refinement of the enclosing async
     * function: the active narrowing together with the saved pre-loop
     * states of this function's open loops. A loop body is checked under
     * a fresh narrowing state, so invalidating only the active state
     * would let the loop exit restore an entry (and a saved shadow
     * snapshot) the suspension cleared. Nested function bodies swap the
     * stack at their boundary, so their suspensions stay scoped to
     * themselves.
     */
    private void invalidateAllNarrowing() {
        narrowing.invalidateAll();
        for (NullNarrowing saved : loopSavedNarrowing) {
            saved.invalidateAll();
        }
    }

    // =======================================================================
    // Literal
    // =======================================================================

    private Type checkLiteral(LiteralExpr lit) {
        return switch (lit.value()) {
            case LiteralValue.NullLiteral n -> Type.Null.INSTANCE;
            case LiteralValue.BooleanLiteral b -> Type.Boolean.INSTANCE;

            case LiteralValue.NumberLiteral n -> Type.Number.INSTANCE;
            case LiteralValue.StringLiteral s -> Type.String.INSTANCE;
        };
    }

    // =======================================================================
    // Identifier
    // =======================================================================

    private Type checkIdentifier(IdentifierExpr id) {
        if (id.name().equals("undefined")) return Type.Undefined.INSTANCE;
        Symbol sym = resolveVisibleBinding(id.name());
        if (sym == null) {
            error(DiagnosticCode.E2001, "Undeclared identifier '" + id.name() + "'", id.span());
            return Type.Error.INSTANCE;
        }

        if (sym instanceof Symbol.ModuleSymbol || sym instanceof Symbol.ClassSymbol) {
            error(DiagnosticCode.E3001, "Namespace/class metadata is not a data value", id.span());
            return Type.Error.INSTANCE;
        }
        Type declaredType = getDeclaredType(sym);
        Type narrowedType = narrowing.getNarrowedType(id.name());
        if (narrowedType != null && !Types.equals(declaredType, narrowedType)) {
            // The read is a refinement claim (declared T | null, read as
            // T): publish the fact the one lowering consumes at the
            // operator operand, condition, index receiver/key and table
            // member receiver positions.
            narrowedReads.add(id);
        }
        // The checked expression fact of every class-typed binding read:
        // the binding's own declaration (a narrowed nullable read keeps
        // the same declaration).
        if (sym instanceof Symbol.VariableSymbol variable) {
            Symbol.ClassSymbol decl = exactBindingClassSymbol(variable);
            if (narrowedType != null) {
                recordExactClassDecl(id, narrowedType, decl);
            } else {
                recordExactClassDecl(id, declaredType, decl);
            }
        }
        if (narrowedType != null) {
            return narrowedType;
        }
        return declaredType;
    }

    // =======================================================================
    // Binary expression
    // =======================================================================

    private Type checkBinary(BinaryExpr bin) {
        // Composite operands do not inherit the enclosing storage type.
        // Record reads remain unknown and must be extracted or refined
        // before an operator consumes a concrete value.
        Type savedExpected = expectedType;
        expectedType = null;
        Type leftType;
        Type rightType;
        try {
            leftType = checkExpression(bin.left());
            rightType = checkExpression(bin.right());
        } finally {
            expectedType = savedExpected;
        }

        if (leftType == Type.Error.INSTANCE || rightType == Type.Error.INSTANCE) {
            return Type.Error.INSTANCE;
        }

        BinaryOp op = bin.op();

        if (op == BinaryOp.EQ || op == BinaryOp.NEQ) {
            boolean equalTypes = Types.equals(leftType, rightType);
            boolean nullableVsNull = isNullableOf(leftType, rightType)
                || isNullableOf(rightType, leftType);
            if ((equalTypes && !(leftType instanceof Type.Void)) || nullableVsNull) {

                return Type.Boolean.INSTANCE;
            }
            error(DiagnosticCode.E3006,
                "Cannot compare " + typeName(leftType) + " with " + typeName(rightType),
                bin.span());
            return Type.Error.INSTANCE;
        }

        if (op == BinaryOp.AND || op == BinaryOp.OR) {
            if (!(leftType instanceof Type.Boolean)) {
                error(DiagnosticCode.E3007, "Left operand of '" + opSymbol(op) + "' must be boolean, got "
                    + typeName(leftType), bin.left().span());
                return Type.Error.INSTANCE;
            }
            if (!(rightType instanceof Type.Boolean)) {
                error(DiagnosticCode.E3007, "Right operand of '" + opSymbol(op) + "' must be boolean, got "
                    + typeName(rightType), bin.right().span());
                return Type.Error.INSTANCE;
            }
            return Type.Boolean.INSTANCE;
        }

        if (op == BinaryOp.LT || op == BinaryOp.LTE || op == BinaryOp.GT || op == BinaryOp.GTE) {
            if (Types.equals(leftType, rightType)) {
                if (leftType instanceof Type.Number || leftType instanceof Type.String) {
                    return Type.Boolean.INSTANCE;
                }
            }
            error(DiagnosticCode.E3007, "Invalid operand types for comparison: "
                + typeName(leftType) + " and " + typeName(rightType), bin.span());
            return Type.Error.INSTANCE;
        }

        // For +, check special case first
        if (op == BinaryOp.ADD) {
            if (Types.equals(leftType, rightType)) {
                if (leftType instanceof Type.Number || leftType instanceof Type.String) {
                    return leftType;
                }
            }
            error(DiagnosticCode.E3010, "Invalid operand types for '+': "
                + typeName(leftType) + " and " + typeName(rightType), bin.span());
            return Type.Error.INSTANCE;
        }

        // Other arithmetic: both operands must be numbers.
        if (!Types.equals(leftType, rightType)) {
            error(DiagnosticCode.E3007, "Invalid operand types for '" + opSymbol(op) + "': "
                + typeName(leftType) + " and " + typeName(rightType), bin.span());
            return Type.Error.INSTANCE;
        }

        if (leftType instanceof Type.Number) {
            return leftType;
        }

        error(DiagnosticCode.E3007, "Invalid operand types for '" + opSymbol(op) + "': "
            + typeName(leftType) + " and " + typeName(rightType), bin.span());
        return Type.Error.INSTANCE;
    }

    // =======================================================================
    // Unary expression
    // =======================================================================

    private Type checkUnary(UnaryExpr un) {
        // A unary operator's operand is not the enclosing position's own
        // expression either: it checks with no expected type, so a nested
        // table read is E3003 at the operand.
        Type savedExpected = expectedType;
        expectedType = null;
        Type exprType;
        try {
            exprType = checkExpression(un.expr());
        } finally {
            expectedType = savedExpected;
        }
        if (exprType == Type.Error.INSTANCE) return Type.Error.INSTANCE;

        return switch (un.op()) {
            case TYPEOF -> {
                if (exprType instanceof Type.Void) {
                    error(DiagnosticCode.E3007, "Void results are not data", un.span());
                    yield Type.Error.INSTANCE;
                }
                yield Type.String.INSTANCE;
            }
            case NOT -> {
                if (!(exprType instanceof Type.Boolean)) {
                    error(DiagnosticCode.E3007, "Operand of '!' must be boolean, got "
                        + typeName(exprType), un.expr().span());
                    yield Type.Error.INSTANCE;
                }
                yield Type.Boolean.INSTANCE;
            }
            case NEG -> {
                if (!(exprType instanceof Type.Number)) {
                    error(DiagnosticCode.E3007, "Operand of unary '-' must be number, got "
                        + typeName(exprType), un.expr().span());
                    yield Type.Error.INSTANCE;
                }
                yield exprType;
            }
        };
    }

    // =======================================================================
    // Call expression
    // =======================================================================

    private Type checkCall(CallExpr call) {
        if (call.callee() instanceof IdentifierExpr id && currentScope.resolve(id.name()) == null) {
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E2001,
                    "Undeclared identifier '" + id.name() + "'", id.span())
                    .withMissingSymbol("FUNCTION", id.name(), id.span().file(),
                            List.of("arity=" + call.args().size())));
            for (ExpressionNode arg : call.args()) checkExpression(arg);
            return Type.Error.INSTANCE;
        }
        Type calleeType = checkExpression(call.callee());
        if (calleeType == Type.Error.INSTANCE) {
            for (ExpressionNode arg : call.args()) checkExpression(arg);
            return Type.Error.INSTANCE;
        }

        // F5: Determine parameter types for contextual typing BEFORE checking args
        List<Type> paramTypesForContext = getParamTypesForContext(call);
        // The parameter positions' exact declared class symbols (the
        // callee's own parameter annotations resolved in the callee's
        // declaring module): an argument object literal of a class-typed
        // parameter is laid out by that declared provider, so a
        // same-named local declaration of the same canonical identity
        // never captures the construction. A null entry leaves the
        // literal's construction context to the enclosing expectation.
        List<Symbol.ClassSymbol> paramClassDeclsForContext =
            getParamClassDeclsForContext(call);
        if (paramClassDeclsForContext == null && paramTypesForContext != null) {
            // A function-typed callee carries no declared parameter
            // annotation, but its bound parameter carriers name their
            // defining providers: the argument literal's construction
            // context still resolves the exact declaration (never a
            // first alias or an identity-first match).
            paramClassDeclsForContext =
                providerClassDeclsOf(paramTypesForContext);
        }

        List<Type> argTypes = new ArrayList<>();
        for (int i = 0; i < call.args().size(); i++) {
            ExpressionNode arg = call.args().get(i);

            Type savedExpected = expectedType;
            Symbol.ClassSymbol savedContextual = contextualClassDecl;
            if (paramTypesForContext != null && i < paramTypesForContext.size()) {
                expectedType = paramTypesForContext.get(i);
            }
            // The enclosing construction context (an annotated binding,
            // field default or assignment target) stays authoritative
            // when it exists: the parameter context supplies the literal's
            // declaration only for an argument the enclosing expression
            // does not already lay out.
            if (contextualClassDecl == null
                    && paramClassDeclsForContext != null
                    && i < paramClassDeclsForContext.size()) {
                contextualClassDecl = paramClassDeclsForContext.get(i);
            }
            argTypes.add(checkContextualReadPosition(arg));
            expectedType = savedExpected;
            contextualClassDecl = savedContextual;
        }

        // Intrinsic call? The callee's own visible binding decides: a
        // lexically shadowing local, parameter or enclosing-block binding
        // — and a same-block `let` that is not declared yet
        // (docs/spec-v1.5.md §Name resolution — Declaration order) —
        // precedes the root intrinsic, exactly as checkIdentifier
        // resolved the callee expression's type above.
        if (call.callee() instanceof IdentifierExpr id) {
            Symbol sym = resolveVisibleBinding(id.name());
            if (sym instanceof Symbol.IntrinsicSymbol is) {
                return is.resolver().checkCall(call, argTypes, ctx);
            }
        }

        // E3014: async function called without await (after intrinsic check,
        // so intrinsics handle their own async logic if ever needed)
        if (calleeType instanceof Type.Func funcType && funcType.isAsync()
                && call != directAwaitCallee) {
            error(DiagnosticCode.E3014,
                "Direct call to async function requires 'await'",
                call.span());
            return Type.Error.INSTANCE;
        }

        if (calleeType instanceof Type.Func funcType) {
            return checkFunctionCall(call, funcType, argTypes);
        }

        error(DiagnosticCode.E3008, typeName(calleeType) + " is not callable", call.callee().span());
        return Type.Error.INSTANCE;
    }

    /**
     * The exact declared class symbol of every callee parameter position
     * (the callee's own function symbol resolved through the identifier
     * or the imported module alias): the parameter's declared annotation
     * resolved in the callee's declaring module scope. The parameter's
     * own declared provider — never an identity-first match or the call
     * site's local declaration — names the construction context, which
     * stays exact for a harness-shared identity. Returns null when the
     * callee carries no declaration surface (a function-typed value, an
     * intrinsic, a synthetic symbol).
     */
    private List<Symbol.ClassSymbol> getParamClassDeclsForContext(CallExpr call) {
        Symbol.FunctionSymbol callee = calleeDeclarationOf(call);
        if (callee == null || callee.paramTypeNodes() == null) {
            return null;
        }
        List<Symbol.ClassSymbol> decls = new ArrayList<>();
        for (TypeNode paramNode : callee.paramTypeNodes()) {
            decls.add(paramNode == null ? null
                : nameResolver.resolveTypeNodeClassSymbolInModule(paramNode,
                    callee.declaringModulePath()));
        }
        return decls;
    }

    /**
     * The declared function symbol a call's own callee names: a direct
     * identifier (a local or hoisted function), or a member access on an
     * imported module alias (the imported module's own declared function
     * symbol). A callee that is a function-typed value names no
     * declaration and returns null.
     */
    private Symbol.FunctionSymbol calleeDeclarationOf(CallExpr call) {
        ExpressionNode callee = call.callee();
        if (callee instanceof IdentifierExpr id) {
            Symbol sym = currentScope.resolve(id.name());
            return sym instanceof Symbol.FunctionSymbol fs ? fs : null;
        }
        if (callee instanceof MemberAccessExpr mae
                && mae.object() instanceof IdentifierExpr id) {
            Symbol sym = currentScope.resolve(id.name());
            if (sym instanceof Symbol.ModuleSymbol ms) {
                return ms.functionSymbols().get(mae.field());
            }
        }
        return null;
    }

    /**
     * Extracts parameter types from a callee for contextual typing.
     * Returns null if not available.
     */
    private List<Type> getParamTypesForContext(CallExpr call) {
        if (call.callee() instanceof IdentifierExpr id) {
            Symbol sym = currentScope.resolve(id.name());
            if (sym instanceof Symbol.FunctionSymbol fs) {
                return new ArrayList<>(fs.funcType().paramTypes());
            }
            if (sym instanceof Symbol.IntrinsicSymbol is) {
                if (is.type() instanceof Type.Func ft) {
                    return new ArrayList<>(ft.paramTypes());
                }
            }
        }
        Type calleeType = typeMap.get(call.callee());
        if (calleeType instanceof Type.Func ft) {
            return new ArrayList<>(ft.paramTypes());
        }
        calleeType = checkExpression(call.callee());
        if (calleeType instanceof Type.Func ft) {
            return new ArrayList<>(ft.paramTypes());
        }
        return null;
    }

    private Type checkFunctionCall(CallExpr call, Type.Func funcType, List<Type> argTypes) {
        int paramCount = funcType.paramTypes().size();

        // DEAL v1.2: fixed arity only — function types carry no rest arm.
        if (argTypes.size() != paramCount) {
            error(DiagnosticCode.E3009,
                "Function argument count mismatch: expected " + paramCount
                + ", got " + argTypes.size(),
                call.span());
            return Type.Error.INSTANCE;
        }

        for (int i = 0; i < paramCount; i++) {
            Type paramType = funcType.paramTypes().get(i);
            Type argType = argTypes.get(i);
            if (argType == Type.Error.INSTANCE) continue;
            if (!isAssignable(paramType, argType)) {
                if (!reportClassProviderCrossing(paramType, argType,
                        call.args().get(i).span())) {
                    error(DiagnosticCode.E5001,
                        "Function argument type mismatch at position " + (i + 1)
                        + ": expected " + typeName(paramType)
                        + ", got " + typeName(argType),
                        call.args().get(i).span());
                }
            }
        }

        return funcType.returnType();
    }

    // =======================================================================
    // Member access
    // =======================================================================

    private Type checkMemberAccess(MemberAccessExpr mae) {
        if (mae.object() instanceof IdentifierExpr id
                && resolveVisibleBinding(id.name()) instanceof Symbol.ModuleSymbol ms) {
            Type exportType = ms.exports().get(mae.field());
            if (ms.classSymbols().containsKey(mae.field())) {
                error(DiagnosticCode.E3001, "Class metadata is not a data value", mae.span());
                return Type.Error.INSTANCE;
            }
            if (exportType != null) {
                typeMap.put(mae.object(), Type.Table.INSTANCE);
                return exportType;
            }
            error(DiagnosticCode.E2004, "Export '" + mae.field() + "' not found in module '"
                + id.name() + "'. Available: " + String.join(", ", ms.exports().keySet()), mae.span());
            return Type.Error.INSTANCE;
        }
        Type objType = checkExpression(mae.object());
        if (objType == Type.Error.INSTANCE) return Type.Error.INSTANCE;

        String field = mae.field();

        // `length` intrinsic property (arrays only — a
        // `string` is a Unicode scalar-value sequence and has no `.length`;
        // string lengths are stdlib `string.length(s)` measured in scalar
        // values).
        if (objType instanceof Type.Array && field.equals("length")) {
            return Type.Number.INSTANCE;
        }

        // Module symbol via identifier (must be checked before Table)
        if (mae.object() instanceof IdentifierExpr id) {
            Symbol sym = currentScope.resolve(id.name());
            if (sym instanceof Symbol.ModuleSymbol ms) {
                Type exportType = ms.exports().get(field);
                if (exportType != null) return exportType;
                error(DiagnosticCode.E2004, "Export '" + field + "' not found in module '"
                    + id.name() + "'. Available: " + String.join(", ", ms.exports().keySet()), mae.span());
                return Type.Error.INSTANCE;
            }
        }

        // Class field access (including built-in Error class).
        //
        // ISSUE-0717 (defect snippet 30): a nullable-typed receiver must
        // be narrowed before its fields are read. Narrowing is supported
        // only for local variables and parameters (docs/spec-v1.5.md
        // §Nullable semantics — "No property-path narrowing"), so a
        // plain identifier receiver is narrowed by the null-narrowing
        // analysis inside its guarded branch and an unguarded read — a
        // property-path receiver included — stays E3003: the receiver's
        // declared nullable type is never unwrapped here.
        Type classTarget = objType;
        if (classTarget instanceof Type.Class cls) {
            // The receiver's own checked declaration names the exact
            // declaring provider first (two same-named siblings share one
            // canonical identity), then the receiver carrier's defining
            // provider resolves the declaration (never a textual
            // class-name search that would pick a same-spelled local or
            // first-aliased class).
            Symbol.ClassSymbol sym = exactReceiverClassSymbol(mae.object(), cls);
            if (sym == null) {
                sym = resolveClassSymbol(cls);
            }
            if (sym != null) {
                ClassField cf = IntrinsicResolvers.findField(sym.fields(), field);
                if (cf != null) {
                    Type fieldType = resolveFieldTypeInClassModule(sym, cf.type());
                    // The exact declaration of a class-typed field: the
                    // chained receiver's own annotation resolved in the
                    // declaring module's scope selects the declared
                    // provider (a harness-shared identity has no
                    // identity-keyed answer).
                    recordExactClassDecl(mae, fieldType,
                        exactFieldClassSymbol(sym, cf.type(), fieldType));
                    if (cf.optional() && !assignmentTargetMode) {
                        // A read of an omitted optional field yields null;
                        // a write target keeps the declared field type. The
                        // read result is the canonical T | null wrap, so a
                        // field declared null reads as null | undefined.
                        // Absence never becomes explicit null.
                        fieldType = Types.optionalRead(fieldType);
                    }
                    return fieldType;
                } else {
                    error(DiagnosticCode.E4002, "Field '" + field + "' not declared in class '"
                        + cls.name() + "'", mae.span());
                    return Type.Error.INSTANCE;
                }
            }
            return Type.Error.INSTANCE;
        }

        // F4: Table access — in write context (assignment target), allow without
        // contextual type. Otherwise admit only a direct read in one of the
        // five specification positions (let initializer, assignment value,
        // return expression, call argument, if condition): the expected
        // type must be present and the read must be the position's own
        // outermost expression (`contextualReadPosition`). Every other table
        // read — a loop condition, a composite operand, an array or class
        // literal element, a template interpolation part included — is
        // E3003 at the read.
        if (objType instanceof Type.Table) {
            if (assignmentTargetMode) {
                return Type.Table.INSTANCE;
            }
            return Type.Unknown.INSTANCE;
        }

        error(DiagnosticCode.E3003,
            "Cannot access field '" + field + "' on type " + typeName(objType), mae.span());
        return Type.Error.INSTANCE;
    }

    // =======================================================================
    // Index expression
    // =======================================================================

    private Type checkIndex(IndexExpr idx) {
        Type arrayType = checkExpression(idx.array());
        Type indexType = checkExpression(idx.index());
        if (arrayType == Type.Error.INSTANCE || indexType == Type.Error.INSTANCE)
            return Type.Error.INSTANCE;

        // A-D10 (assignment-delete-address-chains): table index
        // write/delete keys must have static type string (docs/spec-v1.5.md
        // §Writing table fields and §Deletion). The F5 "allow any index
        // type and skip further checks" skip is superseded by the static string-key gate.
        if (arrayType instanceof Type.Table) {
            if (assignmentTargetMode) {
                if (indexType instanceof Type.String) {
                    return Type.Table.INSTANCE;
                }
                error(DiagnosticCode.E3018,
                    "Table index key must have static type string, got "
                    + typeName(indexType), idx.index().span());
                return Type.Error.INSTANCE;
            }
            // A computed table read takes the contextual target type the
            // surrounding expression supplies only in the same five direct
            // positions (the algorithm of a table member read); its key
            // must have static type string.
            if (!(indexType instanceof Type.String)) {
                error(DiagnosticCode.E3007,
                    "Array index must be int, got " + typeName(indexType),
                    idx.index().span());
                return Type.Error.INSTANCE;
            }
            return Type.Unknown.INSTANCE;
        }

        // Integrality and bounds are runtime checks; static indexes are numbers.
        if (!(indexType instanceof Type.Number)) {
            error(DiagnosticCode.E3007, "Array index must be number, got " + typeName(indexType),
                idx.index().span());
            return Type.Error.INSTANCE;
        }
        if (arrayType instanceof Type.Array arr) {
            // The element read keeps the array expression's recorded
            // exact declaration (an array of one class identity), so a
            // chained class-typed receiver resolves the declaring
            // provider through the binding's own annotation.
            recordExactClassDecl(idx, arr.element(), exactClassDecls.get(idx.array()));
            return arr.element();
        }

        error(DiagnosticCode.E3007, "Cannot index type " + typeName(arrayType), idx.array().span());
        return Type.Error.INSTANCE;
    }

    // =======================================================================
    // Array literal
    // =======================================================================

    private Type checkArrayLiteral(ArrayLiteralExpr arr) {
        // The contextual element type: an array-typed expectation, or the
        // array inside a nullable expectation (e.g. int[] | null).
        Type elementContext = null;
        if (expectedType instanceof Type.Array expectedArr) {
            elementContext = expectedArr.element();
        } else if (expectedType instanceof Type.Nullable ne
                && ne.inner() instanceof Type.Array innerArr) {
            elementContext = innerArr.element();
        }

        List<ExpressionNode> elements = arr.elements();
        if (elements.isEmpty()) {
            if (elementContext != null) return Types.array(elementContext);
            error(DiagnosticCode.E3002, "Cannot infer type of empty array literal", arr.span());
            return Type.Error.INSTANCE;
        }

        if (elementContext != null) {
            // Contextual element checking: every element is checked against
            // the contextual element type and admitted when assignable
            // (int and null both fit (int | null)[]), so the literal has
            // the expected array type.
            boolean ok = true;
            for (ExpressionNode elem : elements) {
                Type savedExpected = expectedType;
                expectedType = elementContext;
                Type elemType = checkExpression(elem);
                expectedType = savedExpected;
                if (elemType == Type.Error.INSTANCE) { ok = false; continue; }
                if (!isAssignable(elementContext, elemType)) {
                    if (!reportClassProviderCrossing(elementContext, elemType,
                            elem.span())) {
                        error(DiagnosticCode.E3011,
                            "Mixed types in array literal: " + typeName(elementContext)
                            + " and " + typeName(elemType),
                            elem.span());
                    }
                    ok = false;
                }
            }
            if (!ok) return Type.Error.INSTANCE;
            return Types.array(elementContext);
        }

        Type firstType = null;
        boolean mixed = false;
        for (ExpressionNode elem : elements) {
            Type elemType = checkExpression(elem);
            if (elemType == Type.Error.INSTANCE) continue;
            if (firstType == null) firstType = elemType;
            else if (!Types.equals(firstType, elemType)) {
                mixed = true;
                error(DiagnosticCode.E3011,
                    "Mixed types in array literal: " + typeName(firstType)
                    + " and " + typeName(elemType),
                    elem.span());
            }
        }
        if (mixed || firstType == null) return Type.Error.INSTANCE;
        return Types.array(firstType);
    }

    // =======================================================================
    // Object literal
    // =======================================================================

    private Type checkObjectLiteral(ObjectLiteralExpr obj) {
        Set<String> propertyNames = new HashSet<>();
        for (Property property : obj.properties()) {
            if (!propertyNames.add(property.name())) {
                error(DiagnosticCode.E3001, "Duplicate property '" + property.name() + "'", property.span());
                return Type.Error.INSTANCE;
            }
        }
        if (expectedType instanceof Type.Class cls) {
            return checkClassConstruction(obj, cls);
        }
        // A nullable class expectation admits the construction (v1.2: the
        // object literal is a class instance; null is the other arm).
        if (expectedType instanceof Type.Nullable ne
                && ne.inner() instanceof Type.Class cls) {
            Type constructed = checkClassConstruction(obj, cls);
            if (constructed == Type.Error.INSTANCE) return constructed;
            return constructed;
        }
        Type savedExpected = expectedType;
        expectedType = null;
        for (Property prop : obj.properties()) {
            if (checkExpression(prop.value()) instanceof Type.Void)
                error(DiagnosticCode.E3001, "Void results cannot be stored in records", prop.value().span());
        }
        expectedType = savedExpected;
        if (obj.properties().isEmpty() && expectedType == null) {
            error(DiagnosticCode.E3002, "Cannot infer type of empty object literal", obj.span());
            return Type.Error.INSTANCE;
        }
        return Type.Table.INSTANCE;
    }

    /**
     * Resolves a class-field type annotation, preferring the owning module's
     * scope when the class symbol belongs to a different module.
     *
     * <p>Cross-module field types (e.g. {@code addresses: Address[]} declared
     * in a companion module) cannot resolve bare class names against the
     * importing module's scope; they are resolved against the owning module
     * first ({@link NameResolver#resolveTypeNodeInModule}). When the owning
     * module's resolution is unsupported or returns {@code null}, this falls
     * back to the plain importing-module resolution — byte-for-byte today's
     * behavior for every case that does not need foreign resolution.</p>
     */
    private Type resolveFieldTypeInClassModule(Symbol.ClassSymbol cs,
                                               TypeNode typeNode) {
        String ownerModule = cs.modulePath();
        if (ownerModule != null && !ownerModule.isEmpty()
                && !ownerModule.equals(modulePath)) {
            Type foreign = nameResolver.resolveTypeNodeInModule(
                typeNode, ownerModule);
            if (foreign != null) {
                return foreign;
            }
        }
        return nameResolver.resolveTypeNode(typeNode);
    }

    private Type checkClassConstruction(ObjectLiteralExpr obj, Type.Class cls) {
        // The contextual declaration wins when it names this exact class
        // (same canonical identity): the value is laid out for the
        // declaration that supplied the context, not for a same-named
        // declaration the lexical scope resolves instead.
        Symbol sym;
        Symbol.ClassSymbol contextual = contextualClassDecl;
        // Whether the enclosing construction context supplied the
        // declaration: only the contextual declaration is published as
        // the literal's exact construction declaration. The scope's own
        // same-named declaration (the unanchored fallback) and the
        // identity-keyed cross-module route name no construction context:
        // the one lowering then resolves the literal by the recorded
        // contextual provider or fails closed when the canonical identity
        // carries more than one declaration.
        boolean contextualSelection = false;
        if (contextual != null
                && contextual.name().equals(cls.name())
                && contextual.identity().equals(cls.identity())
                && providerAgrees(contextual, cls)) {
            sym = contextual;
            contextualSelection = true;
        } else {
            // The receiver carrier's own defining provider resolves the
            // declaration; only an unbound carrier (public descriptor
            // synthesis, retained harnesses) falls back to the exact
            // local declaration and the identity-keyed cross-module
            // resolution (never a textual class-name search that would
            // pick a same-spelled local or first-aliased class).
            sym = resolveClassSymbol(cls);
        }
        if (!(sym instanceof Symbol.ClassSymbol cs)) {
            error(DiagnosticCode.E3004, "Unknown class '" + cls.name() + "'", obj.span());
            return Type.Error.INSTANCE;
        }
        // Checked expression fact: the exact declaration this literal was
        // laid out by, consumed by the E3019 cross-declaration gate at
        // initialization and assignment-expression boundaries, and by the
        // one lowering's exact-provider selection.
        constructedClassDecls.put(obj, cs);
        // The construction context is published through CheckResult when
        // an enclosing annotation, a callee parameter or a declared
        // return annotation named this exact declaration, or when the
        // expectation's own carrier bound this declaration's provider
        // (the provider-authoritative route: a qualified annotation, an
        // imported field/parameter/return type). The one lowering
        // attributes the literal's resources and plan to that
        // declaration, so a same-identity sibling or an imported callee's
        // parameter provider never captures the construction. A literal
        // without such a context (an unbound carrier, the scope's own
        // declaration) publishes nothing: the lowering must not silently
        // lay it out by a unit-local same-identity declaration when the
        // identity carries more than one declaration.
        if (contextualSelection || providerBoundSelection(cls, cs)) {
            recordExactClassDecl(obj, cls, cs);
        }
        // The declaration's AST node, when the symbol is this module's own
        // (a hoisted module-level or nested block declaration): published
        // through CheckResult so the emitters select that exact
        // declaration's artifact (the same-named shadowing declaration's
        // lexical binding must not capture the construction). Imported and
        // synthetic symbols carry no node; their constructions stay on the
        // name/alias paths.
        ClassDeclaration constructedDecl = nameResolver.classDeclarationNode(cs);
        if (constructedDecl != null) {
            constructedDeclNodes.put(obj, constructedDecl);
        }

        List<ClassField> classFields = cs.fields();
        Map<String, Property> provided = new HashMap<>();
        for (Property prop : obj.properties()) {
            provided.put(prop.name(), prop);
        }

        Set<String> classFieldNames = new HashSet<>();
        for (ClassField cf : classFields) classFieldNames.add(cf.name());

        for (String propName : provided.keySet()) {
            if (!classFieldNames.contains(propName)) {
                error(DiagnosticCode.E4002,
                    "Extra field '" + propName + "' in class literal for '" + cls.name() + "'",
                    provided.get(propName).span());
            }
        }

        for (ClassField field : classFields) {
            Property property = provided.get(field.name());
            Type fieldType = resolveFieldTypeInClassModule(cs, field.type());
            if (property == null) {
                if (!field.optional()) {
                    error(DiagnosticCode.E4001,
                        "Missing required field '" + field.name()
                            + "' in class literal for '" + cls.name() + "'",
                        obj.span());
                }
                continue;
            }
            Type savedExpected = expectedType;
            Symbol.ClassSymbol savedContextualClass = contextualClassDecl;
            expectedType = fieldType;
            contextualClassDecl = exactFieldClassSymbol(cs, field.type(), fieldType);
            Type valueType = checkExpression(property.value());
            expectedType = savedExpected;
            contextualClassDecl = savedContextualClass;
            if (valueType != Type.Error.INSTANCE && !isAssignable(fieldType, valueType)
                    && !reportClassProviderCrossing(fieldType, valueType, property.span())) {
                error(DiagnosticCode.E4003,
                    "Type mismatch for field '" + field.name() + "': expected "
                        + typeName(fieldType) + ", got " + typeName(valueType),
                    property.span());
            }
        }

        // A literal an explicit construction context named is checked as
        // that declaration's own class type (the contextual declaration,
        // the callee parameter, or the declared return annotation) —
        // never as the surrounding expectation's carrier: a value a
        // same-identity sibling laid out keeps that sibling's provider,
        // so the enclosing boundary sees the crossing instead of the
        // value silently adopting the expected declaration's provider
        // while its layout/defaults belong to the other declaration
        // (ISSUE-0809 review cycle 2). The unanchored fallback keeps the
        // expectation's carrier: the one lowering then refuses the
        // multiple-declaration identity instead of committing to a first
        // declaration (never a silent first-alias selection). The builtin
        // Error module path is the unbound provider.
        return contextualSelection ? classTypeOf(cs) : cls;
    }

    /**
     * Whether one contextual declaration may lay out a construction of
     * the checked class carrier {@code cls}: the carrier names the same
     * defining provider, or it is unbound (public descriptor synthesis,
     * retained harnesses) and the contextual declaration is the only
     * named declaration. A bound carrier of another provider is never
     * captured by a same-identity contextual declaration (its own
     * provider resolves the declaration instead).
     */
    private static boolean providerAgrees(Symbol.ClassSymbol decl,
                                          Type.Class cls) {
        String provider = cls.providerModule();
        return provider == null
            || provider.equals(Types.providerModule(decl.modulePath()));
    }

    /**
     * Whether the expectation's own carrier bound the selected
     * declaration's provider: the class carrier names the declaration's
     * defining module, so the selection is exact (a qualified
     * annotation, an imported field/parameter/return type) even when no
     * enclosing construction context named it. An unbound carrier
     * (public descriptor synthesis, retained harnesses) binds nothing.
     */
    private static boolean providerBoundSelection(Type.Class cls,
                                                  Symbol.ClassSymbol decl) {
        String provider = cls.providerModule();
        String declared = Types.providerModule(decl.modulePath());
        return provider != null && declared != null
            && provider.equals(declared);
    }

    /**
     * The checked class type of one exact declared class symbol: the
     * declaring module's own wiring key is the defining provider and the
     * builtin {@code Error} module path stays the unbound provider.
     */
    private static Type.Class classTypeOf(Symbol.ClassSymbol cs) {
        return Types.classType(cs.name(), cs.identity(),
            Types.providerModule(cs.modulePath()));
    }

    // =======================================================================
    // Function expression
    // =======================================================================

    private Type checkFunctionExpr(FunctionExpr fe) {
        List<Type> paramTypes = new ArrayList<>();
        for (Parameter p : fe.params()) {
            paramTypes.add(nameResolver.resolveTypeNode(p.type()));
        }
        Type returnType = nameResolver.resolveResultTypeNode(fe.returnType());
        // The return annotation's own declared class symbol, resolved in
        // the scope the function expression is declared in (never in its
        // parameter scope): a returned object literal keeps the declared
        // provider's declaration.
        Symbol.ClassSymbol returnContext = exactAnnotationClassSymbol(
            currentScope, fe.returnType());
        Type.Func funcType = new Type.Func(paramTypes, returnType, fe.isAsync());

        // F1: Check if Pass 1 recorded a scope for this function expression.
        // If so, use it instead of creating a fresh scope — this ensures
        // let-declarations from Pass 1 are visible.
        SymbolTable savedScope = currentScope;
        SymbolTable feScope = scopeMap.get(fe.body());
        if (feScope != null) {
            currentScope = feScope;
            nameResolver.setCurrentScope(feScope);
        } else {
            currentScope = currentScope.enterScope();
            nameResolver.setCurrentScope(currentScope);

            // F6: Check for duplicate parameters before defining
            Set<String> paramNames = new HashSet<>();
            for (Parameter p : fe.params()) {
                if (paramNames.contains(p.name())) {
                    error(DiagnosticCode.E2002, "Duplicate parameter '" + p.name() + "'", p.span());
                    continue;
                }
                paramNames.add(p.name());
                Type pt = nameResolver.resolveTypeNode(p.type());
                currentScope.define(p.name(), new Symbol.VariableSymbol(p.name(), pt,
                    true, nameResolver.declaredClassSymbolOf(p.type()), p.type()));
            }
        }

        Type savedReturnType = currentReturnType;
        Symbol.ClassSymbol savedReturnContext = currentReturnContext;
        boolean savedInsideAsync = insideAsyncFunction;
        currentReturnType = returnType;
        currentReturnContext = returnContext;
        insideAsyncFunction = fe.isAsync();

        // F1: Use walkStatement(fe.body()) instead of walkStatements(fe.body().statements())
        // so that the Block's scope is entered. A function expression body
        // sees only declared types (no captured narrowing) and no
        // enclosing loop context. The try-region assignment bookkeeping is
        // isolated like the loop bookkeeping: declaring a function does not
        // execute its body, so a write in the body never reverts an
        // enclosing try region's refinement.
        NullNarrowing savedNarrowing = narrowing;
        narrowing = new NullNarrowing();
        Deque<Set<Symbol>> savedLoopAssignments = loopAssignments;
        loopAssignments = new ArrayDeque<>();
        Deque<Set<Symbol>> savedTryAssignments = tryAssignments;
        tryAssignments = new ArrayDeque<>();
        Deque<NullNarrowing> savedLoopSavedNarrowing = loopSavedNarrowing;
        loopSavedNarrowing = new ArrayDeque<>();
        int savedLoopDepth = loopDepth;
        loopDepth = 0;
        walkStatement(fe.body());
        narrowing = savedNarrowing;
        loopAssignments = savedLoopAssignments;
        tryAssignments = savedTryAssignments;
        loopSavedNarrowing = savedLoopSavedNarrowing;
        loopDepth = savedLoopDepth;

        // ISSUE-0717: the reachability-ordered walk (the null-returning
        // expression keeps the landed fall-off tolerance; see
        // checkFunctionDeclaration). The try/catch-local obligation is
        // independent of it.
        List<TryStatement> fallthroughs =
            ReturnAnalysis.tryCatchFallthroughs(fe.body());
        for (TryStatement ts : fallthroughs) {
            error(DiagnosticCode.E5002,
                "Function expression must return on all paths: the catch"
                    + " block of a definitely-returning try must also"
                    + " definitely return or throw",
                ts.catchBlock().span());
        }
        if (fallthroughs.isEmpty()
                && !(returnType instanceof Type.Void)
                && !ReturnAnalysis.definitelyReturns(fe.body())) {
            error(DiagnosticCode.E5002,
                "Function expression must return a value on all paths", fe.span());
        }

        currentReturnType = savedReturnType;
        currentReturnContext = savedReturnContext;
        insideAsyncFunction = savedInsideAsync;
        currentScope = savedScope;
        nameResolver.setCurrentScope(savedScope);

        return funcType;
    }

    // =======================================================================
    // Has expression
    // =======================================================================

    private Type checkAssertion(AssertionExpr assertion) {
        Type source = checkExpression(assertion.value());
        Type target = nameResolver.resolveTypeNode(assertion.target());
        if (target instanceof Type.Void || source instanceof Type.Void
                || (!(source instanceof Type.Unknown) && !isAssignable(target, source)
                    && !isAssignable(source, target))) {
            error(DiagnosticCode.E3001, "Incompatible checked assertion", assertion.span());
            return Type.Error.INSTANCE;
        }
        recordExactClassDecl(assertion, target, exactAnnotationClassSymbol(currentScope, assertion.target()));
        return target;
    }

    private Type checkInstanceof(InstanceofExpr test) {
        Type source = checkExpression(test.value());
        Type target = nameResolver.resolveTypeNode(test.target());
        if (!(target instanceof Type.Class cls) || source instanceof Type.Void) {
            error(DiagnosticCode.E3001, "instanceof requires a nominal class", test.span());
            return Type.Error.INSTANCE;
        }
        instanceofTargets.put(test, cls);
        return Type.Boolean.INSTANCE;
    }

    private Type checkHas(HasExpr has) {
        Type objType = checkExpression(has.object());
        Type keyType = checkExpression(has.key());
        if (!(keyType instanceof Type.String)) {
            error(DiagnosticCode.E4005, "Object.hasOwn key must be string", has.key().span());
            return Type.Error.INSTANCE;
        }
        if (objType instanceof Type.Table) return Type.Boolean.INSTANCE;
        if (objType == Type.Error.INSTANCE) return Type.Error.INSTANCE;

        if (!(objType instanceof Type.Class cls)) {
            error(DiagnosticCode.E4005,
                "'has' argument must be a class field access, got " + typeName(objType),
                has.span());
            return Type.Error.INSTANCE;
        }

        Symbol.ClassSymbol sym = exactReceiverClassSymbol(has.object(), cls);
        // If neither the receiver's own checked declaration nor the local
        // scope names an exact declaration, the receiver carrier's own
        // defining provider resolves it (never a textual class-name search
        // that would pick a same-spelled local or first-aliased class).
        if (sym == null) {
            sym = resolveClassSymbol(cls);
        }
        if (sym == null) {
            error(DiagnosticCode.E4005, "Class '" + cls.name() + "' not found", has.span());
            return Type.Error.INSTANCE;
        }

        if (has.field() == null) {
            error(DiagnosticCode.E4005, "Class presence tests require a literal declared-field name", has.key().span());
            return Type.Error.INSTANCE;
        }
        ClassField field = IntrinsicResolvers.findField(sym.fields(), has.field());
        if (field == null) {
            error(DiagnosticCode.E4005,
                "Field '" + has.field() + "' not declared in class '" + cls.name() + "'",
                has.span());
            return Type.Error.INSTANCE;
        }
        return Type.Boolean.INSTANCE;
    }

    // =======================================================================
    // Assignment expression
    // =======================================================================

    private Type checkAssignmentExpr(AssignmentExpr assign) {
        // F3 & F4: Determine target type first (with write-context mode for
        // table accesses), then use it as expectedType for the value.
        boolean savedMode = assignmentTargetMode;
        assignmentTargetMode = true;
        Type targetType = checkExpression(assign.target());
        assignmentTargetMode = savedMode;

        // The assignment gate is the declared type of the target, never a
        // flow-narrowed refinement (v1.2: `x = null` where x: T | null is
        // legal inside a narrowed block; the narrowing is invalidated by
        // the assignment instead).
        Type declaredTarget = targetType;
        if (assign.target() instanceof IdentifierExpr targetId) {
            Type declared = declaredTypeOf(targetId.name());
            if (declared != null && declared != Type.Error.INSTANCE) {
                declaredTarget = declared;
                // The target's recorded type is the declared type, never
                // the flow-narrowed refinement: the assignment gate and
                // every backend's assignment-site boundary check the
                // declared type (v1.2: `x = null` on a `T | null` binding
                // is legal inside a narrowed block).
                typeMap.put(targetId, declared);
            }
        }

        // D3 (typed-boundary-enforcement): array .length is read-only.
        // The target is a MemberAccessExpr with field "length" on an
        // Array-typed object. The append idiom xs[xs.length] = v has an
        // IndexExpr target (the MemberAccessExpr is its index), so it never
        // matches this shape and keeps emitting its append form.
        boolean readOnlyTarget = false;
        if (assign.target() instanceof IdentifierExpr id
                && !(resolveVisibleBinding(id.name()) instanceof Symbol.VariableSymbol)) {
            error(DiagnosticCode.E3017, "Assignment requires a variable binding", id.span());
            readOnlyTarget = true;
        }
        ExpressionNode receiver = assign.target() instanceof MemberAccessExpr member ? member.object()
            : assign.target() instanceof IndexExpr index ? index.array() : null;
        if (receiver instanceof IdentifierExpr id
                && resolveVisibleBinding(id.name()) instanceof Symbol.ModuleSymbol) {
            error(DiagnosticCode.E3017, "Imported exports are read-only", assign.target().span());
            readOnlyTarget = true;
        }
        if (assign.target() instanceof MemberAccessExpr mae
                && mae.field().equals("length")
                && typeOf(mae.object()) instanceof Type.Array) {
            error(DiagnosticCode.E3017, "Array length is read-only",
                assign.target().span());
            readOnlyTarget = true;
        }

        // F3: Use the target type as contextual expected type for the value
        Type savedExpected = expectedType;
        Symbol.ClassSymbol savedContextualClass = contextualClassDecl;
        if (declaredTarget != null && declaredTarget != Type.Error.INSTANCE
                && !(declaredTarget instanceof Type.Table)) {
            expectedType = declaredTarget;
        }
        // The target binding's exact class declaration is the construction
        // context for a same-typed object literal: an assignment into
        // `a: A` constructs the value as that A, never as an unrelated
        // same-named declaration the lexical scope would resolve.
        contextualClassDecl = null;
        if (assign.target() instanceof IdentifierExpr targetId) {
            Symbol targetSym = resolveVisibleBinding(targetId.name());
            if (targetSym instanceof Symbol.VariableSymbol vs) {
                contextualClassDecl = vs.declaredClass() != null
                    ? vs.declaredClass()
                    : exactAnnotationClassSymbol(currentScope, vs.declaredType());
            }
        }
        Type valueType = checkContextualReadPosition(assign.value());
        expectedType = savedExpected;
        contextualClassDecl = savedContextualClass;

        if (valueType == Type.Error.INSTANCE || targetType == Type.Error.INSTANCE) {
            return Type.Error.INSTANCE;
        }

        // A table write through a member/index target is unchecked (no
        // field schema). A table VARIABLE target is an ordinary typed
        // assignment: only a table value is assignable to it.
        boolean uncheckedTableFieldWrite = targetType instanceof Type.Table
            && !(assign.target() instanceof IdentifierExpr);
        if (uncheckedTableFieldWrite) {
            // Table write — always allowed
        } else if (!isAssignable(declaredTarget, valueType)) {
            if (!reportClassProviderCrossing(declaredTarget, valueType,
                    assign.span())) {
                reportAssignabilityError(declaredTarget, valueType, assign.span());
            }
        }

        // ISSUE-0717 (defect snippet 47): two same-named class declarations
        // of one canonical identity (the same-module nested-shadowing pair,
        // or a harness-synthesized carrier) own distinct declarations and
        // runtime layouts. An assignment that crosses the two declarations
        // is rejected at the frontend — never left to the backends'
        // duplicate-layout failure. Nullable class boundaries are the same
        // crossing (the canonical identity is unchanged by the nullability
        // wrapper).
        checkClassProvenance(declaredClassOf(assign.target()), declaredTarget,
            declaredClassOf(assign.value()), valueType, assign.span());

        if (assign.target() instanceof IdentifierExpr id) {
            recordAssignment(id.name());
        }

        // Normal target/value checking ran above (typeMap stays populated);
        // the read-only rejection makes the whole assignment erroneous.
        if (readOnlyTarget) {
            return Type.Error.INSTANCE;
        }

        return valueType;
    }

    // =======================================================================
    // Assignment compatibility
    // =======================================================================

    private void checkAssignment(Type targetType, Type exprType, Span span) {
        if (targetType == null || exprType == null) return;
        if (targetType == Type.Error.INSTANCE || exprType == Type.Error.INSTANCE) return;
        if (!isAssignable(targetType, exprType)) {
            if (reportClassProviderCrossing(targetType, exprType, span)) {
                return;
            }
            reportAssignabilityError(targetType, exprType, span);
        }
    }

    /**
     * Reports the E3019 crossing when the expected and actual types name
     * two distinct declarations of one same-named class through two bound,
     * distinct defining providers: the identity names the defining file,
     * so a same-spelled class of a sibling file is a distinct declaration
     * the value never retags into (and a same-module nested-shadowing
     * declaration stays the same rule through its own provider). Returns
     * {@code true} when the crossing was reported (and the caller skips
     * its own boundary diagnostic), {@code false} otherwise.
     */
    private boolean reportClassProviderCrossing(Type expected, Type actual,
                                                Span span) {
        if (!Types.crossesClassDeclarations(expected, actual)) {
            return false;
        }
        Type.Class expectedClass = nonNullClassType(expected) instanceof Type.Class cls
            ? cls : null;
        String name = expectedClass == null
            ? typeName(expected) : expectedClass.name();
        error(DiagnosticCode.E3019,
            "Cannot assign class '" + name
                + "' across its distinct declarations of the same-named class",
            span);
        return true;
    }

    /**
     * The E3019 cross-declaration gate: the value's class provenance and
     * the target's class provenance are two distinct declarations of a
     * same-named class — a shared identity (the same-module
     * nested-shadowing pair, or a harness-synthesized carrier) still names
     * two declarations, and each owns its own runtime layout. Applied at
     * assignment expressions, declaration initialization, and nullable
     * class boundaries alike (ISSUE-0717 defect snippet 47).
     */
    private void checkClassProvenance(Symbol.ClassSymbol targetDecl,
            Type targetType, Symbol.ClassSymbol valueDecl, Type valueType,
            Span span) {
        Type targetClass = nonNullClassType(targetType);
        Type valueClass = nonNullClassType(valueType);
        if (!(targetClass instanceof Type.Class tc)
                || !(valueClass instanceof Type.Class vc)
                || !Types.equals(tc, vc)) {
            return;
        }
        if (targetDecl != null && valueDecl != null && targetDecl != valueDecl) {
            error(DiagnosticCode.E3019,
                "Cannot assign class '" + tc.name()
                    + "' across its distinct declarations of the same-named class",
                span);
        }
    }

    /** The innermost type under any nullable wrapper. */
    private static Type nonNullClassType(Type type) {
        Type current = type;
        while (current instanceof Type.Nullable n) {
            current = n.inner();
        }
        return current;
    }

    /**
     * The class declaration a value expression was declared with, when the
     * expression names a class-typed binding or evaluates to one: the
     * provenance the E3019 cross-declaration gate compares (ISSUE-0717
     * defect snippet 47).
     */
    private Symbol.ClassSymbol declaredClassOf(ExpressionNode expr) {
        if (expr instanceof IdentifierExpr id) {
            Symbol sym = resolveVisibleBinding(id.name());
            if (sym instanceof Symbol.VariableSymbol vs) {
                return vs.declaredClass();
            }
        }
        // An assignment expression evaluates to the assigned value, so its
        // declaration provenance is the value's own provenance — preserved
        // through nested assignments as well. Without this arm the value's
        // exact declaration is dropped at `let b: A = (a = a)` (and at the
        // assignment-expression and nullable-initialization spellings), so
        // the E3019 gate cannot see the crossing and a JS artifact whose
        // statically string-typed field holds an int is published
        // (ISSUE-0717 review).
        if (expr instanceof AssignmentExpr assign) {
            return declaredClassOf(assign.value());
        }
        // A contextually constructed object literal evaluates to an
        // instance of the declaration that laid it out (the contextual
        // declaration, or the declaration construction resolved): that
        // declaration is the value's provenance exactly like a class-typed
        // binding's, so the E3019 gate sees the crossing at
        // `let b: A = (a = {})` and its nullable and assignment-boundary
        // spellings instead of publishing an artifact with the wrong
        // layout (ISSUE-0717 review).
        if (expr instanceof ObjectLiteralExpr obj) {
            Symbol.ClassSymbol constructed = constructedClassDecls.get(obj);
            if (constructed != null) {
                return constructed;
            }
        }
        return null;
    }

    /**
     * The pinned assignability failure at {@code span}: E5004 for the
     * reverse-arity extension case (F10), E3001 otherwise.
     */
    private void reportAssignabilityError(Type targetType, Type actualType, Span span) {
        // F10: Detect reverse arity for E5004
        if (targetType instanceof Type.Func tf && actualType instanceof Type.Func af
                && isReverseArity(af, tf)) {
            error(DiagnosticCode.E5004,
                "Arity extension failed: actual function has more parameters ("
                + af.paramTypes().size() + ") than target ("
                + tf.paramTypes().size() + ")",
                span);
        } else {
            error(DiagnosticCode.E3001,
                "Cannot assign " + typeName(actualType) + " to " + typeName(targetType),
                span);
        }
    }

    private boolean isAssignable(Type expected, Type actual) {
        // ISSUE-0809 exact_provider_carriage: two bound providers of two
        // same-named declarations are two declarations — a value never
        // retags across them, regardless of the structural arms below
        // (the provider-aware Types.equals already covers the recursive
        // positions; this keeps the nominal rule explicit).
        if (Types.crossesClassDeclarations(expected, actual)) return false;
        return Types.isAssignable(actual, expected);
    }
    /**
     * F10: Detects the reverse arity case where the actual function has more
     * parameters than the target, but the overlapping params and return type
     * match. This should emit E5004 instead of E3001.
     */
    private static boolean isReverseArity(Type.Func actual, Type.Func target) {
        if (!Types.equals(actual.returnType(), target.returnType())) return false;
        int actualCount = actual.paramTypes().size();
        int targetCount = target.paramTypes().size();
        if (actualCount <= targetCount) return false;
        for (int i = 0; i < targetCount; i++) {
            if (!Types.equals(actual.paramTypes().get(i), target.paramTypes().get(i)))
                return false;
        }
        return true;
    }

    // =======================================================================
    // Type inference for let without annotation
    // =======================================================================

    private Type inferTypeFromExpression(ExpressionNode init, Type exprType) {
        if (exprType == Type.Error.INSTANCE) return null;
        return switch (init) {
            case LiteralExpr lit -> exprType;
            case ArrayLiteralExpr arr -> {
                if (arr.elements().isEmpty()) {
                    error(DiagnosticCode.E3002, "Cannot infer type of empty array literal", init.span());
                    yield null;
                }
                yield exprType;
            }
            case ObjectLiteralExpr obj -> {
                if (obj.properties().isEmpty()) {
                    error(DiagnosticCode.E3002, "Cannot infer type of empty object literal", init.span());
                    yield null;
                }
                yield exprType;
            }
            case CallExpr call -> exprType;
            case AssignmentExpr assignment -> exprType;
            case FunctionExpr fe -> exprType;
            case IdentifierExpr id -> exprType;
            case IndexExpr idx -> exprType;
            case UnaryExpr un -> exprType;
            case BinaryExpr bin -> exprType;
            case MemberAccessExpr mae -> exprType;
            case HasExpr has -> exprType;
            case AssertionExpr assertion -> exprType;
            case InstanceofExpr test -> exprType;
            case TemplateLiteralExpr tl -> exprType;
            case AwaitExpression await -> {
                // Await in inference context: use the result type directly.
                yield exprType;
            }
            default -> {
                error(DiagnosticCode.E3002, "Cannot infer type of this expression", init.span());
                yield null;
            }
        };
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    private Type declaredTypeOf(String varName) {
        Symbol sym = resolveVisibleBinding(varName);
        if (sym instanceof Symbol.VariableSymbol vs) {
            Type t = vs.type();
            if (t != null) return t;
        } else if (sym instanceof Symbol.FunctionSymbol fs) {
            return fs.funcType();
        } else if (sym instanceof Symbol.IntrinsicSymbol is) {
            return is.type();
        }
        return Type.Error.INSTANCE;
    }

    /**
     * Resolves a name to the binding the declaration-order rules make
     * visible at the current statement (docs/spec-v1.5.md §Name resolution —
     * Declaration order and §Scope): a let declaration is visible only after its
     * declaration, so a reference from inside the declaring block's
     * earlier statements (a forward or self reference) resolves against
     * the enclosing scopes. The same resolution feeds identifier reads,
     * declared-type lookups, and assignment bookkeeping, so an assignment
     * inside a shadowing initializer targets the enclosing binding.
     */
    private Symbol resolveVisibleBinding(String name) {
        return resolveVisibleBinding(currentScope, name);
    }

    /**
     * The same declaration-order resolution started in {@code scope} instead
     * of the current scope: a fact that must hold after a scope closes (the
     * try/catch revert) resolves in the scope that receives the following
     * code, so a binding declared inside the closed construct never stands
     * in for a same-named enclosing binding.
     */
    private Symbol resolveVisibleBinding(SymbolTable scope, String name) {
        Symbol sym = scope.resolve(name);
        for (PendingScope ps : pendingScopes) {
            if (sym != null && ps.scope != null
                    && ps.remaining.contains(name)
                    && ps.scope.resolveLocal(name) == sym) {
                sym = ps.scope.parent() != null
                    ? ps.scope.parent().resolve(name) : null;
            }
        }
        return sym;
    }

    private Type getDeclaredType(Symbol sym) {
        return switch (sym) {
            case Symbol.VariableSymbol vs -> {
                Type t = vs.type();
                yield t != null ? t : Type.Error.INSTANCE;
            }
            case Symbol.FunctionSymbol fs -> fs.funcType();
            case Symbol.ClassSymbol cs -> classTypeOf(cs);
            case Symbol.ModuleSymbol ms -> Type.Table.INSTANCE;
            case Symbol.IntrinsicSymbol is -> is.type();
        };
    }

    private static boolean isNullableOf(Type maybeNullable, Type other) {
        return (maybeNullable instanceof Type.Nullable && (other instanceof Type.Null || other instanceof Type.Undefined)
                && Types.isAssignable(other, maybeNullable))
            || (maybeNullable instanceof Type.Unknown && (other instanceof Type.Null || other instanceof Type.Undefined));
    }

    private String opSymbol(BinaryOp op) {
        return switch (op) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";

            case POW -> "**";
            case EQ -> "===";
            case NEQ -> "!==";
            case LT -> "<";
            case LTE -> "<=";
            case GT -> ">";
            case GTE -> ">=";
            case AND -> "&&";
            case OR -> "||";
        };
    }

    /**
     * Returns a human-readable name for a type for use in diagnostic messages.
     * Returns {@code "<error>"} for the internal error sentinel.
     */
    public static String typeName(Type t) {
        if (t == null) return "null";
        return switch (t) {
            case Type.Null ignored -> "null";
            case Type.Undefined ignored -> "undefined";
            case Type.Unknown ignored -> "unknown";
            case Type.Void ignored -> "void";
            case Type.Boolean ignored -> "boolean";

            case Type.Number ignored -> "number";
            case Type.String ignored -> "string";
            case Type.Table ignored -> "Record<string, unknown>";
            case Type.Error ignored -> "<error>";
            case Type.Array a -> typeName(a.element()) + "[]";
            case Type.Nullable n -> typeName(n.inner()) + (n.admitsNull() ? " | null" : "")
                + (n.admitsUndefined() ? " | undefined" : "");
            case Type.Class c -> c.name();
            case Type.Func f -> "function";
        };
    }

    private void error(DiagnosticCode code, String message, Span span) {
        diagnostics.add(CompilerDiagnostic.error(code, message, span));
    }
}
