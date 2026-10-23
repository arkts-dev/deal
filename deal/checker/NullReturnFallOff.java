package deal.checker;

import deal.ast.ArrayLiteralExpr;
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
import deal.ast.ExpressionNode;
import deal.ast.ExpressionStatement;
import deal.ast.ExportDeclaration;
import deal.ast.ForOfStatement;
import deal.ast.FunctionDeclaration;
import deal.ast.FunctionExpr;
import deal.ast.HasExpr;
import deal.ast.IdentifierExpr;
import deal.ast.IfStatement;
import deal.ast.ImportDeclaration;
import deal.ast.IndexExpr;
import deal.ast.LiteralExpr;
import deal.ast.MemberAccessExpr;
import deal.ast.NamedType;
import deal.ast.ObjectLiteralExpr;
import deal.ast.Parameter;
import deal.ast.ProgramNode;
import deal.ast.Property;
import deal.ast.ReturnStatement;
import deal.ast.StatementNode;
import deal.ast.TemplateLiteralExpr;
import deal.ast.ThrowStatement;
import deal.ast.TryStatement;
import deal.ast.TypeNode;
import deal.ast.UnaryExpr;
import deal.ast.VariableDeclaration;
import deal.ast.WhileStatement;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The null-return fall-off gate of the public compile surface (ISSUE-0717
 * defect snippets 25/26): the spec's return-type requirements
 * ({@code docs/spec-v1.3.md} §Return type requirements — "The function body must not
 * 'fall off' without a return") apply to a {@code : null} body too. The
 * gate walks a checked module's AST and reports one E5002 per
 * null-returning function declaration or function expression whose body
 * can complete normally.
 *
 * <p><b>Scope.</b> The gate is invoked by the release-owned production
 * compile (the public {@code deal.Main} path) before the production arm
 * runs. The raw
 * {@link TypeChecker#check(String, deal.checker.SymbolTable,
 * deal.checker.NameResolver, ProgramNode)} API and the harness
 * invocations keep the established implicit-null-return tolerance: the
 * composite-terminator window lowerings admit no {@code RETURN}
 * statement and rely on the lowerer's implicit null return for an
 * unterminated {@code : null} body. The gate never mutates the AST.</p>
 *
 * <p><b>The nested recursive-group exemption (ISSUE-0731).</b> A
 * function declaration nested in a function body that is a member of a
 * size&gt;=2 mutual-reference cycle keeps the landed nested-group
 * production contract (the B4 partition the lowering uses to emit one
 * {@code RECURSIVE_GROUP_INIT} and the members' implicit null returns);
 * the gate admits it exactly like the member's body does, while a
 * standalone nested declaration whose body falls off stays rejected
 * (the spec's no-fall-off rule). Module-level declarations and function
 * expressions are never exempt: they are the public surface whose open
 * {@code : null} bodies the spec requires to return.</p>
 */
public final class NullReturnFallOff {

    private NullReturnFallOff() {
        // Static utility; no instances.
    }

    /**
     * Returns one E5002 diagnostic for every null-returning function
     * declaration or expression in the module whose body does not
     * definitely return, in AST source order (a function's diagnostic
     * precedes the diagnostics of functions nested in its body).
     *
     * @param program the module's AST; non-null
     * @return the diagnostics; empty when every null-returning body
     *         returns on all reachable paths
     */
    public static List<CompilerDiagnostic> diagnostics(ProgramNode program) {
        Objects.requireNonNull(program, "program must not be null");
        List<CompilerDiagnostic> out = new ArrayList<>();
        walkStatements(program.statements(), out, false);
        return out;
    }

    // =======================================================================
    // Statements
    // =======================================================================

    private static void walkStatements(List<StatementNode> statements,
                                       List<CompilerDiagnostic> out,
                                       boolean nested) {
        // One statement list is one binding scope for the B4 partition:
        // the nested declarations that are members of a mutual-reference
        // cycle keep the landed ISSUE-0731 implicit-return contract.
        Set<FunctionDeclaration> grouped = nested
            ? mutualGroupMembers(statements) : Set.of();
        for (StatementNode statement : statements) {
            walkStatement(statement, out, nested, grouped);
        }
    }

    private static void walkStatement(StatementNode statement,
                                      List<CompilerDiagnostic> out,
                                      boolean nested,
                                      Set<FunctionDeclaration> grouped) {
        switch (statement) {
            case Block block -> walkStatements(block.statements(), out, nested);
            case FunctionDeclaration declaration -> {
                if (!grouped.contains(declaration)) {
                    checkDeclaration(declaration, out);
                }
                walkStatements(declaration.body().statements(), out, true);
            }
            case ExportDeclaration export -> walkStatement(export.declaration(), out,
                nested, grouped);
            case ClassDeclaration declaration -> {}
            case VariableDeclaration declaration ->
                walkExpression(declaration.initializer(), out);
            case ReturnStatement ret -> {
                if (ret.expr().isPresent()) {
                    walkExpression(ret.expr().get(), out);
                }
            }
            case IfStatement ifStatement -> {
                walkExpression(ifStatement.condition(), out);
                walkStatements(ifStatement.thenBlock().statements(), out, nested);
                if (ifStatement.elseBranch().isPresent()) {
                    switch (ifStatement.elseBranch().get()) {
                        case Either.Left<IfStatement, Block> left ->
                            walkStatement(left.value(), out, nested, Set.of());
                        case Either.Right<IfStatement, Block> right ->
                            walkStatements(right.value().statements(), out, nested);
                    }
                }
            }
            case WhileStatement whileStatement -> {
                walkExpression(whileStatement.condition(), out);
                walkStatements(whileStatement.body().statements(), out, nested);
            }

            case ForOfStatement forOf -> {
                walkExpression(forOf.iterable(), out);
                walkStatements(forOf.body().statements(), out, nested);
            }
            case ExpressionStatement expression -> walkExpression(expression.expr(), out);
            case DeleteStatement delete -> walkExpression(delete.target(), out);
            case TryStatement tryStatement -> {
                walkStatements(tryStatement.tryBlock().statements(), out, nested);
                walkStatements(tryStatement.catchBlock().statements(), out, nested);
            }
            case ThrowStatement throwStatement -> walkExpression(throwStatement.expr(), out);
            case BreakStatement ignored -> { }
            case ContinueStatement ignored -> { }
            case ImportDeclaration ignored -> { }
            default -> { }
        }
    }

    private static void checkDeclaration(FunctionDeclaration declaration,
                                         List<CompilerDiagnostic> out) {
        if (declaration.isExternal() || !isNullReturn(declaration.returnType())) {
            return;
        }
        if (!ReturnAnalysis.definitelyReturns(declaration.body())) {
            out.add(CompilerDiagnostic.error(DiagnosticCode.E5002,
                "Function '" + declaration.name() + "' must return on all paths",
                declaration.span()));
        }
    }

    // =======================================================================
    // Expressions
    // =======================================================================

    private static void walkExpression(ExpressionNode expression,
                                       List<CompilerDiagnostic> out) {
        switch (expression) {
            case FunctionExpr functionExpr -> {
                if (isNullReturn(functionExpr.returnType())
                        && !ReturnAnalysis.definitelyReturns(functionExpr.body())) {
                    out.add(CompilerDiagnostic.error(DiagnosticCode.E5002,
                        "Function expression must return on all paths",
                        functionExpr.span()));
                }
                walkStatements(functionExpr.body().statements(), out, true);
            }
            case BinaryExpr binary -> {
                walkExpression(binary.left(), out);
                walkExpression(binary.right(), out);
            }
            case UnaryExpr unary -> walkExpression(unary.expr(), out);
            case CallExpr call -> {
                walkExpression(call.callee(), out);
                for (ExpressionNode argument : call.args()) {
                    walkExpression(argument, out);
                }
            }
            case MemberAccessExpr member -> walkExpression(member.object(), out);
            case IndexExpr index -> {
                walkExpression(index.array(), out);
                walkExpression(index.index(), out);
            }
            case ArrayLiteralExpr array -> {
                for (ExpressionNode element : array.elements()) {
                    walkExpression(element, out);
                }
            }
            case ObjectLiteralExpr object -> {
                for (Property property : object.properties()) {
                    walkExpression(property.value(), out);
                }
            }
            case HasExpr has -> walkExpression(has.object(), out);
            case AssignmentExpr assignment -> {
                walkExpression(assignment.target(), out);
                walkExpression(assignment.value(), out);
            }
            case TemplateLiteralExpr template -> {
                for (ExpressionNode part : template.parts()) {
                    walkExpression(part, out);
                }
            }
            case AwaitExpression await -> walkExpression(await.callee(), out);
            default -> { }
        }
    }

    // =======================================================================
    // Nested recursive-group partition (the ISSUE-0731 exemption)
    // =======================================================================

    /**
     * The declarations of one statement list that are members of a
     * size&gt;=2 reference cycle: the B4 partition the lowering applies to
     * nested recursive groups (a declaration whose body's free references
     * name a sibling that reaches it back). A self reference alone is not
     * a group member, exactly like the lowering's partition.
     */
    private static Set<FunctionDeclaration> mutualGroupMembers(
            List<StatementNode> statements) {
        List<FunctionDeclaration> declarations = new ArrayList<>();
        for (StatementNode statement : statements) {
            if (statement instanceof FunctionDeclaration function) {
                declarations.add(function);
            }
        }
        if (declarations.size() < 2) {
            return Set.of();
        }
        Map<String, FunctionDeclaration> byName = new LinkedHashMap<>();
        for (FunctionDeclaration function : declarations) {
            byName.put(function.name(), function);
        }
        Map<FunctionDeclaration, Set<FunctionDeclaration>> edges =
            new IdentityHashMap<>();
        for (FunctionDeclaration function : declarations) {
            Set<FunctionDeclaration> targets = new LinkedHashSet<>();
            for (String reference : freeNameReferences(function)) {
                FunctionDeclaration target = byName.get(reference);
                if (target != null && target != function) {
                    targets.add(target);
                }
            }
            edges.put(function, targets);
        }
        Set<FunctionDeclaration> members = Collections.newSetFromMap(
            new IdentityHashMap<>());
        for (FunctionDeclaration function : declarations) {
            for (FunctionDeclaration target : edges.get(function)) {
                if (reaches(edges, target, function)) {
                    members.add(function);
                    members.add(target);
                }
            }
        }
        return members;
    }

    /** Whether {@code start} reaches {@code target} over the reference edges. */
    private static boolean reaches(
            Map<FunctionDeclaration, Set<FunctionDeclaration>> edges,
            FunctionDeclaration start, FunctionDeclaration target) {
        ArrayDeque<FunctionDeclaration> work = new ArrayDeque<>();
        Set<FunctionDeclaration> seen = Collections.newSetFromMap(
            new IdentityHashMap<>());
        work.add(start);
        seen.add(start);
        while (!work.isEmpty()) {
            FunctionDeclaration current = work.poll();
            for (FunctionDeclaration next : edges.get(current)) {
                if (next == target) {
                    return true;
                }
                if (seen.add(next)) {
                    work.add(next);
                }
            }
        }
        return false;
    }

    // -- Free-name references of one declaration's body: the same walk
    //    the lowering's partition uses, so the gate's exemption never
    //    over- or under-approximates the nested-group identity. --

    private static Set<String> freeNameReferences(FunctionDeclaration declaration) {
        Set<String> references = new LinkedHashSet<>();
        Set<String> bound = new HashSet<>();
        for (Parameter parameter : declaration.params()) {
            bound.add(parameter.name());
        }
        walkReferenceBlock(declaration.body(), bound, references);
        return references;
    }

    private static void walkReferenceBlock(Block block, Set<String> bound,
                                           Set<String> references) {
        Set<String> local = new HashSet<>(bound);
        for (StatementNode statement : block.statements()) {
            String declared = declaredNameOf(statement);
            if (declared != null) {
                local.add(declared);
            }
        }
        for (StatementNode statement : block.statements()) {
            walkReferenceStatement(statement, local, references);
        }
    }

    private static String declaredNameOf(StatementNode statement) {
        return switch (statement) {
            case VariableDeclaration decl -> decl.name();
            case FunctionDeclaration function -> function.name();
            default -> null;
        };
    }

    private static void walkReferenceStatement(StatementNode statement,
                                               Set<String> bound,
                                               Set<String> references) {
        switch (statement) {
            case VariableDeclaration decl ->
                walkReferenceExpr(decl.initializer(), bound, references);
            case FunctionDeclaration function -> {
                Set<String> inner = new HashSet<>(bound);
                for (Parameter parameter : function.params()) {
                    inner.add(parameter.name());
                }
                walkReferenceBlock(function.body(), inner, references);
            }
            case Block block -> walkReferenceBlock(block, bound, references);
            case IfStatement ifStatement -> {
                walkReferenceExpr(ifStatement.condition(), bound, references);
                walkReferenceBlock(ifStatement.thenBlock(), bound, references);
                if (ifStatement.elseBranch().isPresent()) {
                    Either<IfStatement, Block> branch = ifStatement.elseBranch().get();
                    switch (branch) {
                        case Either.Left<IfStatement, Block> left ->
                            walkReferenceStatement(left.value(), bound, references);
                        case Either.Right<IfStatement, Block> right ->
                            walkReferenceBlock(right.value(), bound, references);
                    }
                }
            }
            case WhileStatement whileStatement -> {
                walkReferenceExpr(whileStatement.condition(), bound, references);
                walkReferenceBlock(whileStatement.body(), bound, references);
            }

            case ForOfStatement forOf -> {
                walkReferenceExpr(forOf.iterable(), bound, references);
                Set<String> loopBound = new HashSet<>(bound);
                loopBound.add(forOf.varName());
                walkReferenceBlock(forOf.body(), loopBound, references);
            }
            case TryStatement tryStatement -> {
                walkReferenceBlock(tryStatement.tryBlock(), bound, references);
                Set<String> catchBound = new HashSet<>(bound);
                catchBound.add(tryStatement.catchVar());
                walkReferenceBlock(tryStatement.catchBlock(), catchBound, references);
            }
            case ExpressionStatement expressionStatement ->
                walkReferenceExpr(expressionStatement.expr(), bound, references);
            case ReturnStatement returnStatement -> {
                if (returnStatement.expr().isPresent()) {
                    walkReferenceExpr(returnStatement.expr().get(), bound, references);
                }
            }
            case ThrowStatement throwStatement ->
                walkReferenceExpr(throwStatement.expr(), bound, references);
            case DeleteStatement deleteStatement ->
                walkReferenceExpr(deleteStatement.target(), bound, references);
            case ClassDeclaration _, BreakStatement _, ContinueStatement _,
                 ImportDeclaration _, ExportDeclaration _ -> { }
            default -> { }
        }
    }

    private static void walkReferenceExpr(ExpressionNode expr, Set<String> bound,
                                          Set<String> references) {
        switch (expr) {
            case LiteralExpr ignored -> { }
            case IdentifierExpr identifier -> {
                if (!bound.contains(identifier.name())) {
                    references.add(identifier.name());
                }
            }
            case BinaryExpr binary -> {
                walkReferenceExpr(binary.left(), bound, references);
                walkReferenceExpr(binary.right(), bound, references);
            }
            case UnaryExpr unary -> walkReferenceExpr(unary.expr(), bound, references);
            case CallExpr call -> {
                walkReferenceExpr(call.callee(), bound, references);
                for (ExpressionNode arg : call.args()) {
                    walkReferenceExpr(arg, bound, references);
                }
            }
            case MemberAccessExpr access ->
                walkReferenceExpr(access.object(), bound, references);
            case IndexExpr index -> {
                walkReferenceExpr(index.array(), bound, references);
                walkReferenceExpr(index.index(), bound, references);
            }
            case ArrayLiteralExpr array -> {
                for (ExpressionNode element : array.elements()) {
                    walkReferenceExpr(element, bound, references);
                }
            }
            case ObjectLiteralExpr object -> {
                for (Property property : object.properties()) {
                    walkReferenceExpr(property.value(), bound, references);
                }
            }
            case FunctionExpr functionExpr -> {
                Set<String> inner = new HashSet<>(bound);
                for (Parameter parameter : functionExpr.params()) {
                    inner.add(parameter.name());
                }
                walkReferenceBlock(functionExpr.body(), inner, references);
            }
            case HasExpr has -> walkReferenceExpr(has.object(), bound, references);
            case AssignmentExpr assignment -> {
                walkReferenceExpr(assignment.target(), bound, references);
                walkReferenceExpr(assignment.value(), bound, references);
            }
            case TemplateLiteralExpr template -> {
                for (ExpressionNode part : template.parts()) {
                    walkReferenceExpr(part, bound, references);
                }
            }
            case AwaitExpression awaitExpression ->
                walkReferenceExpr(awaitExpression.callee(), bound, references);
        }
    }

    /** Whether one return-type node is the literal {@code null} type. */
    private static boolean isNullReturn(TypeNode node) {
        return node instanceof NamedType named && "null".equals(named.name());
    }
}
