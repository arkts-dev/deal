package deal.checker;

import deal.ast.*;
import deal.types.Type;
import deal.types.Types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
 * After try/catch, the checker reverts every binding assigned inside the
 * try body — by resolved binding, never by name, so a shadowing
 * declaration or catch binding never drops an enclosing refinement.</p>
 *
 * <p>The {@code declaredTypeResolver} function is used to look up the
 * declared type of a variable so that nullable unwrapping can be performed.</p>
 */
public final class NullNarrowing {

    /** Maps variable name → narrowed type. */
    private final Map<String, Type> narrowed = new HashMap<>();

    // -- Shadow frames: one frame per open block, holding the narrowed
    //    entries a block-local declaration removed from the enclosing
    //    binding. The catch scope opens the same frame for the catch
    //    identifier's enclosing entry (the catch binding is a fresh
    //    binding, so the entry is saved and restored). Frames live on the
    //    narrowing object, so the frame state is scoped to the enclosing
    //    function exactly like the narrowed entries (a function boundary
    //    swaps the object; an await's invalidateAll clears the frames
    //    together with the entries, so an invalidated snapshot is never
    //    restored). --
    private final Deque<List<Map.Entry<String, Type>>> shadowFrames =
        new ArrayDeque<>();

    /** Creates an empty narrowing state. */
    public NullNarrowing() {}

    /** Copy constructor for branching. */
    private NullNarrowing(NullNarrowing other) {
        this.narrowed.putAll(other.narrowed);
        // The branch owns its own shadow state: a declaration or an
        // invalidation inside one path must not corrupt the sibling, and
        // the merged state reconciles the copies (a snapshot survives
        // only when both paths still hold it).
        for (List<Map.Entry<String, Type>> frame : other.shadowFrames) {
            this.shadowFrames.add(new ArrayList<>(frame));
        }
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
        if (cond instanceof UnaryExpr unary && unary.op() == UnaryOp.NOT) {
            onIfCondition(unary.expr(), !enterTrueBranch, declaredTypeOf);
            return;
        }
        if (cond instanceof BinaryExpr comparison
                && (comparison.op() == BinaryOp.EQ || comparison.op() == BinaryOp.NEQ)) {
            boolean matching = (comparison.op() == BinaryOp.EQ) == enterTrueBranch;
            ExpressionNode subject = comparison.left();
            ExpressionNode singleton = comparison.right();
            if (isNullish(singleton)) {
                refineNullish(subject, singleton, matching, declaredTypeOf);
                return;
            }
            if (isNullish(subject)) {
                refineNullish(singleton, subject, matching, declaredTypeOf);
                return;
            }
            if (singleton instanceof UnaryExpr unary && unary.op() == UnaryOp.TYPEOF) {
                ExpressionNode swap = subject;
                subject = singleton;
                singleton = swap;
            }
            if (matching && subject instanceof UnaryExpr unary && unary.op() == UnaryOp.TYPEOF
                    && unary.expr() instanceof IdentifierExpr id
                    && singleton instanceof LiteralExpr literal
                    && literal.value() instanceof LiteralValue.StringLiteral text
                    && currentType(id.name(), declaredTypeOf) instanceof Type.Unknown) {
                Type type = switch (text.value()) {
                    case "undefined" -> Type.Undefined.INSTANCE;
                    case "boolean" -> Type.Boolean.INSTANCE;
                    case "number" -> Type.Number.INSTANCE;
                    case "string" -> Type.String.INSTANCE;
                    default -> null;
                };
                if (type != null) narrowed.put(id.name(), type);
                return;
            }
        }
    }

    public void narrow(String name, Type type) { narrowed.put(name, type); }

    private Type currentType(String name, Function<String, Type> declaredTypeOf) {
        return narrowed.getOrDefault(name, declaredTypeOf.apply(name));
    }

    private static boolean isNullish(ExpressionNode expression) {
        return isNullLiteral(expression) || expression instanceof IdentifierExpr id && id.name().equals("undefined");
    }

