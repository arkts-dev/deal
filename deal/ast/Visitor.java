package deal.ast;

/**
 * Visitor interface for the AST.
 * One visit method per concrete node type.
 * All methods have default implementations that return {@code null},
 * so partial visitors (e.g., visitors that only handle statements)
 * can compile without implementing every method.
 *
 * @param <T> the return type of visit methods
 */
public interface Visitor<T> {

    // -- Program --
    default T visit(ProgramNode node) { return null; }

    // -- Statements --
    default T visit(ClassDeclaration node) { return null; }
    default T visit(FunctionDeclaration node) { return null; }
    default T visit(VariableDeclaration node) { return null; }
    default T visit(ReturnStatement node) { return null; }
    default T visit(IfStatement node) { return null; }
    default T visit(WhileStatement node) { return null; }
    default T visit(ForStatement node) { return null; }
    default T visit(ForOfStatement node) { return null; }
    default T visit(BreakStatement node) { return null; }
    default T visit(ContinueStatement node) { return null; }
    default T visit(ExpressionStatement node) { return null; }
    default T visit(ImportDeclaration node) { return null; }
    default T visit(ExportDeclaration node) { return null; }
    default T visit(DeleteStatement node) { return null; }
    default T visit(TryStatement node) { return null; }
    default T visit(ThrowStatement node) { return null; }
    default T visit(Block node) { return null; }

    // -- Expressions --
    default T visit(LiteralExpr node) { return null; }
    default T visit(IdentifierExpr node) { return null; }
    default T visit(BinaryExpr node) { return null; }
    default T visit(UnaryExpr node) { return null; }
    default T visit(CallExpr node) { return null; }
    default T visit(MemberAccessExpr node) { return null; }
    default T visit(IndexExpr node) { return null; }
    default T visit(ArrayLiteralExpr node) { return null; }
    default T visit(ObjectLiteralExpr node) { return null; }
    default T visit(FunctionExpr node) { return null; }
    default T visit(HasExpr node) { return null; }
    default T visit(AssignmentExpr node) { return null; }
    default T visit(TemplateLiteralExpr node) { return null; }
    default T visit(AwaitExpression node) { return null; }

    // -- Type nodes (for visitors that walk type annotations) --
    default T visit(NamedType node) { return null; }
    default T visit(ArrayType node) { return null; }
    default T visit(NullableType node) { return null; }
    default T visit(FunctionType node) { return null; }
}
