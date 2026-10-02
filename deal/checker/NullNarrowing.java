package deal.checker;

import deal.ast.*;
import deal.types.Type;
import deal.types.Types;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Forward-flow null narrowing analysis.
 *
 * <p>Maintains a per-variable map of narrowed types.
 * On {@code if (x !== null)}, the true branch narrows {@code x} to its
 * unwrapped type; the false branch narrows it to {@code NullType}.
 * Assignment to a variable clears its narrowed entry.
 * After try/catch, all variables assigned inside the try body revert.</p>
 *
 * <p>The {@code declaredTypeResolver} function is used to look up the
 * declared type of a variable so that nullable unwrapping can be performed.</p>
 */
public final class NullNarrowing {

    /** Maps variable name → narrowed type. */
    private final Map<String, Type> narrowed = new HashMap<>();

    /**
     * Member-path guard set (ISSUE-0717): the dotted receiver paths a
     * preceding {@code path !== null} test guards non-null in this flow
     * position (the guarded member read of a nullable-typed member path,
     * e.g. {@code if (value.child !== null) { value.child.value }}).
     */
    private final Set<String> guardedPaths = new HashSet<>();

    /** Creates an empty narrowing state. */
    public NullNarrowing() {}

    /** Copy constructor for branching. */
    private NullNarrowing(NullNarrowing other) {
        this.narrowed.putAll(other.narrowed);
        this.guardedPaths.addAll(other.guardedPaths);
    }

    /**
     * Creates a copy of the current state for use in a branch (e.g. if/else).
     */
    public NullNarrowing branch() {
        return new NullNarrowing(this);
    }

    /**
     * Process an if-condition and record narrowing for the appropriate branch.
     *
     */
    public void onIfCondition(ExpressionNode cond, boolean enterTrueBranch,
                              Function<String, Type> declaredTypeOf) {
        // Handle !(x === null)  →  x !== null
        if (cond instanceof UnaryExpr un && un.op() == UnaryOp.NOT) {
            if (un.expr() instanceof BinaryExpr bin) {
                if (bin.op() == BinaryOp.EQ && isNullLiteral(bin.right())) {
                    // !(x === null)  →  x !== null
                    String var = extractVarName(bin.left());
                    if (var != null) {
                        if (enterTrueBranch) {
                            narrowToNonNull(var, declaredTypeOf);
                        } else {
                            narrowed.put(var, Type.Null.INSTANCE);
                        }
                    }
                    guardMemberPath(bin.left(), enterTrueBranch);
                    return;
                }
                if (bin.op() == BinaryOp.NEQ && isNullLiteral(bin.right())) {
                    // !(x !== null)  →  x === null
                    String var = extractVarName(bin.left());
                    if (var != null) {
                        if (enterTrueBranch) {
                            narrowed.put(var, Type.Null.INSTANCE);
                        } else {
                            narrowToNonNull(var, declaredTypeOf);
                        }
                    }
                    guardMemberPath(bin.left(), !enterTrueBranch);
                    return;
                }
            }
            return;
        }

        if (!(cond instanceof BinaryExpr bin)) return;

        if (bin.op() == BinaryOp.NEQ && isNullLiteral(bin.right())) {
            // x !== null
            String var = extractVarName(bin.left());
            if (var != null) {
                if (enterTrueBranch) {
                    narrowToNonNull(var, declaredTypeOf);
                } else {
                    narrowed.put(var, Type.Null.INSTANCE);
                }
            }
            guardMemberPath(bin.left(), enterTrueBranch);
        } else if (bin.op() == BinaryOp.EQ && isNullLiteral(bin.right())) {
            // x === null
            String var = extractVarName(bin.left());
            if (var != null) {
                if (enterTrueBranch) {
                    narrowed.put(var, Type.Null.INSTANCE);
                } else {
                    narrowToNonNull(var, declaredTypeOf);
                }
            }
            guardMemberPath(bin.left(), !enterTrueBranch);
        }
    }

