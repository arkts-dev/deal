package deal.ast;

import java.util.List;

/**
 * Root sealed interface for all statement AST nodes.
 * Every statement carries a non-null {@link Span}.
 */
public sealed interface StatementNode
    permits ClassDeclaration,
            FunctionDeclaration,
            VariableDeclaration,
            ReturnStatement,
            IfStatement,
            WhileStatement,
            ForStatement,
            ForOfStatement,
            BreakStatement,
            ContinueStatement,
            ExpressionStatement,
            ImportDeclaration,
            ExportDeclaration,
            DeleteStatement,
            TryStatement,
            ThrowStatement,
            Block {

    Span span();
}
