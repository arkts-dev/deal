package deal.checker;

import deal.ast.*;
import deal.diagnostics.CompilerDiagnostic;
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
 * <p>Errors produced: E3001–E3018, E4001–E4006, E5001–E5004.</p>
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
    //    admitted positions (docs/spec-v1.6.md §Reading and writing fields — a
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

    /** Post-evaluation states: effects have already invalidated stale facts. */
    private record ConditionFlow(NullNarrowing whenTrue, NullNarrowing whenFalse) {}
    private final Map<ExpressionNode, ConditionFlow> conditionFlows = new IdentityHashMap<>();
    private final Map<ExpressionNode, Type> declaredReadTypes = new IdentityHashMap<>();
    private final Map<ExpressionNode, Object> propertyReadEpochs = new IdentityHashMap<>();
    private final Map<ExpressionNode, NullNarrowing.LostRefinement> lostRefinementReads = new IdentityHashMap<>();

    // -- Assignment tracking for the open try regions of the current
    //    function: one frame per open try block, holding the resolved
    //    bindings assigned inside that region. Entries are binding
    //    symbols (identity, never value equality: two same-named
    //    declarations are distinct bindings), so a try-block declaration,
    //    a catch binding or a catch-local declaration that shadows an
    //    enclosing binding and a write inside a nested function body never
    //    revert another binding's refinement
    //    (docs/spec-v1.6.md §Narrowing across try/catch). The stack is
    //    swapped at function boundaries: declaring a function does not
    //    execute its body, so its writes are the nested function's own
    //    flow and the enclosing try region never records them. --
    private Deque<Set<Symbol>> tryAssignments = new ArrayDeque<>();

    // Exceptional entry facts only lose validity; successful guards never update them.
    private Deque<NullNarrowing> tryEntryFacts = new ArrayDeque<>();

    // -- Async/await tracking --
    private boolean insideAsyncFunction = false;
    private CallExpr directAwaitCallee = null;
    private CallExpr directBuiltinCall = null;

    // -- Current function return type (for return checking) --
    private Type currentReturnType = null;
    private TypeNode currentReturnAnnotation = null;

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
    //    (docs/spec-v1.6.md §Nullable semantics: after an await every
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
        // Exiting a block discards only its declarations' identity-owned facts.
        boolean blockScope = stmt instanceof Block;

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

        if (blockScope && stmtScope != null) {
            for (Symbol binding : stmtScope.symbols().values()) narrowing.invalidate(binding);
        }
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
    // Class declarations
    // =======================================================================

    private void checkClassDeclaration(ClassDeclaration cd) {
        classDeclarationScopes.put(cd, currentScope);
        // Resolve every annotation even when no construction or decoding
        // ever demands the layout. JSON target validation belongs to parse.
        for (ClassField field : cd.fields()) {
            nameResolver.resolveTypeNode(field.type());
        }
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
        if (vd.initializer() instanceof FunctionExpr) {
            for (PendingScope ps : pendingScopes) {
                if (ps.scope == currentScope) {
                    ps.remaining.remove(vd.name());
                    break;
                }
            }
        }
        Type exprType = checkContextualReadPosition(vd.initializer());
        if (exprType instanceof Type.Void) {
            voidDataError(DiagnosticCode.E3001, "Void results cannot initialize bindings",
                    vd.initializer().span(), vd.initializer(), "binding.initialize");
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
        // declaration visible early (docs/spec-v1.6.md §Name resolution —
        // Declaration order: a let declaration is visible only after its declaration
        // and only in its own block).
        for (PendingScope ps : pendingScopes) {
            if (ps.scope == currentScope) {
                ps.remaining.remove(vd.name());
                break;
            }
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
                    // (docs/spec-v1.6.md §Nominal typing and
                    // §Equality and comparison operand rules: class types
                    // are equal by declared class symbol only).
                    Symbol sym = currentScope.resolve(vd.name());
                    if (sym instanceof Symbol.VariableSymbol vs && vs.type() == null) {
                        Symbol.ClassSymbol provenance = vs.declaredClass() != null
                            ? vs.declaredClass() : initDeclaredClass;
                        currentScope.defineReplace(vd.name(),
                            new Symbol.VariableSymbol(vd.name(), targetType, false,
                                provenance, vs.declaredType(), vs.isConst(), vs.declarationRange()));
                    }
                }
            }
            if (targetType != null) {
                int index = diagnostics.size();
                checkAssignment(targetType, exprType, vd.initializer().span(), vd.typeAnnotation().orElse(null));
                if (diagnostics.size() > index)
                    diagnostics.set(index, withReadEvidence(diagnostics.get(index), vd.initializer()));
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
        Symbol binding = currentScope.resolveLocal(vd.name());
        if (targetType != null && exprType != Type.Error.INSTANCE && isAssignable(targetType, exprType))
            refineAssignedBinding(binding, targetType, exprType);
    }

    /**
     * Declaration bookkeeping: a new binding neither inherits a
     * same-named binding's narrowing nor assigns an enclosing binding, so it is never
     * recorded as a loop-body or try-block assignment — the revert of an
     * open loop or try statement is keyed by the resolved binding, and a
     * declaration is a different binding.
     */
    private void recordDeclaration(String varName) {
        narrowing.onAssignment(currentScope.resolveLocal(varName));
    }

    private void refineAssignedBinding(Symbol binding, Type target, Type value) {
        Type stored = target instanceof Type.Func && value instanceof Type.Func
            && !Types.equals(target, value) ? target : value;
        narrowing.narrow(binding, stored);
    }

    /**
     * Assignment bookkeeping shared by assignment expressions: the
     * old refinement is cleared, and the resolved binding is
     * recorded for the enclosing loop (a loop back-edge invalidates the
     * entry for the code after the loop) and for the open try blocks of
     * the current function (after a try/catch every binding assigned
     * inside the try region reverts to its declared type). The binding is
     * identified by its resolved symbol, never by name: a loop-local or
     * try-local declaration's same-named binding is not an assignment to
     * the enclosing binding.
     */
    private void recordAssignment(String varName) {
        Symbol binding = resolveVisibleBinding(varName);
        if (binding == null) return;
        narrowing.onAssignment(binding);
        for (NullNarrowing entry : tryEntryFacts) entry.invalidate(binding);
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
        TypeNode savedReturnAnnotation = currentReturnAnnotation;
        currentReturnAnnotation = fd.returnType();
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
        Deque<NullNarrowing> savedTryEntryFacts = tryEntryFacts;
        tryEntryFacts = new ArrayDeque<>();
        Deque<NullNarrowing> savedLoopSavedNarrowing = loopSavedNarrowing;
        loopSavedNarrowing = new ArrayDeque<>();
        int savedLoopDepth = loopDepth;
        loopDepth = 0;
        walkStatement(fd.body());
        narrowing = savedNarrowing;
        loopAssignments = savedLoopAssignments;
        tryAssignments = savedTryAssignments;
        tryEntryFacts = savedTryEntryFacts;
        loopSavedNarrowing = savedLoopSavedNarrowing;
        loopDepth = savedLoopDepth;

        if (funcType != null && !fd.isExternal()) {
            // One definite-return rule serves every compile surface: only
            // void admits normal completion; null is an explicit data result.
            // Analyze reachable statements, including unconditional-return loops.
            Type retType = funcType.returnType();
            if (!(retType instanceof Type.Void)
                    && !ReturnAnalysis.definitelyReturns(fd.body())) {
                diagnostics.add(missingReturnDiagnostic(
                    "Function '" + fd.name() + "' must return a value on all paths",
                    fd.span(), retType, fd.returnType(), fd.body()));
            }
        }

        currentReturnType = savedReturnType;
        currentReturnAnnotation = savedReturnAnnotation;
        currentReturnContext = savedReturnContext;
        insideAsyncFunction = savedInsideAsync;
    }

    // =======================================================================
    // Return statement
    // =======================================================================

    private CompilerDiagnostic missingReturnDiagnostic(String message, Span span, Type result, TypeNode annotation, Block body) {
        CompilerDiagnostic diagnostic = CompilerDiagnostic.error(DiagnosticCode.E5002, message, span).withFacts(
                new deal.diagnostics.DiagnosticFacts("return.not-definite", "return.flow",
                        typeName(result), "not all paths return or throw",
                        List.of("Implement the intended result on all required paths; no dummy return or result widening is inferred. "
                                + "See docs/spec-v1.6.md#definite-return-analysis and #return-type-requirements; "
                                + "schematic examples: if (condition) { return chosen; } else { return other; }, "
                                + "or throw an explicitly chosen Error. Choose result values and failure semantics yourself.")))
                .withNote("Declared result annotation", annotation.span().range());
        ReturnAnalysis.ReturnObligation obligation = ReturnAnalysis.unmetObligation(body);
        return obligation == null ? diagnostic
            : diagnostic.withNote(obligation.explanation(), obligation.span().range());
    }

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

            if (currentReturnType instanceof Type.Void || !isAssignable(currentReturnType, exprType)) {
                if (!reportClassProviderCrossing(currentReturnType, exprType,
                        rs.expr().get().span())) {
                    diagnostics.add(withReadEvidence(CompilerDiagnostic.error(DiagnosticCode.E5003,
                        "Return type mismatch: expected " + typeName(currentReturnType)
                        + ", got " + typeName(exprType), rs.expr().get().span()).withFacts(
                                new deal.diagnostics.DiagnosticFacts("return.incompatible", "return",
                                        typeName(currentReturnType), typeName(exprType), List.of())
                                        .withMismatch(typeMismatch(currentReturnType, exprType)))
                                .withNote("Declared result annotation", currentReturnAnnotation.span().range()), rs.expr().get()));
                }
            }
        } else {
            if (currentReturnType != null && !(currentReturnType instanceof Type.Void)) {
                diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E5003,
                    "Return type mismatch: expected " + typeName(currentReturnType)
                    + ", got undefined", rs.span()).withFacts(new deal.diagnostics.DiagnosticFacts(
                            "return.missing-value", "return", typeName(currentReturnType), "undefined",
                            List.of("Provide the intended result; no placeholder value is inferred.")))
                            .withNote("Declared result annotation", currentReturnAnnotation.span().range()));
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

        // The condition's true/false states include short-circuit effects.
        narrowCondition(is.condition(), true);
        walkStatement(is.thenBlock());
        NullNarrowing thenNarrowing = narrowing;

        narrowCondition(is.condition(), false);
        if (is.elseBranch().isPresent()) {
            switch (is.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left ->
                    checkIfStatement(left.value());
                case Either.Right<IfStatement, Block> right ->
                    walkStatement(right.value());
            }
        }
        NullNarrowing elseNarrowing = narrowing;
        boolean thenStops = ReturnAnalysis.cannotCompleteNormally(is.thenBlock());
        boolean elseStops = is.elseBranch().isPresent() && switch (is.elseBranch().get()) {
            case Either.Left<IfStatement, Block> left -> ReturnAnalysis.cannotCompleteNormally(left.value());
            case Either.Right<IfStatement, Block> right -> ReturnAnalysis.cannotCompleteNormally(right.value());
        };
        if (thenStops && !elseStops) narrowing = elseNarrowing;
        else if (elseStops && !thenStops) narrowing = thenNarrowing;
        else narrowing = mergeFlows(thenNarrowing, elseNarrowing);
    }

    /** Apply nominal tests with the same branch polarity as nullish/typeof tests. */
    private void narrowCondition(ExpressionNode condition, boolean matching) {
        ConditionFlow flow = conditionFlows.get(condition);
        if (flow != null) {
            narrowing = (matching ? flow.whenTrue() : flow.whenFalse()).branch();
            return;
        }
        refineCondition(condition, matching);
    }

    private void refineCondition(ExpressionNode condition, boolean matching) {
        narrowing.onCondition(condition, matching, this::guardSubject, this::guardDeclaredType);
        while (condition instanceof UnaryExpr unary && unary.op() == UnaryOp.NOT) {
            condition = unary.expr();
            matching = !matching;
        }
        if (matching && condition instanceof InstanceofExpr test) {
            Type.Class target = instanceofTargets.get(test);
            if (target != null) narrowing.narrow(guardSubject(test.value()), target);
        }
    }

    private NullNarrowing.Subject propertySubject(ExpressionNode expression) {
        if (!(expression instanceof MemberAccessExpr member)
                || !(member.object() instanceof IdentifierExpr id)
                || !(typeMap.get(id) instanceof Type.Class cls)) return null;
        Symbol binding = resolveVisibleBinding(id.name());
        if (!(binding instanceof Symbol.VariableSymbol)) return null;
        Symbol.ClassSymbol declaration = exactReceiverClassSymbol(id, cls);
        if (declaration == null) declaration = resolveClassSymbol(cls);
        ClassField field = declaration == null ? null
            : IntrinsicResolvers.findField(declaration.fields(), member.field());
        return field == null ? null : new NullNarrowing.Subject(binding, field);
    }

    private NullNarrowing.Subject guardSubject(ExpressionNode expression) {
        if (expression instanceof IdentifierExpr id)
            return new NullNarrowing.Subject(resolveVisibleBinding(id.name()), null);
        return propertyReadEpochs.get(expression) == narrowing.propertyEpoch()
            ? propertySubject(expression) : null;
    }

    private Type guardDeclaredType(ExpressionNode expression) {
        return expression instanceof IdentifierExpr id ? declaredTypeOf(id.name())
            : declaredReadTypes.get(expression);
    }

    private void invalidatePropertyNarrowing() {
        narrowing.invalidateProperties();
        for (NullNarrowing entry : tryEntryFacts) entry.invalidateProperties();
        for (NullNarrowing saved : loopSavedNarrowing) saved.invalidateProperties();
    }

    // =======================================================================
    // While statement
    // =======================================================================

    private void checkWhileStatement(WhileStatement ws) {
        // The first guard consumes incoming refinements (and validates stale
        // captured values). Only guard-derived facts enter the fresh body;
        // unrelated incoming facts must not cross the loop back-edge.
        NullNarrowing savedNarrowing = narrowing;
        loopSavedNarrowing.push(savedNarrowing);
        narrowing = narrowing.branch();
        loopAssignments.push(newBindingAssignmentSet());
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

        narrowCondition(ws.condition(), true);
        NullNarrowing freshGuard = freshGuardFlow(ws.condition(), true, narrowing.emptyBranch());
        narrowing.retainRefinements(freshGuard);
        loopDepth++;
        walkStatement(ws.body());
        // The loop back-edge invalidates every binding assigned in the
        // body (v1.2 narrowing does not cross loop back-edges).
        Set<Symbol> assigned = loopAssignments.pop();
        loopDepth--;
        loopSavedNarrowing.pop();
        closeLoopAssignments(assigned, savedNarrowing);
        resetRepeatedGuardReads(ws.condition(), assigned);
        narrowing = savedNarrowing;
    }

    /** A repeated guard must test the current value, not its first-entry fact. */
    private void resetRepeatedGuardReads(ExpressionNode expression, Set<Symbol> assigned) {
        if (expression instanceof UnaryExpr unary) {
            if (unary.op() == UnaryOp.TYPEOF) resetGuardSubject(unary.expr(), assigned);
            else resetRepeatedGuardReads(unary.expr(), assigned);
        } else if (expression instanceof InstanceofExpr test) {
            resetGuardSubject(test.value(), assigned);
        } else if (expression instanceof BinaryExpr binary) {
            if (binary.op() == BinaryOp.EQ || binary.op() == BinaryOp.NEQ) {
                if (isNullishGuardLiteral(binary.left())) resetGuardSubject(binary.right(), assigned);
                if (isNullishGuardLiteral(binary.right())) resetGuardSubject(binary.left(), assigned);
            }
            resetRepeatedGuardReads(binary.left(), assigned);
            resetRepeatedGuardReads(binary.right(), assigned);
        }
    }

    private static boolean isNullishGuardLiteral(ExpressionNode expression) {
        return expression instanceof LiteralExpr literal && literal.value() instanceof LiteralValue.NullLiteral
            || expression instanceof IdentifierExpr id && id.name().equals("undefined");
    }

    private void resetGuardSubject(ExpressionNode expression, Set<Symbol> assigned) {
        NullNarrowing.Subject subject = expression instanceof IdentifierExpr id
            ? new NullNarrowing.Subject(resolveVisibleBinding(id.name()), null) : propertySubject(expression);
        if (subject == null || subject.field() == null && !assigned.contains(subject.binding())) return;
        Type declared = guardDeclaredType(expression);
        if (declared != null) {
            typeMap.put(expression, declared);
            narrowedReads.remove(expression);
        }
    }

    /** Guard-only facts, intersected with evaluated outcomes before body entry.
     * This cannot resurrect assignment/await-invalidated facts, or carry an
     * unrelated incoming refinement across a back-edge. */
    private NullNarrowing freshGuardFlow(ExpressionNode condition, boolean matching, NullNarrowing entry) {
        if (condition instanceof UnaryExpr unary && unary.op() == UnaryOp.NOT)
            return freshGuardFlow(unary.expr(), !matching, entry);
        if (condition instanceof BinaryExpr binary
                && (binary.op() == BinaryOp.AND || binary.op() == BinaryOp.OR)) {
            boolean and = binary.op() == BinaryOp.AND;
            NullNarrowing selected = freshGuardFlow(binary.left(), and, entry.branch());
            if (matching == and) return freshGuardFlow(binary.right(), matching, selected);
            return mergeFlows(freshGuardFlow(binary.left(), !and, entry.branch()),
                freshGuardFlow(binary.right(), matching, selected));
        }
        entry.onCondition(condition, matching, this::guardSubject, this::guardDeclaredType);
        if (matching && condition instanceof InstanceofExpr test) {
            Type.Class target = instanceofTargets.get(test);
            if (target != null) entry.narrow(guardSubject(test.value()), target);
        }
        return entry;
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
                saved.invalidate(binding);
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
                new Symbol.VariableSymbol(fos.varName(), varType, false, null, null, fos.isConst(), fos.span().range()));
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
        NullNarrowing tryEntry = narrowing.branch();
        tryEntryFacts.push(tryEntry);
        walkStatement(ts.tryBlock());
        tryEntryFacts.pop();
        tryAssignments.pop();
        NullNarrowing normalExit = narrowing;
        narrowing = tryEntry;
        for (Symbol binding : assigned) {
            if (resolveVisibleBinding(afterCatchScope, binding.name()) == binding)
                narrowing.invalidate(binding);
        }

        // A catch identifier owns independent facts; it never restores or
        // replaces a same-spelled enclosing binding's refinement.
        walkStatement(ts.catchBlock());
        if (catchScope != null) narrowing.invalidate(catchScope.resolveLocal(ts.catchVar()));
        boolean tryStops = ReturnAnalysis.cannotCompleteNormally(ts.tryBlock());
        boolean catchStops = ReturnAnalysis.cannotCompleteNormally(ts.catchBlock());
        if (!tryStops) narrowing = catchStops ? normalExit : mergeFlows(normalExit, narrowing);

        closeTryAssignments(assigned, afterCatchScope);
    }

    /**
     * Closes one try statement's assignment frame
     * (docs/spec-v1.6.md §Narrowing across try/catch: after a try/catch
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
                narrowing.invalidate(binding);
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
        ExpressionNode target = ds.target() instanceof OptionalChainExpr chain ? chain.expression() : ds.target();
        if (target instanceof IndexExpr index && typeOf(index.array()) instanceof Type.Array) {
            mutationError(DiagnosticCode.E3017, "Array elements cannot be deleted", ds.span(),
                    "mutation.array-delete", "delete", "array elements retained", "array element deletion", null);
        }
        assignmentTargetMode = savedMode;

        if (targetType instanceof Type.Error) return;

        if (target instanceof MemberAccessExpr mae) {
            Type objType = typeOf(mae.object());
            // D3 (typed-boundary-enforcement): array .length is read-only.
            // Defensive rejection — codegen would otherwise emit invalid
            // `#xs = nil` Lua for a program the checker had accepted.
            if ((objType instanceof Type.Array || objType instanceof Type.String) && mae.field().equals("length")) {
                mutationError(DiagnosticCode.E3017, "Length is read-only", ds.target().span(),
                        "mutation.length-readonly", "delete", "read-only length", typeName(objType), null);
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
                        mutationError(DiagnosticCode.E4004,
                            "Cannot delete required field '" + mae.field() + "'", ds.span(),
                            "mutation.required-field-delete", "delete", "required field presence", mae.field(),
                            field.span().range());
                    }
                }
            }
            // Table delete: any field is allowed (no further check needed)
        }
        invalidatePropertyNarrowing();
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
        // (docs/spec-v1.6.md §Reading and writing fields, whose allowed contexts
        // name the position's whole expression E, never a subexpression).
        boolean direct = pendingContextualReadPosition;
        pendingContextualReadPosition = false;
        boolean savedDirect = contextualReadPosition;
        contextualReadPosition = direct;
        try {
            conditionFlows.remove(expr);
            Type t = computeExpressionType(expr);
            if (expr instanceof CallExpr || expr instanceof HasExpr) invalidatePropertyNarrowing();
            if (t instanceof Type.Boolean && !conditionFlows.containsKey(expr)) {
                NullNarrowing after = narrowing;
                narrowing = after.branch();
                refineCondition(expr, true);
                NullNarrowing whenTrue = narrowing;
                narrowing = after.branch();
                refineCondition(expr, false);
                conditionFlows.put(expr, new ConditionFlow(whenTrue, narrowing));
                narrowing = after;
                if (expr instanceof UnaryExpr unary && unary.op() == UnaryOp.NOT) {
                    ConditionFlow operand = conditionFlows.get(unary.expr());
                    if (operand != null) conditionFlows.put(expr,
                        new ConditionFlow(operand.whenFalse(), operand.whenTrue()));
                }
            }
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
     * position (docs/spec-v1.6.md §Reading and writing fields): the position's
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

    /** Receiver and key subexpressions are reads even inside a storage target. */
    private Type checkAddressOperand(ExpressionNode expr) {
        boolean savedMode = assignmentTargetMode;
        assignmentTargetMode = false;
        try { return checkExpression(expr); }
        finally { assignmentTargetMode = savedMode; }
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
            case OptionalGuardExpr guard -> checkOptionalGuard(guard);
            case OptionalChainExpr chain -> checkOptionalChain(chain);
            case LiteralExpr lit       -> checkLiteral(lit);
            case IdentifierExpr id     -> checkIdentifier(id);
            case AssertionExpr assertion -> checkAssertion(assertion);
            case InstanceofExpr test -> checkInstanceof(test);
            case BinaryExpr bin        -> checkBinary(bin);
            case ConditionalExpr conditional -> checkConditional(conditional);
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
                    // The interpolation's string/number requirement is the whole
                    // context of an embedded expression (docs/spec-v1.6.md
                    // §Template literals): it grants no contextual-read
                    // position, and an already-rejected part is not
                    // re-reported against it.
                    if (i % 2 == 1 && partType != Type.Error.INSTANCE
                            && !(partType instanceof Type.String)
                            && !(partType instanceof Type.Number)) {
                        error(DiagnosticCode.E3016,
                            "Template literal interpolation must have type string or number, got "
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
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E3012,
                "'await' is only allowed inside an async function", await.span()).withFacts(
                        new deal.diagnostics.DiagnosticFacts("await.outside-async", "await",
                                "async function body", "synchronous function body",
                                List.of("Choose whether the enclosing API should be asynchronous; changing its result contract affects callers."))));
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
            invalidateAllNarrowing(await.span().range());
            return Type.Error.INSTANCE;
        }

        Type calleeFuncType = typeOf(call.callee());

        if (calleeFuncType instanceof Type.Func f && f.isAsync()) {
            // Valid await: resultType is the return type R of the async function.
            // Invalidate narrowing after await.
            invalidateAllNarrowing(await.span().range());
            return resultType;
        }

        if (calleeFuncType != Type.Error.INSTANCE) {
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E3013,
                "'await' must be applied to an async function call", await.callee().span()).withFacts(
                        new deal.diagnostics.DiagnosticFacts("await.non-async-call", "await",
                                "direct async function call", typeName(calleeFuncType), List.of())));
        }
        invalidateAllNarrowing(await.span().range());
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
    private void invalidateAllNarrowing(deal.diagnostics.DiagnosticRange awaitRange) {
        narrowing.invalidateAtAwait(awaitRange);
        for (NullNarrowing entry : tryEntryFacts) entry.invalidateAtAwait(awaitRange);
        for (NullNarrowing saved : loopSavedNarrowing) {
            saved.invalidateAtAwait(awaitRange);
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
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E2001,
                    "Undeclared identifier '" + id.name() + "'", id.span())
                    .withMissingSymbol("VALUE", id.name(), id.span().file(), List.of())
                    .withFacts(new deal.diagnostics.DiagnosticFacts("name.unresolved", "read",
                            "visible value binding", id.name(), List.of("Check the binding's spelling, scope and imports.",
                                    "Listed alternatives are visible, kind/context-compatible bindings, not authorized edits."))
                            .withNames(nameAlternatives(id.name(),
                                    directBuiltinCall != null && directBuiltinCall.callee() == id ? directBuiltinCall : null))));
            return Type.Error.INSTANCE;
        }

        if (sym instanceof Symbol.IntrinsicSymbol && List.of("String", "Number").contains(id.name())) {
            directOnlyError("Builtin operations support direct calls only", id.span(), id.name(), "call.builtin-direct-only");
            return Type.Error.INSTANCE;
        }
        if (sym instanceof Symbol.ModuleSymbol || sym instanceof Symbol.ClassSymbol) {
            error(DiagnosticCode.E3001, "Namespace/class metadata is not a data value", id.span());
            return Type.Error.INSTANCE;
        }
        Type declaredType = getDeclaredType(sym);
        Type narrowedType = narrowing.getNarrowedType(sym);
        recordLostRefinement(id, new NullNarrowing.Subject(sym, null), declaredType);
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

    private NullNarrowing mergeFlows(NullNarrowing left, NullNarrowing right) {
        NullNarrowing merged = left.branch();
        merged.merge(right);
        return merged;
    }

    private Type checkLogical(BinaryExpr bin) {
        Type savedExpected = expectedType;
        expectedType = null;
        Type left;
        Type right;
        boolean and = bin.op() == BinaryOp.AND;
        try {
            left = checkExpression(bin.left());
            NullNarrowing afterLeft = narrowing.branch();
            narrowCondition(bin.left(), !and);
            NullNarrowing skipped = narrowing;
            narrowing = afterLeft;
            narrowCondition(bin.left(), and);
            right = checkExpression(bin.right());
            NullNarrowing afterRight = narrowing.branch();
            narrowCondition(bin.right(), true);
            NullNarrowing rightTrue = narrowing;
            narrowing = afterRight;
            narrowCondition(bin.right(), false);
            NullNarrowing rightFalse = narrowing;
            ConditionFlow flow = and
                ? new ConditionFlow(rightTrue, mergeFlows(skipped, rightFalse))
                : new ConditionFlow(mergeFlows(skipped, rightTrue), rightFalse);
            conditionFlows.put(bin, flow);
            narrowing = mergeFlows(flow.whenTrue(), flow.whenFalse());
        } finally { expectedType = savedExpected; }
        if (left == Type.Error.INSTANCE || right == Type.Error.INSTANCE) return Type.Error.INSTANCE;
        if (!(left instanceof Type.Boolean) || !(right instanceof Type.Boolean)) {
            boolean badLeft = !(left instanceof Type.Boolean);
            binaryOperatorError(DiagnosticCode.E3007, (badLeft ? "Left" : "Right") + " operand of '"
                + opSymbol(bin.op()) + "' must be boolean, got " + typeName(badLeft ? left : right),
                (badLeft ? bin.left() : bin.right()).span(), bin, left, right, "operator.logical.operands",
                "boolean operands", List.of("boolean, boolean"));
            return Type.Error.INSTANCE;
        }
        return Type.Boolean.INSTANCE;
    }

    private Type checkBinary(BinaryExpr bin) {
        if (bin.op() == BinaryOp.COALESCE) return checkCoalesce(bin);
        if (bin.op() == BinaryOp.AND || bin.op() == BinaryOp.OR) return checkLogical(bin);
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
            // A refinement must not make an otherwise legal identity/nullish
            // comparison illegal (including a repeated guard). When only
            // the declared pair is eligible, these identifier reads consume
            // their declared types, not a concrete narrowed projection.
            Type leftDeclared = equalityDeclaredType(bin.left(), leftType);
            Type rightDeclared = equalityDeclaredType(bin.right(), rightType);
            boolean declaredComparison = Types.equals(leftDeclared, rightDeclared)
                || isNullableOf(leftDeclared, rightDeclared) || isNullableOf(rightDeclared, leftDeclared);
            if ((equalTypes && !(leftType instanceof Type.Void)) || nullableVsNull
                    || (declaredComparison && !(leftDeclared instanceof Type.Void))) {
                if (!equalTypes && !nullableVsNull && declaredComparison) {
                    typeMap.put(bin.left(), leftDeclared);
                    typeMap.put(bin.right(), rightDeclared);
                    narrowedReads.remove(bin.left());
                    narrowedReads.remove(bin.right());
                }
                return Type.Boolean.INSTANCE;
            }
            binaryOperatorError(DiagnosticCode.E3006,
                "Cannot compare " + typeName(leftType) + " with " + typeName(rightType),
                bin.span(), bin, leftType, rightType, "operator.equality.operands",
                "equal non-void types or an eligible nullish comparison", List.of(
                        "equal non-void types", "eligible nullable or unknown value, null or undefined (either order)"));
            return Type.Error.INSTANCE;
        }

        if (op == BinaryOp.LT || op == BinaryOp.LTE || op == BinaryOp.GT || op == BinaryOp.GTE) {
            if (Types.equals(leftType, rightType)) {
                if (leftType instanceof Type.Number || leftType instanceof Type.String) {
                    return Type.Boolean.INSTANCE;
                }
            }
            binaryOperatorError(DiagnosticCode.E3007, "Invalid operand types for comparison: "
                + typeName(leftType) + " and " + typeName(rightType), bin.span(), bin, leftType, rightType,
                "operator.relational.operands", "both number or both string", List.of("number, number", "string, string"));
            return Type.Error.INSTANCE;
        }

        // For +, check special case first
        if (op == BinaryOp.ADD) {
            if (Types.equals(leftType, rightType)) {
                if (leftType instanceof Type.Number || leftType instanceof Type.String) {
                    return leftType;
                }
            }
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E3010, "Invalid operand types for '+': "
                    + typeName(leftType) + " and " + typeName(rightType), bin.span()).withFacts(
                    new deal.diagnostics.DiagnosticFacts("operator.add.operands", "addition",
                            "Both '+' operands must be numeric, or both must be string; DEAL has no implicit coercion",
                            "left: " + typeName(leftType) + "; right: " + typeName(rightType),
                            List.of("Choose operands of the same supported kind; do not use a cast as a conversion."))
                            .withOperator(binaryOperatorContext(bin, leftType, rightType, List.of("number, number", "string, string")))));
            return Type.Error.INSTANCE;
        }

        // Other arithmetic: both operands must be numbers.
        if (!Types.equals(leftType, rightType)) {
            binaryOperatorError(DiagnosticCode.E3007, "Invalid operand types for '" + opSymbol(op) + "': "
                + typeName(leftType) + " and " + typeName(rightType), bin.span(), bin, leftType, rightType,
                "operator.arithmetic.operands", "number operands", List.of("number, number"));
            return Type.Error.INSTANCE;
        }

        if (leftType instanceof Type.Number) {
            return leftType;
        }

        binaryOperatorError(DiagnosticCode.E3007, "Invalid operand types for '" + opSymbol(op) + "': "
            + typeName(leftType) + " and " + typeName(rightType), bin.span(), bin, leftType, rightType,
            "operator.arithmetic.operands", "number operands", List.of("number, number"));
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
                    voidDataError(DiagnosticCode.E3007, "Void results are not data", un.span(), un.expr(), "typeof");
                    yield Type.Error.INSTANCE;
                }
                yield Type.String.INSTANCE;
            }
            case NOT -> {
                if (!(exprType instanceof Type.Boolean)) {
                    unaryOperatorError("Operand of '!' must be boolean, got " + typeName(exprType),
                            un, exprType, "!", "boolean");
                    yield Type.Error.INSTANCE;
                }
                yield Type.Boolean.INSTANCE;
            }
            case NEG -> {
                if (!(exprType instanceof Type.Number)) {
                    unaryOperatorError("Operand of unary '-' must be number, got " + typeName(exprType),
                            un, exprType, "-", "number");
                    yield Type.Error.INSTANCE;
                }
                yield exprType;
            }
        };
    }

    // =======================================================================
    // Call expression
    // =======================================================================

    private static boolean optionalCallee(ExpressionNode expression) {
        return switch (expression) {
            case OptionalGuardExpr ignored -> true;
            case MemberAccessExpr member -> optionalCallee(member.object());
            case IndexExpr index -> optionalCallee(index.array());
            case CallExpr call -> optionalCallee(call.callee());
            default -> false;
        };
    }

    private Type checkCall(CallExpr call) {
        if (call.callee() instanceof MemberAccessExpr member
                && member.object() instanceof IdentifierExpr namespace
                && BuiltinCall.resolve(namespace.name(), member.field()) == BuiltinCall.JSON_PARSE)
            return checkJsonParse(call, member);
        if (call.callee() instanceof MemberAccessExpr member
                && member.object() instanceof IdentifierExpr namespace
                && BuiltinCall.resolve(namespace.name(), member.field()) == BuiltinCall.ARRAY_FROM) {
            return checkArrayFrom(call, member);
        }
        if (call.callee() instanceof MemberAccessExpr member && List.of("map", "find", "findIndex", "filter", "some", "every").contains(member.field())
                && !(member.object() instanceof IdentifierExpr id
                    && resolveVisibleBinding(id.name()) instanceof Symbol.ModuleSymbol)) {
            Type receiver = checkAddressOperand(member.object());
            if (receiver instanceof Type.Array array) return member.field().equals("map")
                ? checkArrayMap(call, member, array) : checkArrayPredicate(call, member, array);
        }
        if (call.callee() instanceof IdentifierExpr id && List.of("String", "Number").contains(id.name())
                && resolveVisibleBinding(id.name()) instanceof Symbol.IntrinsicSymbol builtin) {
            typeMap.put(id, builtin.type());
            List<Type> arguments = new ArrayList<>();
            Type savedExpected = expectedType;
            expectedType = null;
            try {
                for (ExpressionNode argument : call.args()) arguments.add(checkExpression(argument));
            } finally { expectedType = savedExpected; }
            return checkFunctionCall(call, (Type.Func) builtin.type(), arguments);
        }
        if (call.callee() instanceof IdentifierExpr id && currentScope.resolve(id.name()) == null) {
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E2001,
                    "Undeclared identifier '" + id.name() + "'", id.span())
                    .withMissingSymbol("FUNCTION", id.name(), id.span().file(),
                            List.of("arity=" + call.args().size()))
                    .withFacts(new deal.diagnostics.DiagnosticFacts("name.unresolved-call", "call",
                            "visible callable binding", id.name(),
                            List.of("Check spelling, lexical scope, and intended imports; no callable declaration is inferred.",
                                    "Listed call alternatives match arity, async form and known result context; argument compatibility is not established."))
                            .withNames(nameAlternatives(id.name(), call))));
            for (ExpressionNode arg : call.args()) checkExpression(arg);
            return Type.Error.INSTANCE;
        }
        CallExpr savedBuiltinCall = directBuiltinCall;
        directBuiltinCall = call;
        Type calleeType;
        try { calleeType = checkExpression(call.callee()); }
        finally { directBuiltinCall = savedBuiltinCall; }
        if (optionalCallee(call.callee()) && calleeType instanceof Type.Func f && f.isAsync()) {
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E3014,
                    "Async calls are not permitted in optional chains", call.span()).withFacts(
                            callFacts("call.async-optional-chain", call, f, -1,
                                    "non-optional direct async call", typeName(f))));
            for (ExpressionNode arg : call.args()) checkExpression(arg);
            return Type.Error.INSTANCE;
        }
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

        if (call.callee() instanceof MemberAccessExpr member
                && member.object() instanceof IdentifierExpr namespace
                && BuiltinCall.resolve(namespace.name(), member.field()) == BuiltinCall.OBJECT_KEYS
                && argTypes.size() == 1) {
            Type argument = argTypes.getFirst();
            if (!(argument instanceof Type.Table) && !(argument instanceof Type.Class)
                    && argument != Type.Error.INSTANCE)
                error(DiagnosticCode.E5001, "Object.keys requires a Record or class instance, got "
                    + typeName(argument), call.args().getFirst().span());
        }

        // Intrinsic call? The callee's own visible binding decides: a
        // lexically shadowing local, parameter or enclosing-block binding
        // — and a same-block `let` that is not declared yet
        // (docs/spec-v1.6.md §Name resolution — Declaration order) —
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
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E3014,
                "Direct call to async function requires 'await'", call.span()).withFacts(
                        callFacts("call.async-requires-await", call, funcType, -1,
                                "awaited direct async call", typeName(funcType))));
            return Type.Error.INSTANCE;
        }

        if (calleeType instanceof Type.Func funcType) {
            return checkFunctionCall(call, funcType, argTypes);
        }

        diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E3008,
                typeName(calleeType) + " is not callable", call.callee().span())
                .withFacts(new deal.diagnostics.DiagnosticFacts("call.non-callable", "call", "function value", typeName(calleeType),
                        List.of("Choose a declared callable or explicitly narrow/extract an unknown callable under its intended contract. "
                                + "No function declaration, signature, conversion or replacement name is inferred. "
                                + "See docs/spec-v1.6.md#function-values-calls-and-wrappers."))));
        return Type.Error.INSTANCE;
    }

    private Type checkJsonParse(CallExpr call, MemberAccessExpr member) {
        Type target = expectedType == null ? Type.Unknown.INSTANCE : expectedType;
        Symbol.ClassSymbol declaration = contextualClassDecl;
        typeMap.put(member.object(), Type.Table.INSTANCE);
        typeMap.put(member, BuiltinCall.JSON_PARSE.signature());
        Type savedExpected = expectedType;
        Symbol.ClassSymbol savedContext = contextualClassDecl;
        List<Type> arguments = new ArrayList<>();
        expectedType = Type.String.INSTANCE;
        contextualClassDecl = null;
        try {
            for (ExpressionNode argument : call.args()) arguments.add(checkContextualReadPosition(argument));
        } finally {
            expectedType = savedExpected;
            contextualClassDecl = savedContext;
        }
        if (checkFunctionCall(call, BuiltinCall.JSON_PARSE.signature(), arguments) == Type.Error.INSTANCE)
            return Type.Error.INSTANCE;
        record SchemaWork(Type type, SchemaRoute route, Span declaration) {}
        java.util.Deque<SchemaWork> pending = new java.util.ArrayDeque<>();
        // Host declarations may resolve to fresh symbol wrappers on every
        // lookup. Guard the exact nominal/provider pair, not wrapper
        // allocation identity (nor an identity-only/first-alias lookup).
        java.util.Set<Type.Class> visited = new java.util.HashSet<>();
        pending.push(new SchemaWork(target, null, null));
        while (!pending.isEmpty()) {
            SchemaWork work = pending.pop();
            Type type = work.type();
            if (type instanceof Type.Func || type instanceof Type.Void) {
                CompilerDiagnostic diagnostic = CompilerDiagnostic.error(DiagnosticCode.E5001,
                        "JSON.parse target must not contain function or void types", call.span())
                        .withFacts(new deal.diagnostics.DiagnosticFacts("json.schema.forbidden-type", "json.decode.schema",
                                "a schema without function or void leaves", typeName(type),
                                List.of("Choose the intended decodable schema; assertions do not provide a JSON.parse target."),
                                schemaPath(work.route())));
                if (work.declaration() != null)
                    diagnostic = diagnostic.withNote("Offending schema annotation", work.declaration().range());
                diagnostics.add(diagnostic);
                return Type.Error.INSTANCE;
            }
            if (type instanceof Type.Nullable nullable)
                pending.push(new SchemaWork(nullable.inner(), work.route(), work.declaration()));
            else if (type instanceof Type.Array array)
                pending.push(new SchemaWork(array.element(), new SchemaRoute(work.route(),
                        new deal.diagnostics.DiagnosticFacts.PathSegment(
                                deal.diagnostics.DiagnosticFacts.PathKind.ARRAY_ELEMENT, "")), work.declaration()));
            else if (type instanceof Type.Class cls) {
                Symbol.ClassSymbol symbol = declaration != null && declaration.identity().equals(cls.identity())
                    && providerAgrees(declaration, cls) ? declaration : resolveClassSymbol(cls);
                if (symbol == null) {
                    diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E5001,
                            "JSON.parse target has no exact class declaration", call.span()).withFacts(
                            new deal.diagnostics.DiagnosticFacts("json.schema.missing-declaration", "json.decode.schema",
                                    "exact defining class declaration and provider", typeName(type), List.of(), schemaPath(work.route()))));
                    return Type.Error.INSTANCE;
                }
                if (visited.add(classTypeOf(symbol)))
                    for (ClassField field : symbol.fields())
                        pending.push(new SchemaWork(resolveFieldTypeInClassModule(symbol, field.type()),
                                new SchemaRoute(work.route(), new deal.diagnostics.DiagnosticFacts.PathSegment(
                                        deal.diagnostics.DiagnosticFacts.PathKind.FIELD, field.name())), field.type().span()));
            }
        }
        recordExactClassDecl(call, target, declaration);
        return target;
    }

    private record SchemaRoute(SchemaRoute parent, deal.diagnostics.DiagnosticFacts.PathSegment segment) {}

    private static List<deal.diagnostics.DiagnosticFacts.PathSegment> schemaPath(SchemaRoute route) {
        var result = new java.util.ArrayDeque<deal.diagnostics.DiagnosticFacts.PathSegment>();
        for (SchemaRoute current = route; current != null; current = current.parent()) result.addFirst(current.segment());
        return List.copyOf(result);
    }

    private Type checkArrayPredicate(CallExpr call, MemberAccessExpr member, Type.Array source) {
        Type.Func predicate = new Type.Func(List.of(source.element(), Type.Number.INSTANCE, source), Type.Boolean.INSTANCE);
        Type result = switch (member.field()) {
            case "findIndex" -> Type.Number.INSTANCE;
            case "filter" -> source;
            case "some", "every" -> Type.Boolean.INSTANCE;
            default -> Types.optionalRead(source.element());
        };
        Type.Func signature = new Type.Func(List.of(predicate), result);
        typeMap.put(member, signature);
        List<Type> arguments = new ArrayList<>();
        Type savedExpected = expectedType;
        Symbol.ClassSymbol savedContext = contextualClassDecl;
        expectedType = null;
        contextualClassDecl = null;
        try {
            for (ExpressionNode argument : call.args()) {
                expectedType = predicate;
                arguments.add(checkExpression(argument));
            }
        } finally {
            expectedType = savedExpected;
            contextualClassDecl = savedContext;
        }
        return checkFunctionCall(call, signature, arguments);
    }

    private Type checkArrayMap(CallExpr call, MemberAccessExpr member, Type.Array source) {
        List<Type> arguments = new ArrayList<>();
        Type savedExpected = expectedType;
        Symbol.ClassSymbol savedContext = contextualClassDecl;
        expectedType = null;
        contextualClassDecl = null;
        try {
            for (ExpressionNode argument : call.args()) {
                expectedType = new Type.Func(List.of(source.element(), Type.Number.INSTANCE, source), Type.Unknown.INSTANCE);
                arguments.add(checkExpression(argument));
            }
        } finally {
            expectedType = savedExpected;
            contextualClassDecl = savedContext;
        }
        if (arguments.size() != 1) {
            intrinsicCallError(DiagnosticCode.E3009, "Array.map requires exactly one argument", call, -1,
                "Array.map", "call.array-map.argument-count", "1", Integer.toString(arguments.size()), 1, "", "");
            return Type.Error.INSTANCE;
        }
        if (!(arguments.getFirst() instanceof Type.Func mapper) || mapper.isAsync()
                || mapper.returnType() instanceof Type.Void) {
            if (arguments.getFirst() != Type.Error.INSTANCE)
                intrinsicCallError(DiagnosticCode.E5001, "Array.map requires a synchronous data-result mapper", call, 0,
                    "Array.map", "call.array-map.mapper", "synchronous function with non-void result",
                    typeName(arguments.getFirst()), 1, "mapper", "");
            return Type.Error.INSTANCE;
        }
        Type.Func signature = new Type.Func(List.of(source.element(), Type.Number.INSTANCE, source), mapper.returnType());
        Type.Array result = new Type.Array(mapper.returnType());
        typeMap.put(member, new Type.Func(List.of(signature), result));
        return checkFunctionCall(call, (Type.Func) typeMap.get(member), arguments);
    }

    /** Inference is from the source and synchronous mapper, never the enclosing result. */
    private Type checkArrayFrom(CallExpr call, MemberAccessExpr member) {
        List<Type> arguments = new ArrayList<>();
        Type savedExpected = expectedType;
        Symbol.ClassSymbol savedContext = contextualClassDecl;
        expectedType = null;
        contextualClassDecl = null;
        try {
            for (ExpressionNode argument : call.args()) {
                expectedType = arguments.size() == 1 && arguments.getFirst() instanceof Type.Array source
                    ? new Type.Func(List.of(source.element(), Type.Number.INSTANCE), Type.Unknown.INSTANCE) : null;
                arguments.add(checkExpression(argument));
            }
        } finally {
            expectedType = savedExpected;
            contextualClassDecl = savedContext;
        }
        typeMap.put(member.object(), Type.Table.INSTANCE);
        if (arguments.size() != 2) {
            intrinsicCallError(DiagnosticCode.E3009, "Array.from requires exactly two arguments", call, -1,
                "Array.from", "call.array-from.argument-count", "2", Integer.toString(arguments.size()), 2, "", "");
            typeMap.put(member, BuiltinCall.ARRAY_FROM.signature());
            return Type.Error.INSTANCE;
        }
        if (!(arguments.get(0) instanceof Type.Array source)) {
            if (arguments.get(0) != Type.Error.INSTANCE)
                intrinsicCallError(DiagnosticCode.E5001, "Array.from requires an array source", call, 0,
                    "Array.from", "call.array-from.source", "array source", typeName(arguments.get(0)), 2, "source", "");
            return Type.Error.INSTANCE;
        }
        if (!(arguments.get(1) instanceof Type.Func mapper) || mapper.isAsync()
                || mapper.returnType() instanceof Type.Void) {
            if (arguments.get(1) != Type.Error.INSTANCE)
                intrinsicCallError(DiagnosticCode.E5001, "Array.from requires a synchronous data-result mapper", call, 1,
                    "Array.from", "call.array-from.mapper", "synchronous function with non-void result",
                    typeName(arguments.get(1)), 2, "mapper", "");
            return Type.Error.INSTANCE;
        }
        Type.Func signature = new Type.Func(List.of(source.element(), Type.Number.INSTANCE), mapper.returnType());
        if (!isAssignable(signature, mapper)) {
            intrinsicCallError(DiagnosticCode.E5001, "Array.from mapper must accept the source element and number index", call, 1,
                "Array.from", "call.array-from.mapper-parameters", typeName(signature), typeName(mapper), 2, "mapper",
                typeName(new Type.Func(List.of(source, signature), new Type.Array(mapper.returnType()))));
            int last = diagnostics.size() - 1;
            CompilerDiagnostic diagnostic = diagnostics.get(last);
            diagnostics.set(last, diagnostic.withFacts(diagnostic.facts().withMismatch(typeMismatch(signature, mapper))));
            return Type.Error.INSTANCE;
        }
        Type.Array result = new Type.Array(mapper.returnType());
        typeMap.put(member, new Type.Func(List.of(source, signature), result));
        return result;
    }

    /** Intrinsic pre-signature rejection: retain only facts already selected by its producer. */
    private void intrinsicCallError(DiagnosticCode code, String message, CallExpr call, int argument,
            String operation, String reason, String expected, String actual, int expectedCount,
            String parameterName, String signature) {
        var facts = new deal.diagnostics.DiagnosticFacts(reason, operation, expected, actual, List.of(
                "Choose the intended source and synchronous callback contract explicitly; no mapper result, conversion or enclosing result type is inferred."))
            .withCall(new deal.diagnostics.DiagnosticFacts.CallContext(operation, signature, null, argument,
                parameterName, expectedCount, call.args().size(), null));
        CompilerDiagnostic diagnostic = CompilerDiagnostic.error(code, message,
            argument < 0 ? call.span() : call.args().get(argument).span()).withFacts(facts);
        diagnostics.add(diagnostic);
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
        if (callee == null || callee.declaration() == null) {
            return null;
        }
        List<Symbol.ClassSymbol> decls = new ArrayList<>();
        for (Parameter parameter : callee.declaration().params()) {
            TypeNode paramNode = parameter.type();
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
        while (callee instanceof OptionalGuardExpr guard) callee = guard.value();
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

    private deal.diagnostics.DiagnosticFacts callFacts(String reason, CallExpr call, Type.Func signature,
                                                      int argument, String expected, String actual) {
        Symbol.FunctionSymbol declaration = calleeDeclarationOf(call);
        String name = declaration == null ? intrinsicCalleeName(call) : declaration.name();
        FunctionDeclaration source = declaration == null ? null : declaration.declaration();
        Parameter parameter = source != null && argument >= 0 && argument < source.params().size()
            ? source.params().get(argument) : null;
        return new deal.diagnostics.DiagnosticFacts(reason, "call", expected, actual, List.of(
                "Match the selected callable's declared parameter count and types using explicitly chosen arguments; "
                        + "no omitted value, coercion or signature change is inferred. "
                        + "See docs/spec-v1.6.md#function-values-calls-and-wrappers and #function-type-compatibility."))
                .withCall(new deal.diagnostics.DiagnosticFacts.CallContext(name, typeName(signature),
                        source == null ? null : source.span().range(), argument,
                        parameter == null ? "" : parameter.name(), signature.paramTypes().size(),
                        call.args().size(), parameter == null ? null : parameter.type().span().range()));
    }

    /** Name an intrinsic only after its receiver/namespace was actually selected by this check. */
    private String intrinsicCalleeName(CallExpr call) {
        ExpressionNode callee = call.callee();
        while (callee instanceof OptionalGuardExpr guard) callee = guard.value();
        if (callee instanceof IdentifierExpr id && id.name().equals("String")
                && resolveVisibleBinding(id.name()) instanceof Symbol.IntrinsicSymbol) return "String";
        if (!(callee instanceof MemberAccessExpr member)) return "";
        if (member.object() instanceof IdentifierExpr namespace
                && typeMap.get(member.object()) instanceof Type.Table
                && !(resolveVisibleBinding(namespace.name()) instanceof Symbol.ModuleSymbol)
                && BuiltinCall.resolve(namespace.name(), member.field()) != null)
            return namespace.name() + "." + member.field();
        Type receiver = typeMap.get(member.object());
        if (receiver instanceof Type.Array && List.of("map", "find", "findIndex", "filter", "some", "every",
                "push", "pop", "splice", "slice", "includes", "indexOf", "join").contains(member.field()))
            return "Array." + member.field();
        if (receiver instanceof Type.String && StringIntrinsic.forMember(member.field()) != null)
            return "String." + member.field();
        return "";
    }

    private Type checkFunctionCall(CallExpr call, Type.Func funcType, List<Type> argTypes) {
        int paramCount = funcType.paramTypes().size();

        // DEAL v1.2: fixed arity only — function types carry no rest arm.
        if (argTypes.size() != paramCount) {
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E3009,
                "Function argument count mismatch: expected " + paramCount
                + ", got " + argTypes.size(), call.span()).withFacts(callFacts(
                        "call.argument-count", call, funcType, -1,
                        Integer.toString(paramCount), Integer.toString(argTypes.size()))));
            return Type.Error.INSTANCE;
        }

        for (int i = 0; i < paramCount; i++) {
            Type paramType = funcType.paramTypes().get(i);
            Type argType = argTypes.get(i);
            if (argType == Type.Error.INSTANCE) continue;
            if (!isAssignable(paramType, argType)) {
                if (!reportClassProviderCrossing(paramType, argType,
                        call.args().get(i).span())) {
                    var diagnostic = CompilerDiagnostic.error(DiagnosticCode.E5001,
                        "Function argument type mismatch at position " + (i + 1)
                        + ": expected " + typeName(paramType)
                        + ", got " + typeName(argType), call.args().get(i).span())
                        .withFacts(callFacts("call.argument-type", call, funcType, i,
                                typeName(paramType), typeName(argType))
                                .withMismatch(typeMismatch(paramType, argType)));
                    var parameterRange = diagnostic.facts().call().parameterRange();
                    if (parameterRange != null) diagnostic = diagnostic.withNote("Expected parameter annotation", parameterRange);
                    diagnostics.add(withReadEvidence(diagnostic, call.args().get(i)));
                }
            }
        }

        return funcType.returnType();
    }

    // =======================================================================
    // Member access
    // =======================================================================

    private void directOnlyError(String message, Span span, String selected, String reason) {
        diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E3001, message, span).withFacts(
                new deal.diagnostics.DiagnosticFacts(reason, "call.form", "direct invocation", selected, List.of(
                        "This selected builtin/receiver operation is not a first-class function value. "
                                + "If a callable value is intended, explicitly declare a wrapper with chosen parameter/result annotations "
                                + "and invoke the operation directly inside it; no wrapper signature or capture behavior is inferred. "
                                + "See docs/spec-v1.6.md#function-values-calls-and-wrappers."))));
    }

    private Type checkMemberAccess(MemberAccessExpr mae) {
        if (mae.object() instanceof IdentifierExpr builtinNamespace) {
            BuiltinCall builtin = BuiltinCall.resolve(builtinNamespace.name(), mae.field());
            if (builtin != null) {
                if (directBuiltinCall == null || mae != directBuiltinCall.callee()) {
                    directOnlyError("Builtin operations support direct calls only", mae.span(),
                            builtinNamespace.name() + "." + mae.field(), "call.builtin-direct-only");
                    return Type.Error.INSTANCE;
                }
                typeMap.put(mae.object(), Type.Table.INSTANCE);
                return builtin.signature();
            }
        }
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
            diagnostics.add(MissingExportDiagnostic.create(ms, mae.field(), "module.member", mae.span()));
            return Type.Error.INSTANCE;
        }
        Type objType = checkAddressOperand(mae.object());
        if (objType == Type.Error.INSTANCE) return Type.Error.INSTANCE;

        String field = mae.field();

        if (objType instanceof Type.String) {
            StringIntrinsic intrinsic = StringIntrinsic.forMember(field);
            if (intrinsic != null) {
                if (directBuiltinCall == null || mae != directBuiltinCall.callee()) {
                    directOnlyError("Receiver intrinsics support direct calls only", mae.span(),
                            "String." + field, "call.receiver-direct-only");
                    return Type.Error.INSTANCE;
                }
                return intrinsic.signature(directBuiltinCall.args().size());
            }
        }
        if (objType instanceof Type.Array && List.of("map", "find", "findIndex", "filter", "some", "every").contains(field)) {
            directOnlyError("Receiver intrinsics support direct calls only", mae.span(), "Array." + field, "call.receiver-direct-only");
            return Type.Error.INSTANCE;
        }
        if (objType instanceof Type.Array array && List.of("push", "pop", "splice", "slice", "includes", "indexOf", "join").contains(field)) {
            if (directBuiltinCall == null || mae != directBuiltinCall.callee()) {
                directOnlyError("Receiver intrinsics support direct calls only", mae.span(), "Array." + field, "call.receiver-direct-only");
                return Type.Error.INSTANCE;
            }
            return switch (field) {
                case "join" -> {
                    if (!array.element().equals(Type.String.INSTANCE)) {
                        error(DiagnosticCode.E3001, "join requires a string array receiver", mae.span());
                        yield Type.Error.INSTANCE;
                    }
                    yield new Type.Func(List.of(Type.String.INSTANCE), Type.String.INSTANCE, false);
                }
                case "push" -> new Type.Func(List.of(array.element()), Type.Number.INSTANCE, false);
                case "pop" -> new Type.Func(List.of(), Types.optionalRead(array.element()), false);
                case "slice" -> new Type.Func(directBuiltinCall.args().size() == 0 ? List.of()
                    : directBuiltinCall.args().size() == 1 ? List.of(Type.Number.INSTANCE)
                    : List.of(Type.Number.INSTANCE, Type.Number.INSTANCE), array, false);
                case "includes", "indexOf" -> new Type.Func(
                    directBuiltinCall.args().size() == 1 ? List.of(array.element())
                        : List.of(array.element(), Type.Number.INSTANCE),
                    field.equals("includes") ? Type.Boolean.INSTANCE : Type.Number.INSTANCE, false);
                default -> new Type.Func(List.of(Type.Number.INSTANCE, Type.Number.INSTANCE), array, false);
            };
        }
        if ((objType instanceof Type.Array || objType instanceof Type.String) && field.equals("length")) {
            return Type.Number.INSTANCE;
        }

        // Module symbol via identifier (must be checked before Table)
        if (mae.object() instanceof IdentifierExpr id) {
            Symbol sym = currentScope.resolve(id.name());
            if (sym instanceof Symbol.ModuleSymbol ms) {
                Type exportType = ms.exports().get(field);
                if (exportType != null) return exportType;
                diagnostics.add(MissingExportDiagnostic.create(ms, field, "module.member", mae.span()));
                return Type.Error.INSTANCE;
            }
        }

        // Class receivers must already be non-nullish. Only direct
        // identifier receivers can own a guarded field refinement.
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
                        // A read of an omitted optional field yields undefined;
                        // only the outer write target keeps its declared type.
                        // The read result is the canonical optional wrap, so a
                        // field declared null reads as null | undefined.
                        // Absence never becomes explicit null.
                        fieldType = Types.optionalRead(fieldType);
                    }
                    declaredReadTypes.put(mae, fieldType);
                    propertyReadEpochs.put(mae, narrowing.propertyEpoch());
                    NullNarrowing.Subject subject = propertySubject(mae);
                    if (!assignmentTargetMode) recordLostRefinement(mae, subject, fieldType);
                    Type refined = assignmentTargetMode || subject == null ? null : narrowing.getNarrowedType(subject);
                    if (refined != null && !Types.equals(refined, fieldType)) {
                        narrowedReads.add(mae);
                        return refined;
                    }
                    return fieldType;
                } else {
                    var diagnostic = CompilerDiagnostic.error(DiagnosticCode.E4002,
                            "Field '" + field + "' not declared in class '" + cls.name() + "'", mae.span())
                            .withFacts(new deal.diagnostics.DiagnosticFacts("access.undeclared-field",
                                    assignmentTargetMode ? "field.target" : "field.read",
                                    "declared field of " + typeName(cls), field, List.of(
                                            "Choose a field declared by the selected class; no field or declaration is invented."),
                                    List.of(new deal.diagnostics.DiagnosticFacts.PathSegment(
                                            deal.diagnostics.DiagnosticFacts.PathKind.FIELD, field))));
                    if (sym.declarationRange() != null)
                        diagnostic = diagnostic.withNote("Receiver class declaration", sym.declarationRange());
                    diagnostics.add(diagnostic);
                    return Type.Error.INSTANCE;
                }
            }
            return Type.Error.INSTANCE;
        }

        // Raw Record reads retain unknown; an enclosing expected type does not
        // extract a concrete type. Storage targets retain their write handling.
        if (objType instanceof Type.Table) {
            if (assignmentTargetMode) {
                return Type.Table.INSTANCE;
            }
            return Type.Unknown.INSTANCE;
        }

        diagnostics.add(withReadEvidence(CompilerDiagnostic.error(DiagnosticCode.E3003,
            "Cannot access field '" + field + "' on type " + typeName(objType), mae.span())
                .withFacts(new deal.diagnostics.DiagnosticFacts("access.field-receiver",
                        assignmentTargetMode ? "field.target" : "field.read",
                        "Record or a declared class receiver; string/array only for supported members",
                        typeName(objType), receiverReadGuidance(objType), List.of(
                                new deal.diagnostics.DiagnosticFacts.PathSegment(
                                        deal.diagnostics.DiagnosticFacts.PathKind.FIELD, field)))), mae.object()));
        return Type.Error.INSTANCE;
    }

    private void recordLostRefinement(ExpressionNode expression, NullNarrowing.Subject subject, Type declared) {
        NullNarrowing.LostRefinement lost = subject == null ? null : narrowing.lostRefinement(subject);
        if (lost == null || Types.equals(lost.previous(), declared)) lostRefinementReads.remove(expression);
        else lostRefinementReads.put(expression, lost);
    }

    private CompilerDiagnostic withReadEvidence(CompilerDiagnostic diagnostic, ExpressionNode expression) {
        var lost = lostRefinementReads.get(expression);
        if (lost != null) diagnostic = diagnostic.withNote(
                "Earlier refinement to " + typeName(lost.previous())
                        + " was invalidated by this await; inspect or guard the current value again before use",
                lost.awaitRange());
        ExpressionNode receiver = expression instanceof MemberAccessExpr member ? member.object()
                : expression instanceof IndexExpr index ? index.array() : null;
        if (receiver != null && typeMap.get(receiver) instanceof Type.Table
                && typeMap.get(expression) instanceof Type.Unknown) {
            diagnostic = diagnostic.withNote(
                    "This raw Record read has type unknown; an expected type does not extract it. "
                            + "Choose a checked assertion or store and narrow the value with a supported guard: "
                            + "typeof for undefined/boolean/number/string, nullish equality, or a nominal instanceof test. "
                            + "Presence alone does not distinguish a missing key from a present undefined value or narrow its type. "
                            + "See docs/spec-v1.6.md#reading-and-writing-fields and #narrowing-and-checked-assertions.",
                    expression.span().range());
        }
        return diagnostic;
    }

    private static List<String> receiverReadGuidance(Type receiver) {
        if (receiver instanceof Type.Unknown) return List.of(
                "Unknown requires checked extraction or supported binding narrowing; an expected result type does not extract a raw Record read. "
                    + "Direct typeof tests narrow undefined/boolean/number/string; nominal instanceof tests narrow to the selected class. "
                    + "typeof object/function does not infer a structural type, and presence alone never narrows a value. "
                    + "See docs/spec-v1.6.md#narrowing-and-checked-assertions; schematic example: "
                    + "if (typeof value === \"string\") { consume(value); }. Choose the intended consumer contract explicitly.");
        if (receiver instanceof Type.Nullable || receiver instanceof Type.Null || receiver instanceof Type.Undefined)
            return List.of("Establish a non-nullish receiver with a supported guard or choose optional chaining when skipped access is intended; presence alone does not refine a field value. "
                    + "See docs/spec-v1.6.md#narrowing-and-checked-assertions and #nullish-coalescing-and-optional-chaining; "
                    + "schematic example: if (value !== null && value !== undefined) { consume(value); }. "
                    + "Choose the intended guard or skipped-access behavior explicitly. Guard each admitted nullish member; "
                    + "optional access deliberately adds undefined for skipped non-void results.");
        return List.of("Choose a receiver supporting this access; no conversion or replacement declaration is inferred.");
    }

    // =======================================================================
    // Index expression
    // =======================================================================

    private Type checkIndex(IndexExpr idx) {
        Type arrayType = checkAddressOperand(idx.array());
        Type indexType = checkAddressOperand(idx.index());
        if (arrayType == Type.Error.INSTANCE || indexType == Type.Error.INSTANCE)
            return Type.Error.INSTANCE;

        // A-D10 (assignment-delete-address-chains): table index
        // write/delete keys must have static type string (docs/spec-v1.6.md
        // §Writing table fields and §Deletion). The F5 "allow any index
        // type and skip further checks" skip is superseded by the static string-key gate.
        if (arrayType instanceof Type.Table) {
            if (assignmentTargetMode) {
                if (indexType instanceof Type.String) {
                    return Type.Table.INSTANCE;
                }
                diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E3018,
                    "Table index key must have static type string, got "
                    + typeName(indexType), idx.index().span()).withFacts(
                            new deal.diagnostics.DiagnosticFacts("index.record-key", "record.index.target",
                                    "string", typeName(indexType), List.of(
                                            "Choose an intended string key; no implicit key conversion is performed."))));
                return Type.Error.INSTANCE;
            }
            // Computed Record reads retain unknown and require string keys.
            if (!(indexType instanceof Type.String)) {
                diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E3007,
                    "Array index must be int, got " + typeName(indexType),
                    idx.index().span()).withFacts(new deal.diagnostics.DiagnosticFacts(
                            "index.record-key", "record.index.read", "string", typeName(indexType), List.of(
                                    "Choose an intended string key; no implicit key conversion is performed."))));
                return Type.Error.INSTANCE;
            }
            return Type.Unknown.INSTANCE;
        }

        // Integrality and bounds are runtime checks; static indexes are numbers.
        if (!(indexType instanceof Type.Number)) {
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E3007,
                "Array index must be number, got " + typeName(indexType), idx.index().span()).withFacts(
                        new deal.diagnostics.DiagnosticFacts("index.array-key", "array.index", "number",
                                typeName(indexType), List.of(
                                        "Choose a numeric index; integrality and bounds are validated at runtime, without truncation."))));
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

        diagnostics.add(withReadEvidence(CompilerDiagnostic.error(DiagnosticCode.E3007,
                "Cannot index type " + typeName(arrayType), idx.array().span()).withFacts(
                        new deal.diagnostics.DiagnosticFacts("index.receiver", "index",
                                "array or Record", typeName(arrayType), receiverReadGuidance(arrayType))), idx.array()));
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
            if (elemType instanceof Type.Void) {
                voidDataError(DiagnosticCode.E3001, "Void results cannot be stored in arrays",
                        elem.span(), elem, "array.element");
                mixed = true;
                continue;
            }
            if (elemType == Type.Error.INSTANCE) { mixed = true; continue; }
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
            if (property.staticName() != null && !propertyNames.add(property.staticName())) {
                error(DiagnosticCode.E3001, "Duplicate property '" + property.staticName() + "'", property.span());
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
            if (prop.key() != null) {
                Type key = checkExpression(prop.key());
                if (!(key instanceof Type.String) && key != Type.Error.INSTANCE)
                    error(DiagnosticCode.E3018, "Record key must be string", prop.key().span());
            }
            if (checkExpression(prop.value()) instanceof Type.Void)
                voidDataError(DiagnosticCode.E3001, "Void results cannot be stored in records",
                        prop.value().span(), prop.value(), "record.field");
        }
        expectedType = savedExpected;
        if (expectedType == null) {
            error(DiagnosticCode.E3002, obj.properties().isEmpty()
                ? "Cannot infer type of empty object literal"
                : "Object literal requires contextual type", obj.span());
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
        for (Property property : obj.properties()) {
            if (property.key() != null) {
                diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E3001,
                        "Computed class keys are not permitted", property.span())
                        .withFacts(new deal.diagnostics.DiagnosticFacts("construction.computed-key", "construction.key",
                                "static declared-field name", "computed key", List.of(
                                        "Choose the intended static field name; class construction does not evaluate computed keys."))
                                .withConstruction(constructionContext(cls, null, "", null))));
                return Type.Error.INSTANCE;
            }
        }
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
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E3004, "Unknown class '" + cls.name() + "'", obj.span())
                    .withFacts(new deal.diagnostics.DiagnosticFacts("construction.missing-declaration", "construction",
                            "available exact class declaration", typeName(cls), List.of(
                                    "Inspect the selected class provider; no same-spelled declaration or structural substitute is inferred."))
                            .withConstruction(constructionContext(cls, null, "", null))));
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
                diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E4002,
                    "Extra field '" + propName + "' in class literal for '" + cls.name() + "'",
                    provided.get(propName).span()).withFacts(classFieldFacts(
                            "construction.extra-field", propName, "a declared field of " + cls.name(), propName,
                            "Choose the intended declared field or revise the intended class contract; no field is inferred.")
                            .withConstruction(constructionContext(cls, cs, propName, null))));
            }
        }

        for (ClassField field : classFields) {
            Property property = provided.get(field.name());
            Type fieldType = resolveFieldTypeInClassModule(cs, field.type());
            if (property == null) {
                if (!field.optional()) {
                    diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E4001,
                        "Missing required field '" + field.name()
                            + "' in class literal for '" + cls.name() + "'",
                        obj.span()).withFacts(classFieldFacts("construction.missing-field", field.name(),
                                typeName(fieldType), "<absent>",
                                "Provide the intended field value; required presence is distinct from null or undefined.")
                                .withConstruction(constructionContext(cls, cs, field.name(), field)))
                                .withNote("Required field declaration", field.span().range()));
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
                diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E4003,
                    "Type mismatch for field '" + field.name() + "': expected "
                        + typeName(fieldType) + ", got " + typeName(valueType),
                    property.span()).withFacts(classFieldFacts("construction.field-type", field.name(),
                            typeName(fieldType), typeName(valueType), "Choose a value satisfying the declared field type; no coercion is inferred.")
                            .withMismatch(typeMismatch(fieldType, valueType))
                            .withConstruction(constructionContext(cls, cs, field.name(), field)))
                            .withNote("Expected field annotation", field.type().span().range()));
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
        Type target = expectedType instanceof Type.Nullable nullable ? nullable.inner() : expectedType;
        Type.Func contextual = target instanceof Type.Func function ? function : null;
        for (int i = 0; i < fe.params().size(); i++) {
            Parameter p = fe.params().get(i);
            if (p.type() != null) paramTypes.add(nameResolver.resolveTypeNode(p.type()));
            else if (contextual != null && i < contextual.paramTypes().size()) paramTypes.add(contextual.paramTypes().get(i));
            else {
                error(DiagnosticCode.E3002, "Parameter '" + p.name() + "' requires a type annotation or contextual signature", p.span());
                paramTypes.add(Type.Error.INSTANCE);
            }
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
            for (int i = 0; i < fe.params().size(); i++) {
                Parameter parameter = fe.params().get(i);
                if (parameter.type() == null) {
                    Type pt = paramTypes.get(i);
                    Type leaf = innermostElementType(pt);
                    Symbol.ClassSymbol provider = leaf instanceof Type.Class cls ? resolveClassSymbol(cls) : null;
                    currentScope.defineReplace(parameter.name(), new Symbol.VariableSymbol(parameter.name(), pt, true, provider, null));
                }
            }
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
                Type pt = paramTypes.get(fe.params().indexOf(p));
                currentScope.define(p.name(), new Symbol.VariableSymbol(p.name(), pt,
                    true, nameResolver.declaredClassSymbolOf(p.type()), p.type(), false, p.span().range()));
            }
        }

        Type savedReturnType = currentReturnType;
        TypeNode savedReturnAnnotation = currentReturnAnnotation;
        currentReturnAnnotation = fe.returnType();
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
        Deque<NullNarrowing> savedTryEntryFacts = tryEntryFacts;
        tryEntryFacts = new ArrayDeque<>();
        Deque<NullNarrowing> savedLoopSavedNarrowing = loopSavedNarrowing;
        loopSavedNarrowing = new ArrayDeque<>();
        int savedLoopDepth = loopDepth;
        loopDepth = 0;
        Type savedExpected = expectedType;
        Symbol.ClassSymbol savedClassContext = contextualClassDecl;
        expectedType = null;
        contextualClassDecl = null;
        try {
            walkStatement(fe.body());
        } finally {
            expectedType = savedExpected;
            contextualClassDecl = savedClassContext;
        }
        if (fe.expressionBody() && returnType instanceof Type.Void
                && fe.body().statements().getFirst() instanceof ExpressionStatement expression
                && !(typeOf(expression.expr()) instanceof Type.Void)) {
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E5003,
                    "Void arrow body requires a void expression", expression.span()).withFacts(
                            new deal.diagnostics.DiagnosticFacts("return.void-arrow-data", "return",
                                    "void", typeName(typeOf(expression.expr())), List.of()))
                    .withNote("Declared result annotation", fe.returnType().span().range()));
        }
        narrowing = savedNarrowing;
        loopAssignments = savedLoopAssignments;
        tryAssignments = savedTryAssignments;
        tryEntryFacts = savedTryEntryFacts;
        loopSavedNarrowing = savedLoopSavedNarrowing;
        loopDepth = savedLoopDepth;

        if (!(returnType instanceof Type.Void)
                && !ReturnAnalysis.definitelyReturns(fe.body())) {
            diagnostics.add(missingReturnDiagnostic(
                "Function expression must return a value on all paths", fe.span(), returnType, fe.returnType(), fe.body()));
        }

        currentReturnType = savedReturnType;
        currentReturnAnnotation = savedReturnAnnotation;
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
        Type savedExpected = expectedType;
        Symbol.ClassSymbol savedContext = contextualClassDecl;
        Type source;
        expectedType = null;
        contextualClassDecl = null;
        try { source = checkExpression(assertion.value()); }
        finally { expectedType = savedExpected; contextualClassDecl = savedContext; }
        Type target = nameResolver.resolveTypeNode(assertion.target());
        if (target instanceof Type.Void || source instanceof Type.Void
                || (!(source instanceof Type.Unknown) && !isAssignable(target, source)
                    && !isAssignable(source, target))) {
            diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E3001,
                    "Incompatible checked assertion from " + typeName(source) + " to " + typeName(target)
                            + ": void is not data; otherwise one type must be assignable to the other",
                    assertion.span()).withFacts(new deal.diagnostics.DiagnosticFacts(
                            "assertion.incompatible", "assertion", typeName(target), typeName(source),
                            List.of("Use compatible types; a checked assertion does not perform a conversion. "
                                    + "See docs/spec-v1.6.md#narrowing-and-checked-assertions; schematic example: "
                                    + "let text = value as string; checks an unknown value at runtime and may raise E8001. "
                                    + "Choose the intended target explicitly; an assertion supplies no JSON reconstruction context."))
                            .withMismatch(typeMismatch(target, source)))
                    .withNote("Checked assertion target annotation", assertion.target().span().range()));
            return Type.Error.INSTANCE;
        }
        recordExactClassDecl(assertion, target, exactAnnotationClassSymbol(currentScope, assertion.target()));
        return target;
    }

    private Type checkInstanceof(InstanceofExpr test) {
        Type source = checkExpression(test.value());
        Type target = nameResolver.resolveTypeNode(test.target());
        if (!(target instanceof Type.Class cls) || source instanceof Type.Void) {
            if (!(target instanceof Type.Class)) {
                diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E3001,
                        "instanceof requires a nominal class", test.span())
                        .withFacts(new deal.diagnostics.DiagnosticFacts("test.nominal-target", "instanceof",
                                "nominal class", typeName(target), List.of(
                                        "Choose an existing nominal class target; instanceof tests identity, not structural shape or conversion.")))
                        .withNote("Test target annotation", test.target().span().range()));
            } else {
                voidDataError(DiagnosticCode.E3001, "instanceof requires a nominal class",
                        test.span(), test.value(), "instanceof");
            }
            return Type.Error.INSTANCE;
        }
        instanceofTargets.put(test, cls);
        return Type.Boolean.INSTANCE;
    }

    private Type checkConditional(ConditionalExpr conditional) {
        Type context = expectedType;
        expectedType = Type.Boolean.INSTANCE;
        Type condition = checkExpression(conditional.condition());
        if (condition != Type.Error.INSTANCE && !(condition instanceof Type.Boolean))
            error(DiagnosticCode.E3007, "Conditional condition must be boolean, got " + typeName(condition), conditional.condition().span());
        NullNarrowing entry = narrowing.branch();
        Symbol.ClassSymbol declaration = contextualClassDecl;
        boolean reverse = context == null
            && (conditional.consequent() instanceof ArrayLiteralExpr || conditional.consequent() instanceof ObjectLiteralExpr)
            && (!(conditional.alternate() instanceof ArrayLiteralExpr || conditional.alternate() instanceof ObjectLiteralExpr)
                || literalNeedsContext(conditional.consequent()) && !literalNeedsContext(conditional.alternate()));
        ExpressionNode first = reverse ? conditional.alternate() : conditional.consequent();
        ExpressionNode second = reverse ? conditional.consequent() : conditional.alternate();
        narrowing = entry.branch();
        narrowCondition(conditional.condition(), !reverse);
        expectedType = context;
        Type firstType = checkExpression(first);
        NullNarrowing selected = narrowing;
        narrowing = entry.branch();
        narrowCondition(conditional.condition(), reverse);
        expectedType = context;
        contextualClassDecl = declaration;
        if (context == null && (second instanceof ArrayLiteralExpr || second instanceof ObjectLiteralExpr)
                && (firstType instanceof Type.Array || firstType instanceof Type.Class || firstType instanceof Type.Table)) {
            expectedType = firstType;
            contextualClassDecl = exactClassDecls.get(first);
        }
        Type secondType = checkExpression(second);
        selected.merge(narrowing);
        narrowing = selected;
        expectedType = context;
        contextualClassDecl = declaration;
        return reverse ? conditionalResult(secondType, firstType, context, conditional.span())
            : conditionalResult(firstType, secondType, context, conditional.span());
    }

    private static boolean literalNeedsContext(ExpressionNode expression) {
        return expression instanceof ObjectLiteralExpr
            || expression instanceof ArrayLiteralExpr array
                && (array.elements().isEmpty() || array.elements().stream().anyMatch(TypeChecker::literalNeedsContext));
    }

    private Type checkOptionalGuard(OptionalGuardExpr guard) {
        Type context = expectedType;
        Symbol.ClassSymbol declaration = contextualClassDecl;
        expectedType = null;
        contextualClassDecl = null;
        Type type;
        try { type = checkAddressOperand(guard.value()); }
        finally { expectedType = context; contextualClassDecl = declaration; }
        Type base = type instanceof Type.Nullable nullable ? nullable.inner() : type;
        recordExactClassDecl(guard, base, exactClassDecls.get(guard.value()));
        if (base instanceof Type.Void) {
            voidDataError(DiagnosticCode.E3007, "Void results are not data", guard.span(), guard.value(), "optional.receiver");
            return Type.Error.INSTANCE;
        }
        return base;
    }

    private Type checkOptionalChain(OptionalChainExpr chain) {
        NullNarrowing skipped = narrowing.branch();
        Type type = checkExpression(chain.expression());
        skipped.merge(narrowing);
        narrowing = skipped;
        recordExactClassDecl(chain, type, exactClassDecls.get(chain.expression()));
        return type instanceof Type.Void || type instanceof Type.Error ? type : Types.optionalRead(type);
    }

    private Type checkCoalesce(BinaryExpr binary) {
        Type context = expectedType;
        Symbol.ClassSymbol classContext = contextualClassDecl;
        Type left;
        Type right;
        expectedType = null;
        contextualClassDecl = null;
        try {
            left = checkExpression(binary.left());
            NullNarrowing skipped = narrowing.branch();
            expectedType = context;
            contextualClassDecl = classContext;
            Type base = left instanceof Type.Nullable nullable ? nullable.inner() : left;
            if (context == null
                    && (binary.right() instanceof ArrayLiteralExpr || binary.right() instanceof ObjectLiteralExpr)
                    && (base instanceof Type.Array || base instanceof Type.Class || base instanceof Type.Table)) {
                expectedType = base;
                contextualClassDecl = exactClassDecls.get(binary.left());
            }
            right = checkExpression(binary.right());
            skipped.merge(narrowing);
            narrowing = skipped;
        } finally { expectedType = context; contextualClassDecl = classContext; }
        if (left == Type.Error.INSTANCE || right == Type.Error.INSTANCE) return Type.Error.INSTANCE;
        if (left instanceof Type.Void || right instanceof Type.Void) {
            ExpressionNode rejected = left instanceof Type.Void ? binary.left() : binary.right();
            voidDataError(DiagnosticCode.E3007, "Void results are not data", rejected.span(), rejected, "coalesce.operand");
            return Type.Error.INSTANCE;
        }
        Type base = left instanceof Type.Nullable n ? n.inner() : left;
        if (base instanceof Type.Null || base instanceof Type.Undefined) return right;
        return conditionalResult(base, right, context, binary.span());
    }

    private Type conditionalResult(Type consequent, Type alternate, Type context, Span span) {
        if (consequent == Type.Error.INSTANCE || alternate == Type.Error.INSTANCE) return Type.Error.INSTANCE;
        if (context != null && isAssignable(context, consequent) && isAssignable(context, alternate)) return context;
        if (Types.equals(consequent, alternate)) return consequent;
        if (consequent instanceof Type.Void || alternate instanceof Type.Void) {
            error(DiagnosticCode.E3006, "Void conditional branches must both return void", span);
            return Type.Error.INSTANCE;
        }
        if (consequent instanceof Type.Unknown || alternate instanceof Type.Unknown) return Type.Unknown.INSTANCE;
        if (consequent instanceof Type.Null) return Types.withNullish(alternate, false, false);
        if (alternate instanceof Type.Null) return Types.withNullish(consequent, false, false);
        if (consequent instanceof Type.Undefined) return Types.withNullish(alternate, true, false);
        if (alternate instanceof Type.Undefined) return Types.withNullish(consequent, true, false);
        Type leftBase = consequent instanceof Type.Nullable n ? n.inner() : consequent;
        Type rightBase = alternate instanceof Type.Nullable n ? n.inner() : alternate;
        if (Types.equals(leftBase, rightBase)) {
            Type result = leftBase;
            if ((consequent instanceof Type.Nullable n && n.admitsNull())
                    || (alternate instanceof Type.Nullable other && other.admitsNull())) result = Types.withNullish(result, false, false);
            if ((consequent instanceof Type.Nullable n && n.admitsUndefined())
                    || (alternate instanceof Type.Nullable other && other.admitsUndefined())) result = Types.withNullish(result, true, false);
            return result;
        }
        error(DiagnosticCode.E3006, "Incompatible conditional branches: " + typeName(consequent) + " and " + typeName(alternate), span);
        return Type.Error.INSTANCE;
    }

    private Type checkHas(HasExpr has) {
        Type objType = checkExpression(has.object());
        Type keyType = checkExpression(has.key());
        if (!(keyType instanceof Type.String)) {
            presenceError("Object.hasOwn key must be string", has.key().span(), "presence.key-type",
                    "string", typeName(keyType), null, null);
            return Type.Error.INSTANCE;
        }
        if (objType instanceof Type.Table) return Type.Boolean.INSTANCE;
        if (objType == Type.Error.INSTANCE) return Type.Error.INSTANCE;

        if (!(objType instanceof Type.Class cls)) {
            presenceError("Object.hasOwn requires a Record or class instance, got " + typeName(objType),
                    has.span(), "presence.receiver", "Record or non-nullish class instance", typeName(objType), null, null);
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
            presenceError("Class '" + cls.name() + "' not found", has.span(), "presence.missing-declaration",
                    "available exact class declaration", typeName(cls), has.field(), null);
            return Type.Error.INSTANCE;
        }

        if (has.field() == null) {
            presenceError("Class presence tests require a literal declared-field name", has.key().span(),
                    "presence.class-key", "literal declared-field name", typeName(keyType), null, sym);
            return Type.Error.INSTANCE;
        }
        ClassField field = IntrinsicResolvers.findField(sym.fields(), has.field());
        if (field == null) {
            presenceError("Field '" + has.field() + "' not declared in class '" + cls.name() + "'",
                    has.span(), "presence.undeclared-field", "declared field of " + typeName(cls), has.field(), has.field(), sym);
            return Type.Error.INSTANCE;
        }
        return Type.Boolean.INSTANCE;
    }

    private void presenceError(String message, Span span, String reason, String expected, String actual,
            String field, Symbol.ClassSymbol declaration) {
        var path = field == null ? List.<deal.diagnostics.DiagnosticFacts.PathSegment>of()
                : List.of(new deal.diagnostics.DiagnosticFacts.PathSegment(
                        deal.diagnostics.DiagnosticFacts.PathKind.FIELD, field));
        var diagnostic = CompilerDiagnostic.error(DiagnosticCode.E4005, message, span)
                .withFacts(new deal.diagnostics.DiagnosticFacts(reason, "Object.hasOwn", expected, actual, List.of(
                        "Presence is distinct from null or undefined and does not narrow a field value; choose a supported receiver and string key, with a literal declared-field name for classes."), path));
        if (declaration != null && declaration.declarationRange() != null)
            diagnostic = diagnostic.withNote("Receiver class declaration", declaration.declarationRange());
        diagnostics.add(diagnostic);
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
            mutationError(DiagnosticCode.E3017, "Assignment requires a variable binding", id.span(),
                    "mutation.non-variable", "assignment", "mutable variable binding", id.name(),
                    bindingDeclarationRange(resolveVisibleBinding(id.name())));
            readOnlyTarget = true;
        }
        if (assign.target() instanceof IdentifierExpr id
                && resolveVisibleBinding(id.name()) instanceof Symbol.VariableSymbol variable
                && variable.isConst()) {
            mutationError(DiagnosticCode.E3017, "Cannot reassign const binding '" + id.name() + "'", id.span(),
                    "mutation.const-reassignment", "assignment", "mutable variable binding", "const " + id.name(),
                    variable.declarationRange());
            readOnlyTarget = true;
        }
        ExpressionNode receiver = assign.target() instanceof MemberAccessExpr member ? member.object()
            : assign.target() instanceof IndexExpr index ? index.array() : null;
        if (receiver instanceof IdentifierExpr id
                && resolveVisibleBinding(id.name()) instanceof Symbol.ModuleSymbol module) {
            mutationError(DiagnosticCode.E3017, "Imported exports are read-only", assign.target().span(),
                    "mutation.import-readonly", "assignment", "locally mutable storage", "imported export",
                    module.importSpan() == null ? null : module.importSpan().range());
            readOnlyTarget = true;
        }
        if (assign.target() instanceof MemberAccessExpr mae
                && mae.field().equals("length")
                && (typeOf(mae.object()) instanceof Type.Array || typeOf(mae.object()) instanceof Type.String)) {
            mutationError(DiagnosticCode.E3017, "Length is read-only", assign.target().span(),
                    "mutation.length-readonly", "assignment", "read-only length", typeName(typeOf(mae.object())), null);
            readOnlyTarget = true;
        }

        // F3: Use the target type as contextual expected type for the value
        Type savedExpected = expectedType;
        Symbol.ClassSymbol savedContextualClass = contextualClassDecl;
        if (declaredTarget != null && declaredTarget != Type.Error.INSTANCE) {
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
                TypeNode annotation = assign.target() instanceof IdentifierExpr id
                        && resolveVisibleBinding(id.name()) instanceof Symbol.VariableSymbol variable
                        ? variable.declaredType() : null;
                int index = diagnostics.size();
                reportAssignabilityError(declaredTarget, valueType, assign.span(), annotation);
                diagnostics.set(index, withReadEvidence(diagnostics.get(index), assign.value()));
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

        invalidatePropertyNarrowing();
        if (assign.target() instanceof IdentifierExpr id && isAssignable(declaredTarget, valueType))
            refineAssignedBinding(resolveVisibleBinding(id.name()), declaredTarget, valueType);
        return valueType;
    }

    // =======================================================================
    // Assignment compatibility
    // =======================================================================

    private deal.diagnostics.DiagnosticFacts.OperatorContext binaryOperatorContext(
            BinaryExpr bin, Type left, Type right, List<String> accepted) {
        return new deal.diagnostics.DiagnosticFacts.OperatorContext(opSymbol(bin.op()), List.of(
                new deal.diagnostics.DiagnosticFacts.Operand("left", typeName(left), bin.left().span().range()),
                new deal.diagnostics.DiagnosticFacts.Operand("right", typeName(right), bin.right().span().range())), accepted);
    }

    private void binaryOperatorError(DiagnosticCode code, String message, Span span, BinaryExpr bin,
            Type left, Type right, String reason, String expected, List<String> accepted) {
        diagnostics.add(CompilerDiagnostic.error(code, message, span).withFacts(
                new deal.diagnostics.DiagnosticFacts(reason, "operator." + opSymbol(bin.op()), expected,
                        "left: " + typeName(left) + "; right: " + typeName(right),
                        List.of("Choose operands allowed by the operator; DEAL does not implicitly coerce operands."))
                        .withOperator(binaryOperatorContext(bin, left, right, accepted))));
    }

    private void unaryOperatorError(String message, UnaryExpr unary, Type actual, String token, String expected) {
        diagnostics.add(CompilerDiagnostic.error(DiagnosticCode.E3007, message, unary.expr().span()).withFacts(
                new deal.diagnostics.DiagnosticFacts("operator.unary.operand", "operator." + token, expected,
                        typeName(actual), List.of("Choose an operand of the required type; no coercion is inferred."))
                        .withOperator(new deal.diagnostics.DiagnosticFacts.OperatorContext(token, List.of(
                                new deal.diagnostics.DiagnosticFacts.Operand("operand", typeName(actual), unary.expr().span().range())),
                                List.of(expected)))));
    }

    private void voidDataError(DiagnosticCode code, String message, Span span, ExpressionNode producer,
                               String operation) {
        var facts = new deal.diagnostics.DiagnosticFacts("void.data-use", operation, "data value", "void",
                List.of("Use the void call as a statement, or choose an intended data-producing expression; no result value or return-type change is inferred."));
        ExpressionNode value = producer;
        if (value instanceof AwaitExpression awaited) value = awaited.callee();
        if (value instanceof OptionalChainExpr chain) value = chain.expression();
        FunctionDeclaration declaration = null;
        if (value instanceof CallExpr call && typeMap.get(call.callee()) instanceof Type.Func signature) {
            facts = facts.withCall(callFacts("void.data-use", call, signature, -1, "data value", "void").call());
            Symbol.FunctionSymbol symbol = calleeDeclarationOf(call);
            declaration = symbol == null ? null : symbol.declaration();
        }
        CompilerDiagnostic diagnostic = CompilerDiagnostic.error(code, message, span).withFacts(facts);
        if (declaration != null)
            diagnostic = diagnostic.withNote("Void producer result annotation", declaration.returnType().span().range());
        diagnostics.add(diagnostic);
    }

    private static deal.diagnostics.DiagnosticRange bindingDeclarationRange(Symbol binding) {
        if (binding instanceof Symbol.VariableSymbol variable) return variable.declarationRange();
        if (binding instanceof Symbol.FunctionSymbol function && function.declaration() != null)
            return function.declaration().span().range();
        if (binding instanceof Symbol.ClassSymbol cls) return cls.declarationRange();
        if (binding instanceof Symbol.ModuleSymbol module && module.importSpan() != null)
            return module.importSpan().range();
        return null;
    }

    private void mutationError(DiagnosticCode code, String message, Span span, String reason,
            String operation, String expected, String actual, deal.diagnostics.DiagnosticRange declaration) {
        CompilerDiagnostic diagnostic = CompilerDiagnostic.error(code, message, span).withFacts(
                new deal.diagnostics.DiagnosticFacts(reason, operation, expected, actual,
                        List.of("Choose storage and mutation consistent with the declared contract; no declaration rewrite is inferred.")));
        if (declaration != null) diagnostic = diagnostic.withNote("Restriction declared here", declaration);
        diagnostics.add(diagnostic);
    }

    private static deal.diagnostics.DiagnosticFacts.ConstructionContext constructionContext(Type.Class type,
            Symbol.ClassSymbol declaration, String fieldName, ClassField field) {
        String provider = declaration == null ? type.providerModule() : declaration.modulePath();
        return new deal.diagnostics.DiagnosticFacts.ConstructionContext(type.name(), provider == null ? "" : provider,
                declaration == null ? null : declaration.declarationRange(), fieldName,
                field == null ? null : field.optional(), field == null ? null : field.span().range(),
                field == null ? null : field.type().span().range());
    }

    private static deal.diagnostics.DiagnosticFacts classFieldFacts(String reason, String field,
            String expected, String actual, String guidance) {
        return new deal.diagnostics.DiagnosticFacts(reason, "construction.field", expected, actual,
                List.of(guidance), List.of(new deal.diagnostics.DiagnosticFacts.PathSegment(
                        deal.diagnostics.DiagnosticFacts.PathKind.FIELD, field)));
    }

    private void checkAssignment(Type targetType, Type exprType, Span span, TypeNode annotation) {
        if (targetType == null || exprType == null) return;
        if (targetType == Type.Error.INSTANCE || exprType == Type.Error.INSTANCE) return;
        if (!isAssignable(targetType, exprType)) {
            if (reportClassProviderCrossing(targetType, exprType, span)) {
                return;
            }
            reportAssignabilityError(targetType, exprType, span, annotation);
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
        reportClassCrossing(name, expected, actual,
                expectedClass == null ? null : resolveClassSymbol(expectedClass),
                nonNullClassType(actual) instanceof Type.Class cls ? resolveClassSymbol(cls) : null, span);
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
            reportClassCrossing(tc.name(), targetType, valueType, targetDecl, valueDecl, span);
        }
    }

    private void reportClassCrossing(String name, Type expected, Type actual,
            Symbol.ClassSymbol expectedDeclaration, Symbol.ClassSymbol actualDeclaration, Span span) {
        CompilerDiagnostic diagnostic = CompilerDiagnostic.error(DiagnosticCode.E3019,
                "Cannot assign class '" + name + "' across its distinct declarations of the same-named class", span)
                .withFacts(new deal.diagnostics.DiagnosticFacts("type.class-provider", "assignment.nominal",
                        typeName(expected), typeName(actual), List.of(
                                "Use a value from the intended class declaration; equal names or fields do not retag nominal values.")));
        if (expectedDeclaration != null && expectedDeclaration.declarationRange() != null)
            diagnostic = diagnostic.withNote("Expected class declaration", expectedDeclaration.declarationRange());
        if (actualDeclaration != null && actualDeclaration.declarationRange() != null)
            diagnostic = diagnostic.withNote("Actual class declaration", actualDeclaration.declarationRange());
        diagnostics.add(diagnostic);
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
    private void reportAssignabilityError(Type targetType, Type actualType, Span span, TypeNode annotation) {
        int diagnosticIndex = diagnostics.size();
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
        CompilerDiagnostic diagnostic = diagnostics.get(diagnosticIndex).withFacts(
                new deal.diagnostics.DiagnosticFacts("type.assignability", "assignment",
                        typeName(targetType), typeName(actualType), List.of())
                        .withMismatch(typeMismatch(targetType, actualType)));
        if (annotation != null) diagnostic = diagnostic.withNote("Expected binding annotation", annotation.span().range());
        diagnostics.set(diagnosticIndex, diagnostic);
    }

    /** Describes an already rejected pair; never participates in the typing decision. */
    private static deal.diagnostics.DiagnosticFacts.TypeMismatch typeMismatch(Type expected, Type actual) {
        var path = new ArrayList<deal.diagnostics.DiagnosticFacts.TypeComponent>();
        boolean exact = false;
        String rule;
        while (true) {
            if (path.size() == 32) {
                return new deal.diagnostics.DiagnosticFacts.TypeMismatch("type.detail-limit", path,
                        typeName(expected), typeName(actual), true);
            }
            if (expected instanceof Type.Nullable target) {
                if (actual instanceof Type.Nullable value) {
                    // Nullable assignability compares the invariant inner type before presence flags;
                    // nested exact equality checks presence flags first.
                    if (exact || Types.equals(value.inner(), target.inner())) {
                        if (exact ? target.admitsNull() != value.admitsNull()
                                : value.admitsNull() && !target.admitsNull()) { rule = "type.null-presence"; break; }
                        if (exact ? target.admitsUndefined() != value.admitsUndefined()
                                : value.admitsUndefined() && !target.admitsUndefined()) { rule = "type.undefined-presence"; break; }
                    }
                    path.add(new deal.diagnostics.DiagnosticFacts.TypeComponent(
                            deal.diagnostics.DiagnosticFacts.TypeComponentKind.NULLABLE_INNER, -1));
                    expected = target.inner(); actual = value.inner(); exact = true; continue;
                }
                if (!exact && actual instanceof Type.Null) { rule = "type.null-presence"; break; }
                if (!exact && actual instanceof Type.Undefined) { rule = "type.undefined-presence"; break; }
                if (!exact) {
                    path.add(new deal.diagnostics.DiagnosticFacts.TypeComponent(
                            deal.diagnostics.DiagnosticFacts.TypeComponentKind.NULLABLE_INNER, -1));
                    expected = target.inner(); exact = true; continue;
                }
            }
            if (expected instanceof Type.Array target && actual instanceof Type.Array value) {
                path.add(new deal.diagnostics.DiagnosticFacts.TypeComponent(
                        deal.diagnostics.DiagnosticFacts.TypeComponentKind.ARRAY_ELEMENT, -1));
                expected = target.element(); actual = value.element(); exact = true; continue;
            }
            if (expected instanceof Type.Func target && actual instanceof Type.Func value) {
                if (target.isAsync() != value.isAsync()) { rule = "type.function-async"; break; }
                if (!Types.equals(target.returnType(), value.returnType())) {
                    path.add(new deal.diagnostics.DiagnosticFacts.TypeComponent(
                            deal.diagnostics.DiagnosticFacts.TypeComponentKind.FUNCTION_RESULT, -1));
                    expected = target.returnType(); actual = value.returnType(); exact = true; continue;
                }
                if (exact ? target.paramTypes().size() != value.paramTypes().size()
                        : value.paramTypes().size() > target.paramTypes().size()) {
                    rule = "type.function-arity"; break;
                }
                int index = -1;
                for (int i = 0; i < value.paramTypes().size(); i++) {
                    if (!Types.equals(target.paramTypes().get(i), value.paramTypes().get(i))) { index = i; break; }
                }
                if (index >= 0) {
                    path.add(new deal.diagnostics.DiagnosticFacts.TypeComponent(
                            deal.diagnostics.DiagnosticFacts.TypeComponentKind.FUNCTION_PARAMETER, index));
                    expected = target.paramTypes().get(index); actual = value.paramTypes().get(index);
                    exact = true; continue;
                }
            }
            rule = expected instanceof Type.Class && actual instanceof Type.Class ? "type.nominal"
                    : expected instanceof Type.Void ? "type.void-result" : "type.exact";
            break;
        }
        return new deal.diagnostics.DiagnosticFacts.TypeMismatch(rule, path,
                typeName(expected), typeName(actual), false);
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
            case OptionalGuardExpr guard -> exprType;
            case OptionalChainExpr chain -> exprType;
            case LiteralExpr lit -> exprType;
            case ArrayLiteralExpr arr -> {
                if (arr.elements().isEmpty()) {
                    error(DiagnosticCode.E3002, "Cannot infer type of empty array literal", init.span());
                    yield null;
                }
                yield exprType;
            }
            case ObjectLiteralExpr obj -> {
                error(DiagnosticCode.E3002, "Object literal requires contextual type", init.span());
                yield null;
            }
            case CallExpr call -> exprType;
            case AssignmentExpr assignment -> exprType;
            case FunctionExpr fe -> exprType;
            case IdentifierExpr id -> exprType;
            case IndexExpr idx -> exprType;
            case UnaryExpr un -> exprType;
            case BinaryExpr bin -> exprType;
            case ConditionalExpr conditional -> exprType;
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
     * visible at the current statement (docs/spec-v1.6.md §Name resolution —
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

    /** Observe only existing scope/type facts; never evaluate arguments to rank a misspelling. */
    private deal.diagnostics.DiagnosticFacts.NameContext nameAlternatives(String missing, CallExpr call) {
        var candidates = new ArrayList<deal.diagnostics.DiagnosticFacts.NameCandidate>();
        var rank = java.util.Comparator.comparingInt(deal.diagnostics.DiagnosticFacts.NameCandidate::distance)
                .thenComparingInt(deal.diagnostics.DiagnosticFacts.NameCandidate::scopeDepth)
                .thenComparing(deal.diagnostics.DiagnosticFacts.NameCandidate::name);
        int count = 0;
        var seen = new java.util.HashSet<String>();
        for (SymbolTable scope = currentScope; scope != null; scope = scope.parent()) {
            for (String name : scope.symbols().keySet()) {
                if (!seen.add(name)) continue;
                Symbol symbol = resolveVisibleBinding(name);
                if (!(symbol instanceof Symbol.VariableSymbol || symbol instanceof Symbol.FunctionSymbol)) continue;
                int distance = nearbyNameDistance(missing, name);
                if (distance > 2) continue;
                Type type = getDeclaredType(symbol);
                if (type instanceof Type.Error || type instanceof Type.Void) continue;
                Type valueType = type;
                if (call != null) {
                    if (!(type instanceof Type.Func function)
                            || function.paramTypes().size() != call.args().size()
                            || function.isAsync() != (call == directAwaitCallee)) continue;
                    valueType = function.returnType();
                }
                if (expectedType != null && !Types.isAssignable(valueType, expectedType)) continue;
                int visibleDepth = 0;
                for (SymbolTable owner = currentScope; owner != null && owner.resolveLocal(name) != symbol;
                        owner = owner.parent()) visibleDepth++;
                candidates.add(new deal.diagnostics.DiagnosticFacts.NameCandidate(name,
                        type instanceof Type.Func ? "FUNCTION" : "VALUE", typeName(type),
                        bindingDeclarationRange(symbol), distance, visibleDepth));
                count++;
                candidates.sort(rank);
                if (candidates.size() > 5) candidates.removeLast();
            }
        }
        return new deal.diagnostics.DiagnosticFacts.NameContext(candidates, count, count > candidates.size());
    }

    /** Levenshtein over at most 64 UTF-16 units; longer spellings have no fuzzy alternatives. */
    private static int nearbyNameDistance(String left, String right) {
        if (left.length() > 64 || right.length() > 64 || Math.abs(left.length() - right.length()) > 2) return 3;
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j < previous.length; j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] next = new int[right.length() + 1];
            next[0] = i;
            for (int j = 1; j < next.length; j++)
                next[j] = Math.min(Math.min(previous[j] + 1, next[j - 1] + 1),
                        previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            previous = next;
        }
        return previous[right.length()];
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

    private Type equalityDeclaredType(ExpressionNode expression, Type checked) {
        if (!narrowedReads.contains(expression)) return checked;
        return expression instanceof IdentifierExpr id ? declaredTypeOf(id.name())
            : declaredReadTypes.getOrDefault(expression, checked);
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
            case REM -> "%";

            case POW -> "**";
            case EQ -> "===";
            case NEQ -> "!==";
            case LT -> "<";
            case LTE -> "<=";
            case GT -> ">";
            case GTE -> ">=";
            case AND -> "&&";
            case OR -> "||";
            case COALESCE -> "??";
        };
    }

    /**
     * Returns a human-readable name for a type for use in diagnostic messages.
     * Returns {@code "<error>"} for the internal error sentinel.
     */
    public static String typeName(Type t) {
        return typeName(t, 0);
    }

    private static String typeName(Type t, int depth) {
        if (t == null) return "null";
        if (depth >= 32) return "<type truncated at depth 32>";
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
            case Type.Array a -> (a.element() instanceof Type.Nullable || a.element() instanceof Type.Func
                    ? "(" + typeName(a.element(), depth + 1) + ")"
                    : typeName(a.element(), depth + 1)) + "[]";
            case Type.Nullable n -> (n.inner() instanceof Type.Func
                    ? "(" + typeName(n.inner(), depth + 1) + ")" : typeName(n.inner(), depth + 1))
                + (n.admitsNull() ? " | null" : "") + (n.admitsUndefined() ? " | undefined" : "");
            case Type.Class c -> c.name();
            case Type.Func f -> (f.isAsync() ? "async " : "") + "("
                    + f.paramTypes().stream().map(p -> typeName(p, depth + 1))
                            .collect(java.util.stream.Collectors.joining(", "))
                    + ") => " + typeName(f.returnType(), depth + 1);
        };
    }

    private void error(DiagnosticCode code, String message, Span span) {
        CompilerDiagnostic diagnostic = CompilerDiagnostic.error(code, message, span);
        if (code == DiagnosticCode.E3002) {
            diagnostic = diagnostic.withFacts(new deal.diagnostics.DiagnosticFacts(
                    "context.required", "inference", "explicit contextual type", "no usable contextual type",
                    List.of("For an empty array, annotate the intended element type; for an object, supply the intended class or Record construction context. "
                            + "See docs/spec-v1.6.md#inference-rules, #array-literals and #record-type-and-semantics; "
                            + "schematic examples: `let values: number[] = [];` and `let fields: Record<string, unknown> = {};`. "
                            + "These annotations are alternatives requiring explicit intent, not inferred types or authorized edits.")));
        }
        diagnostics.add(diagnostic);
    }
}
