package deal.checker;

import deal.ast.*;

/**
 * Definite return analysis for function bodies.
 *
 * <p>Recursively walks the function body tracking "definitely returns" status.
 * <ul>
 *   <li>{@code return} → definitely returns</li>
 *   <li>{@code throw} → definitely returns</li>
 *   <li>{@code if/else} → definitely returns if both branches definitely return</li>
 *   <li>Block → definitely returns if last statement definitely returns</li>
 *   <li>Loops → do NOT definitely return (conservative)</li>
 * </ul>
 */
public final class ReturnAnalysis {

    private ReturnAnalysis() {}

    /**
     * Checks whether a block definitely returns on all paths.
     */
    public static boolean definitelyReturns(Block block) {
        return definitelyReturnsStmts(block.statements());
    }

    /**
     * Checks whether a statement definitely returns.
     */
    public static boolean definitelyReturns(StatementNode stmt) {
        if (stmt instanceof ReturnStatement) return true;
        if (stmt instanceof ThrowStatement) return true;

        if (stmt instanceof IfStatement i) {
            if (i.elseBranch().isEmpty()) return false;
            boolean thenReturns = definitelyReturns(i.thenBlock());
            if (!thenReturns) return false;
            return switch (i.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left ->
                    definitelyReturns(left.value());
                case Either.Right<IfStatement, Block> right ->
                    definitelyReturns(right.value());
            };
        }
        if (stmt instanceof Block b) {
            return definitelyReturnsStmts(b.statements());
        }
        if (stmt instanceof WhileStatement) return false;
        if (stmt instanceof ForStatement) return false;
        if (stmt instanceof TryStatement ts) {
            return definitelyReturns(ts.tryBlock())
                && definitelyReturns(ts.catchBlock());
        }
        // All other statements (expr, var decl, break, continue, etc.) do not return
        return false;
    }

    private static boolean definitelyReturnsStmts(java.util.List<StatementNode> stmts) {
        if (stmts.isEmpty()) return false;
        return definitelyReturns(stmts.get(stmts.size() - 1));
    }
}
