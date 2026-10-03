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
import deal.ast.ForInit;
import deal.ast.ForOfStatement;
import deal.ast.ForStatement;
import deal.ast.FunctionDeclaration;
import deal.ast.FunctionExpr;
import deal.ast.HasExpr;
import deal.ast.IfStatement;
import deal.ast.ImportDeclaration;
import deal.ast.IndexExpr;
import deal.ast.MemberAccessExpr;
import deal.ast.NamedType;
import deal.ast.ObjectLiteralExpr;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The null-return fall-off gate of the public compile surface (ISSUE-0717
 * defect snippets 25/26): the spec's return-type requirements
 * ({@code docs/spec-v1.2.md} §Functions — "The function body must not
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
        walkStatements(program.statements(), out);
        return out;
    }

    // =======================================================================
    // Statements
    // =======================================================================

    private static void walkStatements(List<StatementNode> statements,
                                       List<CompilerDiagnostic> out) {
        for (StatementNode statement : statements) {
            walkStatement(statement, out);
        }
    }

    private static void walkStatement(StatementNode statement,
                                      List<CompilerDiagnostic> out) {
        switch (statement) {
            case Block block -> walkStatements(block.statements(), out);
            case FunctionDeclaration declaration -> {
                checkDeclaration(declaration, out);
                walkStatements(declaration.body().statements(), out);
            }
            case ExportDeclaration export -> walkStatement(export.declaration(), out);
            case ClassDeclaration declaration -> {
                for (ClassField field : declaration.fields()) {
                    if (field.defaultExpr().isPresent()) {
                        walkExpression(field.defaultExpr().get(), out);
                    }
                }
            }
            case VariableDeclaration declaration ->
                walkExpression(declaration.initializer(), out);
            case ReturnStatement ret -> {
                if (ret.expr().isPresent()) {
                    walkExpression(ret.expr().get(), out);
                }
            }
            case IfStatement ifStatement -> {
                walkExpression(ifStatement.condition(), out);
                walkStatements(ifStatement.thenBlock().statements(), out);
                if (ifStatement.elseBranch().isPresent()) {
                    switch (ifStatement.elseBranch().get()) {
                        case Either.Left<IfStatement, Block> left ->
                            walkStatement(left.value(), out);
                        case Either.Right<IfStatement, Block> right ->
                            walkStatements(right.value().statements(), out);
                    }
                }
            }
            case WhileStatement whileStatement -> {
                walkExpression(whileStatement.condition(), out);
                walkStatements(whileStatement.body().statements(), out);
            }
            case ForStatement forStatement -> {
                if (forStatement.init().isPresent()) {
                    switch (forStatement.init().get()) {
                        case ForInit.VarDecl varDecl ->
                            walkExpression(varDecl.decl().initializer(), out);
                        case ForInit.AssignExpr assign ->
                            walkExpression(assign.expr(), out);
                    }
                }
                if (forStatement.condition().isPresent()) {
                    walkExpression(forStatement.condition().get(), out);
                }
                if (forStatement.update().isPresent()) {
                    walkExpression(forStatement.update().get(), out);
                }
                walkStatements(forStatement.body().statements(), out);
            }
            case ForOfStatement forOf -> {
                walkExpression(forOf.iterable(), out);
                walkStatements(forOf.body().statements(), out);
            }
            case ExpressionStatement expression -> walkExpression(expression.expr(), out);
            case DeleteStatement delete -> walkExpression(delete.target(), out);
            case TryStatement tryStatement -> {
                walkStatements(tryStatement.tryBlock().statements(), out);
                walkStatements(tryStatement.catchBlock().statements(), out);
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
                walkStatements(functionExpr.body().statements(), out);
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

    /** Whether one return-type node is the literal {@code null} type. */
    private static boolean isNullReturn(TypeNode node) {
        return node instanceof NamedType named && "null".equals(named.name());
    }
}