    private void refineNullish(ExpressionNode subject, ExpressionNode singleton, boolean matching,
                               Function<String, Type> declaredTypeOf) {
        if (!(subject instanceof IdentifierExpr id)) return;
        boolean undefined = singleton instanceof IdentifierExpr;
        Type current = currentType(id.name(), declaredTypeOf);
        if (matching) {
            narrowed.put(id.name(), undefined ? Type.Undefined.INSTANCE : Type.Null.INSTANCE);
        } else if (current instanceof Type.Nullable n) {
            if (undefined && n.inner() instanceof Type.Undefined && n.admitsNull()) {
                narrowed.put(id.name(), Type.Null.INSTANCE);
                return;
            }
            boolean nullFlag = n.admitsNull() && undefined;
            boolean undefinedFlag = n.admitsUndefined() && !undefined;
            narrowed.put(id.name(), !nullFlag && !undefinedFlag ? n.inner()
                : new Type.Nullable(n.inner(), nullFlag, undefinedFlag));
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

    /** Opens one block's shadow frame (the declaring block's entries). */
    public void pushShadowFrame() {
        shadowFrames.push(new ArrayList<>());
    }

    /**
     * Records one narrowed entry removed by a shadowing block-local
     * declaration; the innermost frame's entries are restored when the
     * declaring block exits. A declaration without an open frame (the
     * module scope) has no enclosing binding to restore.
     */
    public void recordShadowed(String varName, Type type) {
        if (!shadowFrames.isEmpty()) {
            shadowFrames.peek().add(Map.entry(varName, type));
        }
    }

    /**
     * Closes the innermost block's shadow frame, restoring the entries
     * the block's own declarations removed from the enclosing scope.
     */
    public void popShadowFrame() {
        if (shadowFrames.isEmpty()) return;
        for (Map.Entry<String, Type> entry : shadowFrames.pop()) {
            narrowed.put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Drops one variable's narrowed entry (the loop back-edge rule: an
     * assignment inside a loop body invalidates the entry after the loop).
     */
    public void invalidate(String varName) {
        narrowed.remove(varName);
    }

    /**
     * Clears all narrowed entries. Used after await expressions
     * because async suspension invalidates all flow-sensitive refinements.
     */
    public void invalidateAll() {
        narrowed.clear();
        // Every saved shadowed entry is a refinement of the enclosing
        // async function too: after the suspension no block exit may
        // resurrect an entry the invalidation cleared.
        for (List<Map.Entry<String, Type>> frame : shadowFrames) {
            frame.clear();
        }
    }

    /**
     * Merges narrowings from two branches (intersection: only keep if both
     * branches agree). An entry one branch invalidated is dropped, so an
     * assignment or an await on either path survives the join.
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
        mergeShadowFrames(other);
    }

    /**
     * Reconciles the shadow frames of two branch states: a saved snapshot
     * survives the join only when both paths still hold it, so an
     * invalidation on either path (an assignment snapshot removal or an
     * await's {@link #invalidateAll()}) wins over a harmless path — a
     * snapshot never resurrects a refinement the language invalidated.
     * The frames pair by block nesting: both branches are balanced over
     * the same enclosing blocks, so frame depth is the block depth.
     */
    private void mergeShadowFrames(NullNarrowing other) {
        List<List<Map.Entry<String, Type>>> mine =
            new ArrayList<>(shadowFrames);
        List<List<Map.Entry<String, Type>>> theirs =
            new ArrayList<>(other.shadowFrames);
        int depth = Math.min(mine.size(), theirs.size());
        for (int i = 0; i < depth; i++) {
            List<Map.Entry<String, Type>> merged = new ArrayList<>();
            for (Map.Entry<String, Type> entry : mine.get(i)) {
                for (Map.Entry<String, Type> candidate : theirs.get(i)) {
                    if (entry.getKey().equals(candidate.getKey())
                            && Types.equals(entry.getValue(),
                                candidate.getValue())) {
                        merged.add(entry);
                        break;
                    }
                }
            }
            mine.set(i, merged);
        }
        shadowFrames.clear();
        for (int i = mine.size() - 1; i >= 0; i--) {
            shadowFrames.push(mine.get(i));
        }
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
