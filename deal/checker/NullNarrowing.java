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

    /** Creates an empty narrowing state. */
    public NullNarrowing() {}

    /** Copy constructor for branching. */
    private NullNarrowing(NullNarrowing other) {
        this.narrowed.putAll(other.narrowed);
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
     * @param cond               the if-condition expression
     * @param enterTrueBranch    true for the then-block, false for the else-block
     * @param declaredTypeOf     function to look up the declared type of a variable
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
        }
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
     * Reverts all narrowed entries for variables in the given set (used
     * after try/catch).
     */
    public void revertAll(Set<String> varNames) {
        for (String name : varNames) {
            narrowed.remove(name);
        }
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
    }

    /**
     * Returns all variable names that currently have narrowed entries.
     */
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
