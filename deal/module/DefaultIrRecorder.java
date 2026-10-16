package deal.module;

import deal.ast.ArrayLiteralExpr;
import deal.ast.ArrayType;
import deal.ast.AssignmentExpr;
import deal.ast.AwaitExpression;
import deal.ast.BinaryExpr;
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
import deal.ast.VariableDeclaration;
import deal.ast.WhileStatement;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;
import deal.diagnostics.DiagnosticRange;
import deal.identity.CanonicalModuleIdentity;
import deal.types.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class DefaultIrRecorder {

    /**
     * The scope-aware type-node resolution seam: the planner supplies
     * the module's {@code NameResolver} positioned at the given scope;
     * the declaration analyzer supplies its declaration-scope resolver.
     */
    @FunctionalInterface
    interface TypeNodeResolver {
        Type resolveTypeNode(TypeNode typeNode, SymbolTable scope);
    }

    // ---- Read-only inputs ----

    private final Map<ExpressionNode, Type> typeMap;
    private final Map<StatementNode, SymbolTable> scopeMap;
    /**
     * The statement that introduced each checked lexical scope, by scope
     * identity (the inverse of {@link #scopeMap}): a resolved local
     * declaration's defining scope ties back to the declaration it was
     * defined in, so a nested declaration shadowing a same-named
     * top-level function supplies its own parameter contexts.
     */
    private final Map<SymbolTable, StatementNode> scopeOwners;
    private final Map<String, DefaultPlanImport> imports;
    private final Map<String, deal.identity.CanonicalModuleIdentity>
        classification;
    private final SourceModuleLocation location;
    private final String modulePath;
    private final ProgramNode program;
    private final TypeNodeResolver typeResolver;

    /**
     * Class name &rarr; the import alias the declaring field type
     * annotation names for that class (collected from the field's type
     * node): the importer-local alias a contextual class literal
     * occurrence is attributed to, in preference to the first alias
     * importing the provider.
     */
    private final Map<String, String> qualifiedClassAliases;

    // ---- Outputs ----

    private final LinkedHashMap<String, Symbol> bindings =
        new LinkedHashMap<>();
    private final LinkedHashMap<SemanticResourceIdentity,
        DefaultResourceOccurrence> occurrences = new LinkedHashMap<>();

    /**
     * The imported class-literal providers of this walk (literal range
     * &rarr; import alias), one entry per literal in walk order: the
     * resource occurrences deduplicate by semantic resource identity,
     * while an emitter needs the exact provider of every constructed
     * literal, repeated demands included.
     */
    private final LinkedHashMap<DiagnosticRange, String>
        classLiteralProviders = new LinkedHashMap<>();

    /**
     * The resolved provider of every inferred class-typed binding whose
     * declaration was already walked (binding symbol &rarr; the provider
     * its initializer expression's checked route names): an inferred
     * binding keeps its initializer's provenance for later assignments,
     * so the import order never decides the constructed class.
     */
    private final Map<Symbol.VariableSymbol, DefaultPlanImport>
        bindingProviders = new java.util.IdentityHashMap<>();

    /**
     * The checker's non-hoisting let visibility (docs/spec-v1.3.md
     * &sect;Name resolution — Declaration order and &sect;Scope) mirrored over this
     * source-order walk: one frame per open statement list, holding the
     * scope of that list and its own let-declared names not yet
     * reached. A reference to such a name resolves against the
     * enclosing scopes, so an assignment inside a shadowing
     * declaration's own initializer (or a forward reference) names the
     * enclosing binding exactly like the checker's
     * {@code resolveVisibleBinding}. Walk state only: every push has a
     * matching pop on the same code path.
     */
    private record PendingScope(SymbolTable scope, Set<String> remaining) {
    }

    private final ArrayDeque<PendingScope> pendingScopes =
        new ArrayDeque<>();

    /**
     * The provider the enclosing declared function's return type node
     * names (the return-provider provenance of a returned class
     * literal), pushed around every walked function body and function
     * expression of this module and restored on exit: a class context
     * arriving through a declared signature is exact even when the
     * demanded name is exported by identity-equal siblings. Null
     * outside a function body or when the annotation names no import.
     */
    private DefaultPlanImport enclosingReturnProvider = null;

    private final List<CompilerDiagnostic> awaitDiagnostics =
        new ArrayList<>();

    DefaultIrRecorder(
            Map<ExpressionNode, Type> typeMap,
            Map<StatementNode, SymbolTable> scopeMap,
            Map<String, DefaultPlanImport> imports,
            Map<String, deal.identity.CanonicalModuleIdentity> classification,
            SourceModuleLocation location,
            String modulePath,
            ProgramNode program,
            TypeNodeResolver typeResolver,
            Map<String, String> qualifiedClassAliases) {
        this.typeMap = Objects.requireNonNull(typeMap, "typeMap");
        this.scopeMap = Objects.requireNonNull(scopeMap, "scopeMap");
        Map<SymbolTable, StatementNode> owners =
            new java.util.IdentityHashMap<>();
        for (Map.Entry<StatementNode, SymbolTable> entry
                : this.scopeMap.entrySet()) {
            owners.put(entry.getValue(), entry.getKey());
        }
        this.scopeOwners = owners;
        this.imports = Objects.requireNonNull(imports, "imports");
        this.classification = Objects.requireNonNull(classification,
            "classification");
        this.location = Objects.requireNonNull(location, "location");
        this.modulePath = Objects.requireNonNull(modulePath, "modulePath");
        this.program = Objects.requireNonNull(program, "program");
        this.typeResolver = Objects.requireNonNull(typeResolver,
            "typeResolver");
        this.qualifiedClassAliases = Map.copyOf(Objects.requireNonNull(
            qualifiedClassAliases, "qualifiedClassAliases"));
    }

    /** The insertion-ordered resolved bindings (read-only view). */
    Map<String, Symbol> bindings() {
        return Collections.unmodifiableMap(bindings);
    }

    /**
     * The ordered, deduplicated provisional occurrence records
     * (first-IR-walk-occurrence order, deduplicated by semantic
     * resource identity).
     */
    List<DefaultResourceOccurrence> occurrences() {
        return List.copyOf(occurrences.values());
    }

    /**
     * The imported class-literal providers of this walk (literal range
     * &rarr; import alias), one entry per constructed literal.
     */
    Map<DiagnosticRange, String> classLiteralProviders() {
        return Map.copyOf(classLiteralProviders);
    }

    /** The E3020 evaluator-scope-await diagnostics. */
    List<CompilerDiagnostic> awaitDiagnostics() {
        return List.copyOf(awaitDiagnostics);
    }

    /**
     * Records the default expression: returns the IR root with the
     * field type as the root's expected-type context.
     */
    DefaultIrNode record(ExpressionNode root, SymbolTable scope,
                         Type fieldType) {
        return recordExpression(root, scope,
            fieldType == null ? List.of() : List.of(fieldType), true);
    }

    // =====================================================================
    // Expression walk
    // =====================================================================

    private DefaultIrNode recordExpression(ExpressionNode expr,
            SymbolTable scope, List<Type> contexts, boolean evaluatorScope) {
        return recordExpression(expr, scope, contexts, null,
            evaluatorScope);
    }

    /**
     * The provenance-carrying expression walk: {@code contextProvider}
     * is the import whose checked declaration scope resolved the
     * subtree's contextual class type, when the route that introduced
     * the context names it — the resolved imported callee's parameter
     * context, the enclosing class field's declaring provider, or the
     * directly declared local variable type. A null provider means the
     * checked route names no import and the class-literal provider
     * falls back to the declaring-annotation/export-surface selection.
     */
    private DefaultIrNode recordExpression(ExpressionNode expr,
            SymbolTable scope, List<Type> contexts,
            DefaultPlanImport contextProvider, boolean evaluatorScope) {
        switch (expr) {
            case LiteralExpr lit -> {
                return node(DefaultIrNode.NodeKind.LITERAL, null,
                    typeMap.get(lit), contexts, List.of(), lit.value(),
                    null, null, lit, lit.span());
            }
            case IdentifierExpr id -> {
                return recordIdentifier(id, scope, contexts);
            }
            case BinaryExpr bin -> {
                return node(DefaultIrNode.NodeKind.BINARY,
                    bin.op().name(), typeMap.get(bin), contexts,
                    List.of(
                        recordExpression(bin.left(), scope, List.of(),
                            evaluatorScope),
                        recordExpression(bin.right(), scope, List.of(),
                            evaluatorScope)),
                    null, null, null, bin, bin.span());
            }
            case UnaryExpr un -> {
                return node(DefaultIrNode.NodeKind.UNARY,
                    un.op().name(), typeMap.get(un), contexts,
                    List.of(recordExpression(un.expr(), scope, List.of(),
                        evaluatorScope)),
                    null, null, null, un, un.span());
            }
            case CallExpr call -> {
                return recordCall(call, scope, contexts, evaluatorScope);
            }
            case MemberAccessExpr mae -> {
                return recordMemberAccess(mae, scope, contexts,
                    evaluatorScope);
            }
            case IndexExpr idx -> {
                return node(DefaultIrNode.NodeKind.INDEX, null,
                    typeMap.get(idx), contexts,
                    List.of(
                        recordExpression(idx.array(), scope, List.of(),
                            evaluatorScope),
                        recordExpression(idx.index(), scope, List.of(),
                            evaluatorScope)),
                    null, null, null, idx, idx.span());
            }
            case ArrayLiteralExpr arr -> {
                List<DefaultIrNode> elements = new ArrayList<>();
                for (ExpressionNode element : arr.elements()) {
                    // The elements share the array's expected type: a
                    // class-typed element keeps the array's provider
                    // provenance (an identity-equal sibling must never
                    // capture the construction).
                    elements.add(recordExpression(element, scope,
                        List.of(), contextProvider, evaluatorScope));
                }
                return node(DefaultIrNode.NodeKind.ARRAY_LITERAL, null,
                    typeMap.get(arr), contexts, elements, null, null,
                    null, arr, arr.span());
            }
            case ObjectLiteralExpr obj -> {
                return recordObjectLiteral(obj, scope, contexts,
                    contextProvider, evaluatorScope);
            }
            case FunctionExpr fe -> {
                return recordFunctionExpression(fe, scope, contexts);
            }
            case HasExpr has -> {
                return node(DefaultIrNode.NodeKind.HAS, null,
                    typeMap.get(has), contexts,
                    List.of(recordExpression(has.object(), scope,
                        List.of(), evaluatorScope)),
                    null, null, null, has, has.span());
            }
            case AssignmentExpr assign -> {
                Type targetType = typeMap.get(assign.target());
                return node(DefaultIrNode.NodeKind.ASSIGNMENT, null,
                    typeMap.get(assign), contexts,
                    List.of(
                        recordExpression(assign.target(), scope,
                            List.of(), evaluatorScope),
                        recordExpression(assign.value(), scope,
                            targetType == null ? List.of()
                                : List.of(targetType),
                            assignmentContextProvider(assign, scope),
                            evaluatorScope)),
                    null, null, null, assign, assign.span());
            }
            case AwaitExpression await -> {
                if (evaluatorScope) {
                    // D2 sync gate: every planned default is a sync
                    // evaluator; await at evaluator scope is E3020 at
                    // the await range.
                    awaitDiagnostics.add(CompilerDiagnostic.error(
                        DiagnosticCode.E3020, "'await' in class default",
                        await.span().range()));
                }
                return node(DefaultIrNode.NodeKind.AWAIT, null,
                    typeMap.get(await), contexts,
                    List.of(recordExpression(await.callee(), scope,
                        List.of(), evaluatorScope)),
                    null, null, null, await, await.span());
            }
            case TemplateLiteralExpr tl -> {
                List<DefaultIrNode> parts = new ArrayList<>();
                for (ExpressionNode part : tl.parts()) {
                    parts.add(recordExpression(part, scope, List.of(),
                        evaluatorScope));
                }
                return node(DefaultIrNode.NodeKind.TEMPLATE_LITERAL, null,
                    typeMap.get(tl), contexts, parts, null, null, null,
                    tl, tl.span());
            }
        }
    }

    private DefaultIrNode recordIdentifier(IdentifierExpr id,
            SymbolTable scope, List<Type> contexts) {
        DefaultIrNode.Target target = null;
        Symbol sym = scope.resolve(id.name());
        if (sym instanceof Symbol.VariableSymbol vs) {
            bindings.putIfAbsent(id.name(), vs);
            target = DefaultIrNode.Target.localBinding(id.name());
        } else if (sym instanceof Symbol.FunctionSymbol fs) {
            bindings.putIfAbsent(id.name(), fs);
            target = DefaultIrNode.Target.sameModuleResource(id.name(),
                sameModuleResource(
                    LexicalDeclarationIdentity.DeclarationKind.FUNCTION,
                    id.name()));
        } else if (sym instanceof Symbol.ClassSymbol cs) {
            bindings.putIfAbsent(id.name(), cs);
            target = DefaultIrNode.Target.sameModuleResource(id.name(),
                sameModuleResource(
                    LexicalDeclarationIdentity.DeclarationKind.CLASS,
                    id.name()));
        } else if (sym instanceof Symbol.IntrinsicSymbol is) {
            bindings.putIfAbsent(id.name(), is);
            target = DefaultIrNode.Target.intrinsic(id.name());
        } else if (sym instanceof Symbol.ModuleSymbol ms) {
            target = DefaultIrNode.Target.moduleAlias(id.name());
        }
        return node(DefaultIrNode.NodeKind.IDENTIFIER, null,
            typeMap.get(id), contexts, List.of(), null, target, null,
            id, id.span());
    }

    private DefaultIrNode recordCall(CallExpr call, SymbolTable scope,
            List<Type> contexts, boolean evaluatorScope) {
        DefaultIrNode.Target target = null;
        if (call.callee() instanceof MemberAccessExpr mae
                && mae.object() instanceof IdentifierExpr id) {
            Symbol sym = scope.resolve(id.name());
            if (sym instanceof Symbol.ModuleSymbol ms) {
                DefaultPlanImport provider = imports.get(id.name());
                Type exportType = provider == null ? null
                    : provider.exports().get(mae.field());
                if (exportType instanceof Type.Func) {
                    // D4: a call whose callee is a function declared in
                    // an imported module records the wrapper at the
                    // call-site range (pre-order: the callee's own
                    // member-access record is deduplicated away).
                    target = recordImportedFunctionWrapper(provider,
                        id.name(), ms.name() + "." + mae.field(),
                        mae.field(), call.span().range());
                }
            }
        } else if (call.callee() instanceof IdentifierExpr cid) {
            Symbol sym = scope.resolve(cid.name());
            if (sym instanceof Symbol.IntrinsicSymbol) {
                // The int/number conversion (and bytes allocation)
                // intrinsics serialize as builtin-target calls.
                target = DefaultIrNode.Target.intrinsic(cid.name());
            }
        }

        List<Type> paramContexts = paramContextsOf(call);
        List<DefaultPlanImport> paramProviders =
            paramContextProviders(call, scope);
        List<DefaultIrNode> children = new ArrayList<>();
        children.add(recordExpression(call.callee(), scope, List.of(),
            evaluatorScope));
        for (int i = 0; i < call.args().size(); i++) {
            children.add(recordExpression(call.args().get(i), scope,
                i < paramContexts.size()
                    ? List.of(paramContexts.get(i)) : List.of(),
                i < paramProviders.size() ? paramProviders.get(i) : null,
                evaluatorScope));
        }
        return node(DefaultIrNode.NodeKind.CALL, null, typeMap.get(call),
            contexts, children, null, target, null, call, call.span());
    }

    private List<Type> paramContextsOf(CallExpr call) {
        Type calleeType = typeMap.get(call.callee());
        if (calleeType instanceof Type.Func func) {
            return func.paramTypes();
        }
        return List.of();
    }

    /**
     * The contextual provider of every callee parameter: the import
     * whose resolved declaration scope introduced the parameter's class
     * context. An imported callee's signature is resolved in its own
     * module, so a parameter type spelled with a bare class name is
     * that provider's declaration while a qualified spelling names one
     * of the provider's own imports (resolvable through this module's
     * import surface only when the provider is this module). A
     * same-module callee contributes the parameter types of the
     * declaration the checked name resolved to (the nested declaration
     * shadowing a same-named module function included). A parameter
     * the route does not resolve to an owning import yields null and
     * the class literal keeps the checked export-surface selection.
     */
    private List<DefaultPlanImport> paramContextProviders(CallExpr call,
            SymbolTable scope) {
        TypeNode[] paramNodes = null;
        DefaultPlanImport owner = null;
        if (call.callee() instanceof MemberAccessExpr mae
                && mae.object() instanceof IdentifierExpr id
                && visibleBinding(scope, id.name())
                    instanceof Symbol.ModuleSymbol) {
            DefaultPlanImport provider = imports.get(id.name());
            FunctionDeclaration declaration = provider == null ? null
                : AstDeclarations.findFunctionDeclaration(
                    provider.program(), mae.field());
            if (declaration != null) {
                paramNodes = declaration.params().stream()
                    .map(Parameter::type)
                    .toArray(TypeNode[]::new);
                owner = provider;
            }
        } else if (call.callee() instanceof IdentifierExpr cid
                && visibleBinding(scope, cid.name())
                    instanceof Symbol.FunctionSymbol function) {
            // The declaration the callee name resolved to, never the
            // first top-level declaration carrying the name: a nested
            // declaration shadowing a module-level function owns its
            // parameter contexts.
            FunctionDeclaration declaration = functionDeclarationOf(
                function, cid.name(), scope);
            if (declaration != null) {
                paramNodes = declaration.params().stream()
                    .map(Parameter::type)
                    .toArray(TypeNode[]::new);
            }
        }
        if (paramNodes == null) {
            return List.of();
        }
        List<DefaultPlanImport> providers = new ArrayList<>(
            paramNodes.length);
        for (TypeNode node : paramNodes) {
            providers.add(providerForTypeNode(node, owner));
        }
        return providers;
    }

    /**
     * Pushes one statement list's not-yet-declared let names over the
     * scope that list was walked in, mirroring the checker's
     * {@code pushPendingScope}: only the names this scope declares
     * locally take part, and each is removed when its declaration
     * statement is reached. A null scope or a scope that declares no
     * direct let contributes an empty frame.
     */
    private void pushPendingScope(List<StatementNode> statements,
            SymbolTable scope) {
        Set<String> names = new LinkedHashSet<>();
        if (scope != null) {
            for (StatementNode child : statements) {
                if (child instanceof VariableDeclaration vd
                        && scope.containsLocally(vd.name())) {
                    names.add(vd.name());
                }
            }
        }
        pendingScopes.push(new PendingScope(scope, names));
    }

    /**
     * Resolves a name to the binding the declaration-order rules make
     * visible at the current walk position (docs/spec-v1.3.md
     * &sect;Name resolution — Declaration order): a let declaration is visible only after
     * its declaration, so a reference from inside the declaring
     * statement list's earlier statements — a shadowing declaration's
     * own initializer, a forward reference — resolves against the
     * enclosing scopes. This is the recorder's counterpart of the
     * checker's {@code TypeChecker.resolveVisibleBinding}; the same
     * resolution feeds every provider-provenance route (assignment
     * targets, inferred initializers, contextual callees), so the
     * pending declaration's annotation never captures the literal.
     */
    private Symbol visibleBinding(SymbolTable scope, String name) {
        Symbol sym = scope.resolve(name);
        for (PendingScope pending : pendingScopes) {
            if (sym != null && pending.scope() != null
                    && pending.remaining().contains(name)
                    && pending.scope().resolveLocal(name) == sym) {
                sym = pending.scope().parent() != null
                    ? pending.scope().parent().resolve(name) : null;
            }
        }
        return sym;
    }

    /**
     * Removes one completed declaration's name from the pending frame
     * of its own scope: the name is visible to the following statements
     * of that list (the checker's
     * {@code checkVariableDeclaration} removal).
     */
    private void declarationReached(SymbolTable scope, String name) {
        for (PendingScope pending : pendingScopes) {
            if (pending.scope() == scope) {
                pending.remaining().remove(name);
                return;
            }
        }
    }

    /**
     * The declaration of the function a callee name resolved to: the
     * defining scope of the resolved symbol is located on the checked
     * scope chain (the innermost scope whose local binding is that
     * exact symbol) and the declaration is read from the block that
     * introduced that scope, so a nested declaration shadowing a same-named
     * module-level function — or another nested declaration — supplies
     * its own parameter types. The module scope resolves to the
     * module's top-level declaration; a scope the checked walk did not
     * record has no exact declaration and falls back to the top-level
     * lookup.
     */
    private FunctionDeclaration functionDeclarationOf(
            Symbol.FunctionSymbol symbol, String name, SymbolTable scope) {
        for (SymbolTable defining = scope; defining != null;
                defining = defining.parent()) {
            if (defining.resolveLocal(name) != symbol) {
                continue;
            }
            if (defining.parent() == null) {
                return AstDeclarations.findFunctionDeclaration(program,
                    name);
            }
            StatementNode owner = scopeOwners.get(defining);
            FunctionDeclaration declaration = owner == null ? null
                : directFunctionDeclaration(owner, name);
            if (declaration != null) {
                return declaration;
            }
            break;
        }
        return AstDeclarations.findFunctionDeclaration(program, name);
    }

    /**
     * The direct function declaration of the given name in the block
     * that introduced one lexical scope, or null: a declaration nested
     * deeper in the same block belongs to another scope.
     */
    private static FunctionDeclaration directFunctionDeclaration(
            StatementNode owner, String name) {
        if (!(owner instanceof Block block)) {
            return null;
        }
        for (StatementNode statement : block.statements()) {
            FunctionDeclaration declaration =
                AstDeclarations.functionDeclarationOf(statement);
            if (declaration != null && declaration.name().equals(name)) {
                return declaration;
            }
        }
        return null;
    }

    /**
     * The provider one declared type node spells for a class: a bare
     * class name is the owner module's own declaration (that import),
     * a qualified {@code Alias.Class} names one of the owner's imports
     * — resolved through this module's import surface when the owner is
     * this module. A class literal's context is always a directly named
     * class type (the checker contextualizes a construction only
     * against a {@code Type.Class}), so a compound node carries no
     * provider. Returns null when the node names no resolvable class
     * provider.
     */
    private DefaultPlanImport providerForTypeNode(TypeNode typeNode,
            DefaultPlanImport owner) {
        return switch (typeNode) {
            case QualifiedType qualified -> owner == null
                ? imports.get(qualified.moduleName()) : null;
            case NamedType named -> owner != null
                    && AstDeclarations.findClassDeclaration(owner.program(),
                        named.name()) != null
                ? owner : null;
            // A compound annotation contributes the provider of its
            // class component: an array, nullable, or function type of
            // one imported class has that provider's demands.
            case ArrayType array -> providerForTypeNode(array.elementType(),
                owner);
            case NullableType nullable -> providerForTypeNode(
                nullable.innerType(), owner);
            case FunctionType function -> providerForTypeNode(
                function.returnType(), owner);
        };
    }

    private DefaultIrNode recordMemberAccess(MemberAccessExpr mae,
            SymbolTable scope, List<Type> contexts,
            boolean evaluatorScope) {
        DefaultIrNode.Target target = null;
        if (mae.object() instanceof IdentifierExpr id) {
            Symbol sym = scope.resolve(id.name());
            if (sym instanceof Symbol.ModuleSymbol ms) {
                DefaultPlanImport provider = imports.get(id.name());
                Type exportType = provider == null ? null
                    : provider.exports().get(mae.field());
                if (exportType instanceof Type.Func) {
                    // An imported function referenced as a first-class
                    // value: the wrapper reference records at the member
                    // access range (a call site records first at the
                    // call range and deduplicates this).
                    target = recordImportedFunctionWrapper(provider,
                        id.name(), ms.name() + "." + mae.field(),
                        mae.field(), mae.span().range());
                } else if (exportType instanceof Type.Class clsType) {
                    // A class member in expression position cannot
                    // type-check; defensive target only, no occurrence
                    // (contextual class literals record their plan
                    // reference).
                    SemanticResourceIdentity resource = importedResource(
                        provider,
                        LexicalDeclarationIdentity.DeclarationKind.CLASS,
                        mae.field());
                    target = DefaultIrNode.Target.importedResource(
                        ms.name() + "." + mae.field(), resource);
                }
            }
        }
        // Table member reads carry their resolved type as the
        // contextual descriptor (the E3003 contextual-read semantics).
        List<Type> effectiveContexts = contexts;
        Type objectType = typeMap.get(mae.object());
        Type selfType = typeMap.get(mae);
        if (objectType instanceof Type.Table
                && selfType != null && !(selfType instanceof Type.Error)) {
            effectiveContexts = List.of(selfType);
        }
        return node(DefaultIrNode.NodeKind.MEMBER_ACCESS, null,
            typeMap.get(mae), effectiveContexts,
            List.of(recordExpression(mae.object(), scope, List.of(),
                evaluatorScope)),
            null, target, null, mae, mae.span());
    }

    private DefaultIrNode recordObjectLiteral(ObjectLiteralExpr obj,
            SymbolTable scope, List<Type> contexts,
            DefaultPlanImport contextProvider, boolean evaluatorScope) {
        Type resolvedType = typeMap.get(obj);
        DefaultIrNode.Target target = null;
        // The program declaring the constructed class and its import (a
        // null import means this module): the nested literals' provider
        // provenance, read from that program's own field declarations.
        ProgramNode declaringProgram = null;
        DefaultPlanImport declaringProvider = null;
        if (resolvedType instanceof Type.Class cls) {
            // The import the literal's checked context names is the exact
            // declaring provider (the declared annotation's qualified
            // spelling, the resolved imported callee's parameter context,
            // or the enclosing field's declaring provider): a same-identity
            // local declaration of the same name must never capture the
            // construction of an imported context, so the textual
            // same-module lookup runs only when the context names no
            // import.
            Symbol.ClassSymbol sameModule = null;
            if (!contextNamesImport(cls, contextProvider)) {
                Symbol sym = scope.resolve(cls.name());
                if (sym instanceof Symbol.ClassSymbol cs
                        && cs.identity().equals(cls.identity())) {
                    sameModule = cs;
                }
            }
            if (sameModule != null) {
                bindings.putIfAbsent(cls.name(), sameModule);
                target = DefaultIrNode.Target.sameModuleResource(
                    cls.name(),
                    sameModuleResource(
                        LexicalDeclarationIdentity.DeclarationKind.CLASS,
                        cls.name()));
                declaringProgram = program;
            } else if (cls.identity().moduleIdentity()
                    instanceof CanonicalModuleIdentity.BuiltinModule) {
                // The intrinsic Error class construction.
                target = DefaultIrNode.Target.intrinsic(cls.name());
            } else {
                DefaultPlanImport provider =
                    providerForClass(cls, contextProvider);
                if (provider != null) {
                    SemanticResourceIdentity resource = importedResource(
                        provider,
                        LexicalDeclarationIdentity.DeclarationKind.CLASS,
                        cls.name());
                    target = DefaultIrNode.Target.importedResource(
                        cls.name(), resource);
                    declaringProgram = provider.program();
                    declaringProvider = provider;
                    if (provider.publishesClassPlans()) {
                        // D4: a contextual class literal typed as a
                        // plan-bearing class declared in an imported
                        // module records the class default plan at the
                        // literal range. Host-declared classes produce
                        // no plan (the host supplies defaults at load)
                        // and publish no occurrence.
                        String alias = aliasFor(provider, cls.name());
                        recordOccurrence(
                            RuntimeResourceReference.Kind
                                .IMPORTED_CLASS_DEFAULT_PLAN,
                            provider, alias, resource,
                            obj.span().range());
                        classLiteralProviders.put(obj.span().range(),
                            alias);
                    }
                }
            }
        }
        List<DefaultIrNode> children = new ArrayList<>();
        for (Property property : obj.properties()) {
            DefaultPlanImport childProvider =
                resolvedType instanceof Type.Class cls
                    && declaringProgram != null
                ? providerForDeclaredField(declaringProgram,
                    declaringProvider, cls, property.name())
                : null;
            children.add(recordExpression(property.value(), scope,
                List.of(), childProvider, evaluatorScope));
        }
        List<Type> effectiveContexts = contexts;
        if (resolvedType instanceof Type.Class) {
            effectiveContexts = List.of(resolvedType);
        }
        return node(DefaultIrNode.NodeKind.OBJECT_LITERAL, null,
            resolvedType, effectiveContexts, children, null, target,
            null, obj, obj.span());
    }

    List<DefaultIrNode> recordFunctionBody(FunctionDeclaration fd,
            SymbolTable moduleScope) {
        SymbolTable bodyScope = scopeMap.get(fd.body());
        if (bodyScope == null) {
            bodyScope = moduleScope.enterScope();
            for (Parameter p : fd.params()) {
                Type paramType = typeResolver.resolveTypeNode(p.type(),
                    bodyScope);
                bodyScope.define(p.name(), new Symbol.VariableSymbol(
                    p.name(), paramType, true, null, p.type()));
            }
        }
        List<DefaultIrNode> statements = new ArrayList<>();
        DefaultPlanImport savedReturnProvider = enclosingReturnProvider;
        enclosingReturnProvider = providerForTypeNode(fd.returnType(),
            null);
        pushPendingScope(fd.body().statements(), bodyScope);
        try {
            for (StatementNode stmt : fd.body().statements()) {
                statements.add(walkStatement(stmt, bodyScope, false));
            }
        } finally {
            enclosingReturnProvider = savedReturnProvider;
            pendingScopes.pop();
        }
        return List.copyOf(statements);
    }

    private DefaultIrNode recordFunctionExpression(FunctionExpr fe,
            SymbolTable scope, List<Type> contexts) {
        List<DefaultIrNode> bodyChildren = new ArrayList<>();
        SymbolTable bodyScope = scopeMap.get(fe.body());
        if (bodyScope == null) {
            // The pass-1 resolver never walks class-default
            // subexpressions, so a function-expression body inside a
            // default has no recorded scope; mirror the checker's
            // fallback: a fresh child scope carrying the parameters.
            bodyScope = scope.enterScope();
            for (Parameter p : fe.params()) {
                Type paramType = typeResolver.resolveTypeNode(p.type(),
                    bodyScope);
                bodyScope.define(p.name(), new Symbol.VariableSymbol(
                    p.name(), paramType, true, null, p.type()));
            }
        }
        // Await inside a nested function-expression body is not at
        // evaluator scope (D2).
        DefaultPlanImport savedReturnProvider = enclosingReturnProvider;
        enclosingReturnProvider = providerForTypeNode(fe.returnType(),
            null);
        pushPendingScope(fe.body().statements(), bodyScope);
        try {
            for (StatementNode stmt : fe.body().statements()) {
                bodyChildren.add(walkStatement(stmt, bodyScope, false));
            }
        } finally {
            enclosingReturnProvider = savedReturnProvider;
            pendingScopes.pop();
        }
        return node(DefaultIrNode.NodeKind.FUNCTION_EXPRESSION, null,
            typeMap.get(fe), contexts, bodyChildren, null, null, null,
            fe, fe.span());
    }

    // =====================================================================
    // Statement walk (function bodies nested in a default)
    // =====================================================================

    private DefaultIrNode walkStatement(StatementNode stmt,
            SymbolTable scope, boolean evaluatorScope) {
        SymbolTable stmtScope = scopeMap.get(stmt);
        // The scope the construct itself was checked in (effectively
        // final for the Optional lambdas below).
        final SymbolTable effectiveScope =
            stmtScope != null ? stmtScope : scope;
        switch (stmt) {
            case Block b -> {
                List<DefaultIrNode> children = new ArrayList<>();
                boolean tracked = stmtScope != null;
                if (tracked) {
                    pushPendingScope(b.statements(), effectiveScope);
                }
                try {
                    for (StatementNode s : b.statements()) {
                        children.add(walkStatement(s, effectiveScope,
                            evaluatorScope));
                    }
                } finally {
                    if (tracked) {
                        pendingScopes.pop();
                    }
                }
                return node(DefaultIrNode.NodeKind.BLOCK, null, null,
                    List.of(), children, null, null, null, b,
                    b.span().range());
            }
            case VariableDeclaration vd -> {
                List<DefaultIrNode> children = new ArrayList<>();
                if (vd.initializer() != null) {
                    // The declared annotation is the checked provenance
                    // of the initializer's class context: a qualified
                    // spelling names its provider import directly. An
                    // inferred declaration inherits the resolved route
                    // of its initializer expression, so the binding
                    // keeps that provider for its later assignments
                    // (never the first identity-equal export).
                    DefaultPlanImport declaredProvider = vd.typeAnnotation()
                        .map(typeNode -> providerForTypeNode(typeNode, null))
                        .orElse(null);
                    if (declaredProvider == null) {
                        declaredProvider = expressionContextProvider(
                            vd.initializer(), effectiveScope);
                    }
                    Symbol bound = effectiveScope.resolve(vd.name());
                    if (bound instanceof Symbol.VariableSymbol variable
                            && declaredProvider != null) {
                        bindingProviders.put(variable, declaredProvider);
                    }
                    children.add(recordExpression(vd.initializer(),
                        effectiveScope, List.of(), declaredProvider,
                        evaluatorScope));
                }
                // The declaration is complete: the name is visible to
                // the following statements of its own statement list,
                // so a later assignment to it resolves to this binding
                // (docs/spec-v1.3.md §Name resolution — Declaration order).
                declarationReached(effectiveScope, vd.name());
                return node(DefaultIrNode.NodeKind.VARIABLE_DECLARATION,
                    null, null, List.of(), children, null, null,
                    vd.name(), vd, vd.span().range());
            }
            case ExpressionStatement es -> {
                return node(DefaultIrNode.NodeKind.EXPRESSION_STATEMENT,
                    null, null, List.of(),
                    List.of(recordExpression(es.expr(), effectiveScope,
                        List.of(), evaluatorScope)),
                    null, null, null, es, es.span().range());
            }
            case ReturnStatement rs -> {
                List<DefaultIrNode> children = new ArrayList<>();
                // The enclosing function's declared return type is the
                // returned expression's checked context: its provider
                // provenance disambiguates identity-equal siblings.
                rs.expr().ifPresent(expr -> children.add(
                    recordExpression(expr, effectiveScope, List.of(),
                        enclosingReturnProvider, evaluatorScope)));
                return node(DefaultIrNode.NodeKind.RETURN, null, null,
                    List.of(), children, null, null, null, rs,
                    rs.span().range());
            }
            case ThrowStatement th -> {
                return node(DefaultIrNode.NodeKind.THROW, null, null,
                    List.of(),
                    List.of(recordExpression(th.expr(), effectiveScope,
                        List.of(), evaluatorScope)),
                    null, null, null, th, th.span().range());
            }
            case IfStatement is -> {
                List<DefaultIrNode> children = new ArrayList<>();
                children.add(recordExpression(is.condition(),
                    effectiveScope, List.of(), evaluatorScope));
                children.add(walkStatement(is.thenBlock(), effectiveScope,
                    evaluatorScope));
                is.elseBranch().ifPresent(branch -> {
                    switch (branch) {
                        case Either.Left<IfStatement, Block> left ->
                            children.add(walkStatement(left.value(),
                                effectiveScope, evaluatorScope));
                        case Either.Right<IfStatement, Block> right ->
                            children.add(walkStatement(right.value(),
                                effectiveScope, evaluatorScope));
                    }
                });
                return node(DefaultIrNode.NodeKind.IF, null, null,
                    List.of(), children, null, null, null, is,
                    is.span().range());
            }
            case WhileStatement ws -> {
                return node(DefaultIrNode.NodeKind.WHILE, null, null,
                    List.of(),
                    List.of(
                        recordExpression(ws.condition(), effectiveScope,
                            List.of(), evaluatorScope),
                        walkStatement(ws.body(), effectiveScope,
                            evaluatorScope)),
                    null, null, null, ws, ws.span().range());
            }
            case ForStatement fs -> {
                List<DefaultIrNode> children = new ArrayList<>();
                fs.init().ifPresent(init -> {
                    switch (init) {
                        case ForInit.VarDecl vd -> {
                            // The loop-head let is not visible to its own
                            // initializer (docs/spec-v1.3.md §Name resolution —
                            // Declaration order): a self reference there names the
                            // enclosing binding, exactly like the
                            // checker's loop-head pending frame.
                            SymbolTable loopScope = scopeMap.get(fs);
                            boolean tracked = loopScope != null;
                            if (tracked) {
                                pushPendingScope(List.of(vd.decl()),
                                    loopScope);
                            }
                            try {
                                children.add(walkStatement(vd.decl(),
                                    effectiveScope, evaluatorScope));
                            } finally {
                                if (tracked) {
                                    pendingScopes.pop();
                                }
                            }
                        }
                        case ForInit.AssignExpr ae ->
                            children.add(recordExpression(ae.expr(),
                                effectiveScope, List.of(),
                                evaluatorScope));
                    }
                });
                fs.condition().ifPresent(cond -> children.add(
                    recordExpression(cond, effectiveScope, List.of(),
                        evaluatorScope)));
                fs.update().ifPresent(update -> children.add(
                    recordExpression(update, effectiveScope, List.of(),
                        evaluatorScope)));
                children.add(walkStatement(fs.body(), effectiveScope,
                    evaluatorScope));
                return node(DefaultIrNode.NodeKind.FOR, null, null,
                    List.of(), children, null, null, null, fs,
                    fs.span().range());
            }
            case ForOfStatement fos -> {
                return node(DefaultIrNode.NodeKind.FOR_OF, null, null,
                    List.of(),
                    List.of(
                        recordExpression(fos.iterable(), effectiveScope,
                            List.of(), evaluatorScope),
                        walkStatement(fos.body(), effectiveScope,
                            evaluatorScope)),
                    null, null, fos.varName(), fos, fos.span().range());
            }
            case TryStatement ts -> {
                return node(DefaultIrNode.NodeKind.TRY, null, null,
                    List.of(),
                    List.of(
                        walkStatement(ts.tryBlock(), effectiveScope,
                            evaluatorScope),
                        walkStatement(ts.catchBlock(), effectiveScope,
                            evaluatorScope)),
                    null, null, null, ts, ts.span().range());
            }
            case DeleteStatement ds -> {
                return node(DefaultIrNode.NodeKind.DELETE, null, null,
                    List.of(),
                    List.of(recordExpression(ds.target(), effectiveScope,
                        List.of(), evaluatorScope)),
                    null, null, null, ds, ds.span().range());
            }
            case BreakStatement bs -> {
                return node(DefaultIrNode.NodeKind.BREAK, null, null,
                    List.of(), List.of(), null, null, null, bs,
                    bs.span().range());
            }
            case ContinueStatement cs -> {
                return node(DefaultIrNode.NodeKind.CONTINUE, null, null,
                    List.of(), List.of(), null, null, null, cs,
                    cs.span().range());
            }
            case FunctionDeclaration fd -> {
                // A nested function declaration inside a default: its
                // body is not at evaluator scope (it executes when the
                // nested function is called). Class declarations inside
                // are not descended into (their defaults are planned
                // separately).
                List<DefaultIrNode> children = new ArrayList<>();
                if (fd.body() != null) {
                    // The nested declaration's own return type names its
                    // body's return-context provider (the enclosing
                    // function's annotation never applies).
                    DefaultPlanImport savedReturnProvider =
                        enclosingReturnProvider;
                    enclosingReturnProvider = providerForTypeNode(
                        fd.returnType(), null);
                    try {
                        children.add(walkStatement(fd.body(),
                            effectiveScope, false));
                    } finally {
                        enclosingReturnProvider = savedReturnProvider;
                    }
                }
                DefaultIrNode.Target target = null;
                if (fd.body() != null) {
                    target = DefaultIrNode.Target.sameModuleResource(
                        fd.name(),
                        sameModuleResource(
                            LexicalDeclarationIdentity
                                .DeclarationKind.FUNCTION,
                            fd.name()));
                }
                return node(DefaultIrNode.NodeKind.FUNCTION_DECLARATION,
                    null, null, List.of(), children, null, target,
                    fd.name(), fd, fd.span().range());
            }
            case ClassDeclaration cd -> {
                // The nested class declaration's plan content and gates
                // are its own plan's domain; the IR node carries the
                // declaration and its same-module resource identity for
                // the serializer's inline canonical plan projection.
                DefaultIrNode.Target target =
                    DefaultIrNode.Target.sameModuleResource(cd.name(),
                        sameModuleResource(
                            LexicalDeclarationIdentity
                                .DeclarationKind.CLASS,
                            cd.name()));
                return node(DefaultIrNode.NodeKind.CLASS_DECLARATION,
                    null, null, List.of(), List.of(), null, target,
                    cd.name(), cd, cd.span().range());
            }
            case ImportDeclaration id -> {
                return node(DefaultIrNode.NodeKind.BLOCK, null, null,
                    List.of(), List.of(), null, null, null, id,
                    id.span().range());
            }
            case ExportDeclaration ed -> {
                return walkStatement(ed.declaration(), effectiveScope,
                    evaluatorScope);
            }
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static DefaultIrNode node(DefaultIrNode.NodeKind kind,
            String operatorKind, Type resultType, List<Type> contexts,
            List<DefaultIrNode> children, LiteralValue literalValue,
            DefaultIrNode.Target target, String declaredName,
            ExpressionNode expression, Span span) {
        return new DefaultIrNode(kind, operatorKind, resultType, contexts,
            children, literalValue, target, declaredName, null,
            expression, span.range());
    }

    private static DefaultIrNode node(DefaultIrNode.NodeKind kind,
            String operatorKind, Type resultType, List<Type> contexts,
            List<DefaultIrNode> children, LiteralValue literalValue,
            DefaultIrNode.Target target, String declaredName,
            StatementNode statement, DiagnosticRange range) {
        return new DefaultIrNode(kind, operatorKind, resultType, contexts,
            children, literalValue, target, declaredName, statement,
            null, range);
    }

    private void recordOccurrence(RuntimeResourceReference.Kind kind,
            DefaultPlanImport provider, String alias,
            SemanticResourceIdentity resource, DiagnosticRange range) {
        if (resource == null) {
            return;
        }
        occurrences.putIfAbsent(resource, new DefaultResourceOccurrence(
            kind, location.semanticModuleIdentity(),
            provider.location().semanticModuleIdentity(), alias, resource,
            range));
    }

    /**
     * The provider that owns the class type's canonical class identity:
     * the import whose checked surface carries the demanded class, or
     * null (an unknown or builtin module).
     *
     * <p>The public module identity is directory-keyed (the relative
     * module components exclude the file stem), so sibling modules in
     * one directory share one classification identity: an
     * identity-equality match alone is ambiguous, and a name-bearing
     * sibling program is not proof of ownership (a sibling's
     * non-exported class can carry the demanded name without being
     * reachable under it). The provider is therefore resolved from the
     * checked import provenance, over the consumer's import
     * declarations:</p>
     * <ol>
     *   <li>the contextual provider the checked route names
     *       ({@code provenance}), when its program declares the demanded
     *       class and its classification carries the class identity —
     *       the resolved imported callee's parameter context, the
     *       enclosing class field's declaring provider, the directly
     *       declared variable type, or the assigned binding's own
     *       declaration (annotation or recorded initializer route);</li>
     *   <li>the import the occurrence's resolved qualified alias names
     *       (the checked spelling of the declaring annotation), when its
     *       export surface carries the class identity;</li>
     *   <li>otherwise the first import, in import-declaration order,
     *       whose export surface carries the class identity — the only
     *       route by which the checker resolves a foreign class for an
     *       importer;</li>
     *   <li>otherwise the first identity-equal import (the prior
     *       selection), which the serializer's exact-declaration check
     *       still fails closed at when the selected module declares no
     *       such class (never a silent fallback, never another class's
     *       serialization).</li>
     * </ol>
     */
    private DefaultPlanImport providerForClass(Type.Class cls,
            DefaultPlanImport provenance) {
        CanonicalModuleIdentity target = cls.identity().moduleIdentity();
        if (target instanceof CanonicalModuleIdentity.BuiltinModule) {
            return null;
        }
        if (provenance != null && ownsClass(provenance, cls, target)) {
            return provenance;
        }
        String qualified = qualifiedClassAliases.get(cls.name());
        DefaultPlanImport identityMatch = null;
        DefaultPlanImport exportedMatch = null;
        for (StatementNode stmt : program.statements()) {
            if (!(stmt instanceof ImportDeclaration imp)) {
                continue;
            }
            DefaultPlanImport provider = imports.get(imp.alias());
            if (provider == null) {
                continue;
            }
            CanonicalModuleIdentity providerIdentity =
                classification.get(provider.modulePath());
            if (!target.equals(providerIdentity)) {
                continue;
            }
            if (identityMatch == null) {
                identityMatch = provider;
            }
            if (!exportsClass(provider, cls)) {
                continue;
            }
            if (imp.alias().equals(qualified)) {
                // The checked qualified spelling names this import's
                // class: the exact defining provider.
                return provider;
            }
            if (exportedMatch == null) {
                exportedMatch = provider;
            }
        }
        return exportedMatch != null ? exportedMatch : identityMatch;
    }

    /**
     * Whether the literal's checked context names an import that owns the
     * demanded class: the contextual provider the checked route resolved
     * (the declared annotation's qualified spelling, the resolved
     * imported callee's parameter context, or the enclosing field's
     * declaring provider), or the occurrence's recorded qualified alias.
     * A context naming an import selects that exact declaration, so a
     * same-identity local declaration of the same name never captures the
     * construction (the textual scope lookup stays the retained fallback
     * for a context that names no import).
     */
    private boolean contextNamesImport(Type.Class cls,
            DefaultPlanImport contextProvider) {
        CanonicalModuleIdentity target = cls.identity().moduleIdentity();
        if (contextProvider != null && ownsClass(contextProvider, cls, target)) {
            return true;
        }
        String qualified = qualifiedClassAliases.get(cls.name());
        if (qualified != null) {
            DefaultPlanImport provider = imports.get(qualified);
            if (provider != null && ownsClass(provider, cls, target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when one import is the declared owner of the demanded class:
     * its program declares the class name and its module classification
     * carries the class's module identity (the directory-keyed identity
     * alone is ambiguous across sibling modules).
     */
    private boolean ownsClass(DefaultPlanImport provider, Type.Class cls,
            CanonicalModuleIdentity classIdentity) {
        return AstDeclarations.findClassDeclaration(provider.program(),
                cls.name()) != null
            && classIdentity.equals(
                classification.get(provider.modulePath()));
    }

    /**
     * The contextual provider of an assignment value: the assigned
     * binding's exact resolved provenance for an identifier target (its
     * own declared type annotation, else the recorded route of the
     * declaration's initializer), the same provenance of the array a
     * class element is assigned into (an index target), or the assigned
     * member's declared type node read from the owning class's exact
     * declaration. A target form the checked symbol surface does not
     * tie to one declaration (an unresolved name, an unrecorded route)
     * yields null and the class literal keeps the checked export-surface
     * selection.
     */
    private DefaultPlanImport assignmentContextProvider(
            AssignmentExpr assign, SymbolTable scope) {
        return switch (assign.target()) {
            case IdentifierExpr id -> bindingContextProvider(id, scope);
            case IndexExpr index -> expressionContextProvider(
                index.array(), scope);
            case MemberAccessExpr mae -> memberContextProvider(mae, scope);
            default -> null;
        };
    }

    /**
     * The provider a resolved variable binding names for a constructed
     * value: the declaration's own annotation when it carries one (a
     * qualified class spelling names the defining provider directly, so
     * an identity-equal sibling module and the import order never
     * capture the literal), else the route recorded for the binding when
     * its declaration was walked (an inferred binding inherits the
     * resolved provenance of its initializer expression).
     */
    private DefaultPlanImport bindingContextProvider(IdentifierExpr id,
            SymbolTable scope) {
        Symbol sym = visibleBinding(scope, id.name());
        if (!(sym instanceof Symbol.VariableSymbol variable)) {
            return null;
        }
        if (variable.declaredType() != null) {
            return providerForTypeNode(variable.declaredType(), null);
        }
        return bindingProviders.get(variable);
    }

    /**
     * The provider the object of a member target (or of an indexed member
     * target) names for a constructed value: the assigned member's
     * declared type node read from the owning class's exact declaration
     * (the same-module program, or the checked provider of the owner's
     * own resolved route). Null when the checked symbol surface does not
     * tie the member to one declaration.
     */
    private DefaultPlanImport memberContextProvider(MemberAccessExpr mae,
            SymbolTable scope) {
        if (!(typeMap.get(mae.object()) instanceof Type.Class owner)) {
            return null;
        }
        Symbol sym = visibleBinding(scope, owner.name());
        if (sym instanceof Symbol.ClassSymbol cs
                && cs.identity().equals(owner.identity())) {
            DefaultPlanImport declared = providerForDeclaredField(program,
                null, owner, mae.field());
            if (declared != null) {
                return declared;
            }
        }
        // The owner expression's own checked route names the owner's
        // declaring provider (a binding declared as ir.Wrap, an inferred
        // binding initialized from an ir route, or a member/index read of
        // one): that provider's program carries the member's field
        // declaration, so an identity-equal sibling module and the import
        // order never capture the assigned literal.
        DefaultPlanImport ownerProvider = expressionContextProvider(
            mae.object(), scope);
        if (ownerProvider != null && ownsClass(ownerProvider, owner,
                owner.identity().moduleIdentity())) {
            return providerForDeclaredField(ownerProvider.program(),
                ownerProvider, owner, mae.field());
        }
        DefaultPlanImport provider = providerForClass(owner, null);
        return provider == null ? null : providerForDeclaredField(
            provider.program(), provider, owner, mae.field());
    }

    /**
     * The provider a value expression's checked route names for a class
     * value, used for an inferred binding's provenance: an identifier
     * carries its binding's provenance, a member read its declared
     * field's, an index read its array's (the element keeps the array
     * declaration's provider), an assignment its value's, and a call the
     * resolved callee's declared return type (the imported callee's own
     * declaration names its module's classes; a same-module callee keeps
     * this module's import spellings). Null when the route names no
     * owning import: the caller then keeps the checked export-surface
     * selection.
     */
    private DefaultPlanImport expressionContextProvider(ExpressionNode expr,
            SymbolTable scope) {
        return switch (expr) {
            case IdentifierExpr id -> bindingContextProvider(id, scope);
            case MemberAccessExpr mae -> memberContextProvider(mae, scope);
            case IndexExpr index -> expressionContextProvider(
                index.array(), scope);
            case AssignmentExpr assign -> expressionContextProvider(
                assign.value(), scope);
            case CallExpr call -> callReturnContextProvider(call, scope);
            default -> null;
        };
    }

    /**
     * The provider the resolved callee's declared return type names for
     * a call result: an imported callee's declaration is resolved in its
     * own module (a bare class spelling is that module's own class), a
     * same-module callee's declaration in this module (a qualified
     * spelling names one of this module's imports). Null when the callee
     * is unresolved, carries no declaration, or its return type names no
     * owning import.
     */
    private DefaultPlanImport callReturnContextProvider(CallExpr call,
            SymbolTable scope) {
        if (call.callee() instanceof MemberAccessExpr mae
                && mae.object() instanceof IdentifierExpr id
                && visibleBinding(scope, id.name())
                    instanceof Symbol.ModuleSymbol) {
            DefaultPlanImport provider = imports.get(id.name());
            if (provider == null) {
                return null;
            }
            FunctionDeclaration declaration =
                AstDeclarations.findFunctionDeclaration(provider.program(),
                    mae.field());
            return declaration == null ? null : providerForTypeNode(
                declaration.returnType(), provider);
        }
        if (call.callee() instanceof IdentifierExpr cid
                && visibleBinding(scope, cid.name())
                    instanceof Symbol.FunctionSymbol function) {
            FunctionDeclaration declaration = functionDeclarationOf(
                function, cid.name(), scope);
            return declaration == null ? null : providerForTypeNode(
                declaration.returnType(), null);
        }
        return null;
    }

    /**
     * The contextual provider of one nested class literal: the property
     * name selects the owning class's field declaration in its declaring
     * program, whose declared type node names the nested class's
     * provider (a bare name is the declaring module's own declaration, a
     * qualified name one of that module's imports). Null when the
     * declaration or field is absent, or its spelling names no owning
     * import: the caller then keeps the checked export-surface
     * selection.
     */
    private DefaultPlanImport providerForDeclaredField(
            ProgramNode declaringProgram,
            DefaultPlanImport declaringProvider, Type.Class owner,
            String fieldName) {
        ClassDeclaration declaration = AstDeclarations.findClassDeclaration(
            declaringProgram, owner.name());
        if (declaration == null) {
            return null;
        }
        for (ClassField field : declaration.fields()) {
            if (field.name().equals(fieldName)) {
                return providerForTypeNode(field.type(),
                    declaringProvider);
            }
        }
        return null;
    }

    /**
     * True when the import's checked export surface carries the demanded
     * class identity under its declared name: the importer's only route
     * to a foreign class type (the checker's qualified type resolution
     * reads the module's export map, and an exported signature's class
     * types flow through the same surface). A non-exported sibling
     * declaration of the same name is never a match.
     */
    private static boolean exportsClass(DefaultPlanImport provider,
            Type.Class cls) {
        Type exported = provider.exports().get(cls.name());
        return exported instanceof Type.Class exportedClass
            && exportedClass.identity().equals(cls.identity());
    }

    /**
     * The import alias to attribute an imported class-literal
     * occurrence to: the alias the declaring field type annotation
     * names for the class when present (the resolved spelling), else
     * the first alias importing the provider (deterministic).
     */
    private String aliasFor(DefaultPlanImport provider, String className) {
        String qualified = qualifiedClassAliases.get(className);
        if (qualified != null && imports.containsKey(qualified)
                && imports.get(qualified).sourcePath()
                    .equals(provider.sourcePath())) {
            return qualified;
        }
        for (Map.Entry<String, DefaultPlanImport> entry
                : imports.entrySet()) {
            if (entry.getValue().sourcePath()
                    .equals(provider.sourcePath())) {
                return entry.getKey();
            }
        }

        return provider.modulePath();
    }

    /**
     * Records the imported-function wrapper reference of one imported
     * call or first-class member read, and returns its target: the
     * occurrence keys on the wrapper kind, the provider, the alias, and
     * the call-site or member-access range.
     */
    private DefaultIrNode.Target recordImportedFunctionWrapper(
            DefaultPlanImport provider, String alias, String qualifiedName,
            String field, DiagnosticRange range) {
        SemanticResourceIdentity resource = importedResource(provider,
            LexicalDeclarationIdentity.DeclarationKind.FUNCTION, field);
        recordOccurrence(
            RuntimeResourceReference.Kind.IMPORTED_FUNCTION_WRAPPER,
            provider, alias, resource, range);
        return DefaultIrNode.Target.importedResource(qualifiedName, resource);
    }

    private SemanticResourceIdentity sameModuleResource(
            LexicalDeclarationIdentity.DeclarationKind kind, String name) {
        return declarationResource(program, location.semanticModuleIdentity(),
            modulePath, kind, name);
    }

    private SemanticResourceIdentity importedResource(
            DefaultPlanImport provider,
            LexicalDeclarationIdentity.DeclarationKind kind, String name) {
        return declarationResource(provider.program(),
            provider.location().semanticModuleIdentity(),
            provider.modulePath(), kind, name);
    }

    /**
     * The semantic resource identity of one same-module or imported
     * declaration: the declaration's range when findable (a function's
     * synthetic JSON export anchors on its class), the program's range
     * otherwise.
     */
    private static SemanticResourceIdentity declarationResource(
            ProgramNode program, SemanticModuleIdentity moduleIdentity,
            String modulePath,
            LexicalDeclarationIdentity.DeclarationKind kind, String name) {
        StatementNode declaration = kind
                == LexicalDeclarationIdentity.DeclarationKind.FUNCTION
            ? AstDeclarations.findFunctionDeclaration(program, name)
            : AstDeclarations.findClassDeclaration(program, name);
        DiagnosticRange range;
        if (declaration != null) {
            range = declaration.span().range();
        } else if (kind
                == LexicalDeclarationIdentity.DeclarationKind.FUNCTION) {
            ClassDeclaration synthetic =
                AstDeclarations.jsonableClassForSynthetic(program, name);
            range = synthetic != null ? synthetic.span().range()
                : program.span().range();
        } else {
            range = program.span().range();
        }
        return new SemanticResourceIdentity(
            moduleIdentity, kind,
            new LexicalDeclarationIdentity(kind, name, modulePath, range));
    }

    /**
     * Collects className &rarr; alias pairs from a field type
     * annotation: every {@code Alias.ClassName} qualified type in the
     * annotation (recursively through array, nullable, and function
     * types), first occurrence wins.
     */
    static Map<String, String> qualifiedClassAliasesOf(TypeNode typeNode) {
        Map<String, String> aliases = new LinkedHashMap<>();
        collectQualifiedAliases(typeNode, aliases);
        return aliases;
    }

    private static void collectQualifiedAliases(TypeNode typeNode,
            Map<String, String> aliases) {
        switch (typeNode) {
            case deal.ast.QualifiedType q -> aliases.putIfAbsent(
                q.typeName(), q.moduleName());
            case deal.ast.ArrayType arr ->
                collectQualifiedAliases(arr.elementType(), aliases);
            case deal.ast.NullableType n ->
                collectQualifiedAliases(n.innerType(), aliases);
            case deal.ast.FunctionType ft -> {
                for (deal.ast.FunctionTypeParam p : ft.params()) {
                    collectQualifiedAliases(p.type(), aliases);
                }
                collectQualifiedAliases(ft.returnType(), aliases);
            }
            default -> {
                // Primitive/class named types carry no alias.
            }
        }
    }
}