    /**
     * Records the member path of a {@code path !== null} test as guarded
     * non-null in the branch that observes the non-null arm (the variable
     * arm is handled by the identifier narrowing above; the member-path
     * arm feeds {@link #isPathGuarded}).
     */
    private void guardMemberPath(ExpressionNode operand, boolean nonNullBranch) {
        if (!nonNullBranch) {
            return;
        }
        String path = pathOf(operand);
        if (path != null) {
            guardedPaths.add(path);
        }
    }

    /**
     * Whether a member path (a dotted identifier-rooted receiver, e.g.
     * {@code value.child}) is guarded non-null at the current flow
     * position.
     */
    public boolean isPathGuarded(String path) {
        return guardedPaths.contains(path);
    }

    /**
     * Adopts the null guards of a branch state without its type
     * narrowing: the guard-only merge used when a then-branch cannot
     * complete normally (the false arm's guards survive the if; the
     * declared types stay visible to the comparison rules).
     */
    public void adoptGuards(NullNarrowing branch) {
        guardedPaths.addAll(branch.guardedPaths);
    }

    /**
     * The dotted path of a member-access chain rooted at an identifier
     * ({@code a.b.c}), or {@code null} when the expression is not a plain
     * identifier-rooted member chain.
     */
    public static String pathOf(ExpressionNode expr) {
        if (expr instanceof IdentifierExpr id) {
            return id.name();
        }
        if (expr instanceof MemberAccessExpr mae) {
            String base = pathOf(mae.object());
            return base == null ? null : base + "." + mae.field();
        }
        return null;
    }

    private void narrowToNonNull(String varName, Function<String, Type> declaredTypeOf) {
        Type declared = declaredTypeOf.apply(varName);
        if (declared instanceof Type.Nullable n) {
            narrowed.put(varName, n.inner());
        }
    }

    /**
     * Returns the narrowed type for a variable, or {@code null} if no narrowing
     * is active (meaning the declared type should be used).
     */
    public Type getNarrowedType(String varName) {
        return narrowed.get(varName);
    }

    /**
     * Records that a variable has been assigned, clearing its narrowed entry.
     */
    public void onAssignment(String varName) {
        narrowed.remove(varName);
    }

    /**
     * Restores a narrowed entry removed by a shadowing block-local
     * declaration when the declaring block exits.
     */
    public void restore(String varName, Type type) {
        narrowed.put(varName, type);
    }

    /**
     * Drops one variable's narrowed entry (the loop back-edge rule: an
     * assignment inside a loop body invalidates the entry after the loop).
     */
    public void invalidate(String varName) {
        narrowed.remove(varName);
    }

    /**
     * Reverts all narrowed entries for variables in the given set (used
     * after try/catch).
     */
    public void revertAll(Set<String> varNames) {
        for (String name : varNames) {
            narrowed.remove(name);
        }
    }

    /**
     * Clears all narrowed entries. Used after await expressions
     * because async suspension invalidates all flow-sensitive refinements.
     */
    public void invalidateAll() {
        narrowed.clear();
        guardedPaths.clear();
    }

    /**
     * Merges narrowings from two branches (intersection: only keep if both
     * branches agree).
     */
    public void merge(NullNarrowing other) {
        Set<String> toRemove = new HashSet<>();
        for (Map.Entry<String, Type> entry : narrowed.entrySet()) {
            Type otherType = other.narrowed.get(entry.getKey());
            if (otherType == null || !Types.equals(entry.getValue(), otherType)) {
                toRemove.add(entry.getKey());
            }
        }
        for (String name : toRemove) {
            narrowed.remove(name);
        }
        guardedPaths.retainAll(other.guardedPaths);
    }

    public Set<String> narrowedVariableNames() {
        return new HashSet<>(narrowed.keySet());
    }

    // =======================================================================
    // Internal helpers
    // =======================================================================

    private static boolean isNullLiteral(ExpressionNode expr) {
        return expr instanceof LiteralExpr lit
            && lit.value() instanceof LiteralValue.NullLiteral;
    }

    private static String extractVarName(ExpressionNode expr) {
        if (expr instanceof IdentifierExpr id) {
            return id.name();
        }
        return null; // no property-path narrowing
    }
}
