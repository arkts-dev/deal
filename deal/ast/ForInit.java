package deal.ast;

/**
 * Sum type for the for-loop initializer (spec.md line 162):
 * ForInit ::= VariableDeclaration | AssignmentExpression
 */
public sealed interface ForInit permits ForInit.VarDecl, ForInit.AssignExpr {
    Span span();

    /** let-declared loop variable */
    record VarDecl(VariableDeclaration decl) implements ForInit {
        public Span span() { return decl.span(); }
    }

    /** expression initializer */
    record AssignExpr(AssignmentExpr expr) implements ForInit {
        public Span span() { return expr.span(); }
    }
}
