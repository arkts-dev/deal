package deal.compiler;

import deal.ast.*;
import java.util.*;

/** Module references from parsed syntax, excluding member names, literals and local bindings. */
final class DeclarationReferences {
    record Reference(String kind, String name) {}
    private final Set<Reference> references = new LinkedHashSet<>();

    static Set<Reference> collect(StatementNode node, Set<String> bindings) {
        var visitor = new DeclarationReferences();
        visitor.statement(node, new HashSet<>(bindings));
        return Set.copyOf(visitor.references);
    }

    private void type(TypeNode node) {
        switch (node) {
            case NamedType n -> references.add(new Reference("class", n.name()));
            case ArrayType n -> type(n.elementType());
            case NullableType n -> type(n.innerType());
            case FunctionType n -> { n.params().forEach(p -> type(p.type())); type(n.returnType()); }
            case QualifiedType ignored -> { }
        }
    }

    private void function(List<Parameter> parameters, TypeNode returns, Block body, Set<String> outer) {
        var local = new HashSet<>(outer);
        parameters.forEach(p -> { type(p.type()); local.add(p.name()); });
        type(returns);
        if (body != null) statement(body, local);
    }

    private void statement(StatementNode node, Set<String> scope) {
        switch (node) {
            case ExportDeclaration n -> statement(n.declaration(), scope);
            case FunctionDeclaration n -> function(n.params(), n.returnType(), n.body(), scope);
            case ClassDeclaration n -> n.fields().forEach(f -> { type(f.type()); f.defaultExpr().ifPresent(e -> expression(e, scope)); });
            case Block n -> {
                var local = new HashSet<>(scope);
                n.statements().forEach(s -> statement(s, local));
            }
            case VariableDeclaration n -> {
                n.typeAnnotation().ifPresent(this::type);
                expression(n.initializer(), scope);
                scope.add(n.name());
            }
            case ExpressionStatement n -> expression(n.expr(), scope);
            case ReturnStatement n -> n.expr().ifPresent(e -> expression(e, scope));
            case IfStatement n -> {
                expression(n.condition(), scope); statement(n.thenBlock(), scope);
                n.elseBranch().ifPresent(b -> {
                    if (b instanceof Either.Left<IfStatement, Block> left) statement(left.value(), scope);
                    if (b instanceof Either.Right<IfStatement, Block> right) statement(right.value(), scope);
                });
            }
            case WhileStatement n -> { expression(n.condition(), scope); statement(n.body(), scope); }
            case ForStatement n -> {
                var local = new HashSet<>(scope);
                n.init().ifPresent(i -> {
                    if (i instanceof ForInit.VarDecl v) statement(v.decl(), local);
                    if (i instanceof ForInit.AssignExpr a) expression(a.expr(), local);
                });
                n.condition().ifPresent(e -> expression(e, local));
                n.update().ifPresent(e -> expression(e, local));
                statement(n.body(), local);
            }
            case ForOfStatement n -> {
                type(n.varType()); expression(n.iterable(), scope);
                var local = new HashSet<>(scope); local.add(n.varName()); statement(n.body(), local);
            }
            case TryStatement n -> {
                statement(n.tryBlock(), scope);
                var local = new HashSet<>(scope); local.add(n.catchVar()); statement(n.catchBlock(), local);
            }
            case ThrowStatement n -> expression(n.expr(), scope);
            case DeleteStatement n -> expression(n.target(), scope);
            case ImportDeclaration ignored -> { }
            case BreakStatement ignored -> { }
            case ContinueStatement ignored -> { }
        }
    }

    private void expression(ExpressionNode node, Set<String> scope) {
        switch (node) {
            case IdentifierExpr n -> { if (!scope.contains(n.name())) references.add(new Reference("function", n.name())); }
            case LiteralExpr ignored -> { }
            case MemberAccessExpr n -> expression(n.object(), scope);
            case CallExpr n -> { expression(n.callee(), scope); n.args().forEach(e -> expression(e, scope)); }
            case BinaryExpr n -> { expression(n.left(), scope); expression(n.right(), scope); }
            case UnaryExpr n -> expression(n.expr(), scope);
            case IndexExpr n -> { expression(n.array(), scope); expression(n.index(), scope); }
            case ArrayLiteralExpr n -> n.elements().forEach(e -> expression(e, scope));
            case ObjectLiteralExpr n -> n.properties().forEach(p -> expression(p.value(), scope));
            case FunctionExpr n -> function(n.params(), n.returnType(), n.body(), scope);
            case HasExpr n -> expression(n.object(), scope);
            case AssignmentExpr n -> { expression(n.target(), scope); expression(n.value(), scope); }
            case TemplateLiteralExpr n -> n.parts().forEach(e -> expression(e, scope));
            case AwaitExpression n -> expression(n.callee(), scope);
        }
    }
}
