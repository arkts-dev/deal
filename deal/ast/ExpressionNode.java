package deal.ast;

/**
 * Root sealed interface for all expression AST nodes.
 */
public sealed interface ExpressionNode
    permits LiteralExpr,
            IdentifierExpr,
            BinaryExpr,
            UnaryExpr,
            CallExpr,
            MemberAccessExpr,
            IndexExpr,
            ArrayLiteralExpr,
            ObjectLiteralExpr,
            FunctionExpr,
            HasExpr,
            AssignmentExpr,
            TemplateLiteralExpr,
            AwaitExpression {

    Span span();
}
