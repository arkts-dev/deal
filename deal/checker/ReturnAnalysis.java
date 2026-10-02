package deal.checker;

import deal.ast.*;

/**
 * Definite return analysis for function bodies (spec §Definite return
 * analysis, "A function with return type {@code R} must return on all
 * reachable paths").
 *
 * <p>The analysis walks a statement list in source order and stops at the
 * first statement that cannot complete normally — the statements after a
 * terminator are unreachable and are ignored, so "the block's final
 * reachable statement" is the terminator, never an unreachable tail.
 * Terms:</p>
 * <ul>
 *   <li>{@code return}/{@code throw} → cannot complete normally and
 *       definitely returns;</li>
 *   <li>{@code break}/{@code continue} → cannot complete normally but does
 *       not return (a block whose first terminator is a transfer is not a
 *       returning block);</li>
 *   <li>{@code if (cond) { A } else { B }} → cannot complete normally and
 *       definitely returns when both branches do;</li>
 *   <li>{@code try}/{@code catch} → both arms must cannot complete
 *       normally and both must return (spec §Error handling: "If the try
 *       block has a definite return, the catch block must also have a
 *       definite return or throw");</li>
 *   <li>loops → not assumed to return unless the loop is literal-true or
 *       test-less and its body cannot complete normally through a
 *       return/throw and contains no reachable {@code break} (spec:
 *       "Loops are not assumed to return unless they contain an
 *       unconditional return before any possible break").</li>
 * </ul>
 *
 * <p>A function whose body cannot complete normally through a return/throw
 * (an {@code if/else} chain, a literal-true loop, a {@code try/catch} pair,
 * or a terminating block) satisfies the requirement; a body that falls off
 * the end, or whose only non-completing path exits by {@code break} /
 * {@code continue}, does not.</p>
 */
public final class ReturnAnalysis {

    private ReturnAnalysis() {}

    /**
     * Checks whether a block definitely returns on all reachable paths.
     */
    public static boolean definitelyReturns(Block block) {
        return blockReturns(block.statements());
    }

    /**
     * Checks whether a statement definitely returns.
     */
    public static boolean definitelyReturns(StatementNode stmt) {
        return returnsOnAllPaths(stmt);
    }

    /**
     * Checks whether a statement list returns on all paths: the first
     * statement that cannot complete normally decides, and the statements
     * after it are unreachable.
     */
    private static boolean blockReturns(java.util.List<StatementNode> stmts) {
        for (StatementNode stmt : stmts) {
            if (!cannotCompleteNormally(stmt)) {
                continue;
            }
            return returnsOnAllPaths(stmt);
        }
        // Every statement can complete normally: the block can fall
        // through.
        return false;
    }

    /**
     * Checks whether one non-completing statement exits by a
     * {@code return}/{@code throw} on every path (the composite rule).
     */
    private static boolean returnsOnAllPaths(StatementNode stmt) {
        if (stmt instanceof ReturnStatement) return true;
        if (stmt instanceof ThrowStatement) return true;
        if (stmt instanceof BreakStatement) return false;
        if (stmt instanceof ContinueStatement) return false;
        if (stmt instanceof Block block) {
            return blockReturns(block.statements());
        }
        if (stmt instanceof IfStatement i) {
            if (i.elseBranch().isEmpty()) return false;
            if (!returnsOnAllPaths(i.thenBlock())) return false;
            return switch (i.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left ->
                    returnsOnAllPaths(left.value());
                case Either.Right<IfStatement, Block> right ->
                    returnsOnAllPaths(right.value());
            };
        }
        if (stmt instanceof TryStatement ts) {
            return returnsOnAllPaths(ts.tryBlock())
                && returnsOnAllPaths(ts.catchBlock());
        }
        if (stmt instanceof WhileStatement ws) {
            return isLiteralTrue(ws.condition())
                && returnsOnAllPaths(ws.body())
                && !containsBreak(ws.body());
        }
        if (stmt instanceof ForStatement fs) {
            boolean infinite = fs.condition().isEmpty()
                || isLiteralTrue(fs.condition().get());
            return infinite
                && returnsOnAllPaths(fs.body())
                && !containsBreak(fs.body());
        }
        // All other statements cannot complete normally only through the
        // transfer statements handled above.
        return false;
    }

    /**
     * Whether a statement cannot complete normally (control never reaches
     * the statement that follows it): a return, throw, break, or continue,
     * or a composite statement every one of whose paths is non-completing.
     * The null-narrowing guard merge uses this; the definite-return rule
     * additionally requires the non-completing paths to exit by
     * return/throw ({@link #returnsOnAllPaths}).
     */
    public static boolean cannotCompleteNormally(StatementNode stmt) {
        if (stmt instanceof ReturnStatement) return true;
        if (stmt instanceof ThrowStatement) return true;
        if (stmt instanceof BreakStatement) return true;
        if (stmt instanceof ContinueStatement) return true;
        if (stmt instanceof Block block) {
            return blockCannotComplete(block.statements());
        }
        if (stmt instanceof IfStatement i) {
            if (i.elseBranch().isEmpty()) return false;
            if (!cannotCompleteNormally(i.thenBlock())) return false;
            return switch (i.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left ->
                    cannotCompleteNormally(left.value());
                case Either.Right<IfStatement, Block> right ->
                    cannotCompleteNormally(right.value());
            };
        }
        if (stmt instanceof TryStatement ts) {
            return cannotCompleteNormally(ts.tryBlock())
                && cannotCompleteNormally(ts.catchBlock());
        }
        if (stmt instanceof WhileStatement ws) {
            return isLiteralTrue(ws.condition())
                && cannotCompleteNormally(ws.body())
                && !containsBreak(ws.body());
        }
        if (stmt instanceof ForStatement fs) {
            boolean infinite = fs.condition().isEmpty()
                || isLiteralTrue(fs.condition().get());
            return infinite
                && cannotCompleteNormally(fs.body())
                && !containsBreak(fs.body());
        }
        return false;
    }

    /** Checks whether a statement list cannot complete normally (its first
     * non-completing statement, if any, ends the walk). */
    private static boolean blockCannotComplete(java.util.List<StatementNode> stmts) {
        for (StatementNode stmt : stmts) {
            if (cannotCompleteNormally(stmt)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one expression is the literal boolean {@code true} (the
     * {@code while (true)} / {@code for (; true; …)} loop form).
     */
    private static boolean isLiteralTrue(ExpressionNode expression) {
        return expression instanceof LiteralExpr literal
            && literal.value() instanceof LiteralValue.BooleanLiteral bool
            && bool.value();
    }

    /**
     * Whether a loop body carries a reachable {@code break} of this loop
     * (nested loops and nested function bodies bind their own transfers,
     * so their {@code break} statements do not break this loop).
     */
    private static boolean containsBreak(StatementNode stmt) {
        if (stmt instanceof BreakStatement) return true;
        if (stmt instanceof Block block) {
            for (StatementNode child : block.statements()) {
                if (containsBreak(child)) return true;
            }
            return false;
        }
        if (stmt instanceof IfStatement i) {
            if (containsBreak(i.thenBlock())) return true;
            return i.elseBranch().isPresent()
                && switch (i.elseBranch().get()) {
                    case Either.Left<IfStatement, Block> left ->
                        containsBreak(left.value());
                    case Either.Right<IfStatement, Block> right ->
                        containsBreak(right.value());
                };
        }
        if (stmt instanceof TryStatement ts) {
            return containsBreak(ts.tryBlock()) || containsBreak(ts.catchBlock());
        }
        // Nested loops and nested function bodies own their transfers.
        return false;
    }
}
