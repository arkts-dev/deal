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
 *   <li>{@code try}/{@code catch} → both arms must not complete
 *       normally and both must return for the statement itself to
 *       definitely return; otherwise later statements may return;</li>
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

    public static boolean definitelyReturns(Block block) { return definitelyReturns(block, expression -> false); }
    public static boolean definitelyReturns(StatementNode statement) { return new Analysis(expression -> false).definitelyReturns(statement); }
    public static boolean cannotCompleteNormally(StatementNode statement) { return cannotCompleteNormally(statement, expression -> false); }
    public static ReturnObligation unmetObligation(Block block) { return new Analysis(expression -> false).unmetObligation(block); }
    public static boolean definitelyReturns(Block block, java.util.function.Predicate<ExpressionNode> terminates) {
        return new Analysis(terminates).definitelyReturns(block);
    }
    public static boolean cannotCompleteNormally(StatementNode statement, java.util.function.Predicate<ExpressionNode> terminates) {
        return new Analysis(terminates).cannotCompleteNormally(statement);
    }

    /** Diagnostic-only unmet obligation; it is not a feasible execution witness. */
    public record ReturnObligation(Span span, String explanation) {}

    private static final class Analysis {
        private final java.util.function.Predicate<ExpressionNode> terminates;
        Analysis(java.util.function.Predicate<ExpressionNode> terminates) { this.terminates = terminates; }

    /**
     * Checks whether a block definitely returns on all reachable paths.
     */
    public boolean definitelyReturns(Block block) {
        return blockReturns(block.statements());
    }

    /**
     * Labels one representative construct rejected by the existing return rule.
     * Never evaluates conditions or claims that an arm/loop is executable. A
     * later definite return discharges an earlier conditional obligation.
     */
    public ReturnObligation unmetObligation(Block block) {
        if (definitelyReturns(block)) return null;
        StatementNode representative = block;
        for (StatementNode statement : block.statements()) {
            representative = statement;
            if (cannotCompleteNormally(statement)) break;
        }
        ReturnObligation obligation = unmetStatementObligation(representative);
        return obligation != null ? obligation : new ReturnObligation(block.span(),
            "Body's final reachable statement does not establish the required return or throw");
    }

    private ReturnObligation unmetStatementObligation(StatementNode statement) {
        if (statement instanceof Block block) {
            if (block.statements().isEmpty())
                return new ReturnObligation(block.span(), "Empty body has no required return or throw");
            return unmetObligation(block);
        }
        if (statement instanceof IfStatement conditional) {
            if (conditional.elseBranch().isEmpty())
                return new ReturnObligation(conditional.condition().span(),
                    "Conditional without an else does not establish return or throw on all paths");
            if (!returnsOnAllPaths(conditional.thenBlock())) return unmetObligation(conditional.thenBlock());
            return switch (conditional.elseBranch().get()) {
                case Either.Left<IfStatement, Block> left -> unmetStatementObligation(left.value());
                case Either.Right<IfStatement, Block> right -> unmetObligation(right.value());
            };
        }
        if (statement instanceof TryStatement attempt) {
            return unmetObligation(!returnsOnAllPaths(attempt.tryBlock())
                ? attempt.tryBlock() : attempt.catchBlock());
        }
        if (statement instanceof WhileStatement loop)
            return new ReturnObligation(loop.condition().span(),
                "Loop does not satisfy the unconditional-return-before-any-possible-break rule; no iteration is assumed");
        if (statement instanceof ForOfStatement loop)
            return new ReturnObligation(loop.iterable().span(),
                "Iteration does not establish a required return; no iteration is assumed");
        if (statement instanceof BreakStatement || statement instanceof ContinueStatement)
            return new ReturnObligation(statement.span(), "Loop transfer is not a return or throw");
        return null;
    }

    /**
     * Checks whether a statement definitely returns.
     */
    public boolean definitelyReturns(StatementNode stmt) {
        return returnsOnAllPaths(stmt);
    }

    /**
     * Checks whether a statement list returns on all paths: the first
     * statement that cannot complete normally decides, and the statements
     * after it are unreachable.
     */
    private boolean blockReturns(java.util.List<StatementNode> stmts) {
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
    private boolean returnsOnAllPaths(StatementNode stmt) {
        if (stmt instanceof ExpressionStatement expression && terminates.test(expression.expr())) return true;
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
    public boolean cannotCompleteNormally(StatementNode stmt) {
        if (stmt instanceof ExpressionStatement expression && terminates.test(expression.expr())) return true;
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

        return false;
    }

    /** Checks whether a statement list cannot complete normally (its first
     * non-completing statement, if any, ends the walk). */
    private boolean blockCannotComplete(java.util.List<StatementNode> stmts) {
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
     * so their {@code break} statements do not break this loop; a
     * {@code break} behind a terminator is unreachable and never counts —
     * the spec's "unconditional return before any possible break" rule).
     */
    private boolean containsBreak(StatementNode stmt) {
        if (stmt instanceof BreakStatement) return true;
        if (stmt instanceof Block block) {
            for (StatementNode child : block.statements()) {
                if (containsBreak(child)) return true;
                // The first statement that cannot complete normally ends
                // the block's reachable prefix: later breaks are dead.
                if (cannotCompleteNormally(child)) return false;
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
}
