package deal.checker;

import deal.ast.*;
import deal.types.Type;
import deal.types.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Forward flow facts owned by declaration identity, never identifier spelling. */
public final class NullNarrowing {
    /** A binding, or one exact declared field reached through that binding. */
    public record Subject(Symbol binding, ClassField field) {
        @Override public boolean equals(Object other) {
            return other instanceof Subject s && binding == s.binding && field == s.field;
        }
        @Override public int hashCode() {
            return 31 * System.identityHashCode(binding) + System.identityHashCode(field);
        }
    }

    /** Diagnostic evidence only; never participates in type or flow decisions. */
    public enum Invalidation { AWAIT, CALL, ASSIGNMENT, DELETE }
    public record LostRefinement(Type previous, deal.diagnostics.DiagnosticRange range, Invalidation operation) {}
    private final Map<Subject, LostRefinement> lostRefinements = new HashMap<>();
    private final Map<Subject, Type> narrowed = new HashMap<>();
    private Object propertyEpoch = new Object();

    public NullNarrowing() {}
    private NullNarrowing(NullNarrowing other) {
        narrowed.putAll(other.narrowed);
        lostRefinements.putAll(other.lostRefinements);
        propertyEpoch = other.propertyEpoch;
    }
    public Object propertyEpoch() { return propertyEpoch; }
    public NullNarrowing emptyBranch() {
        NullNarrowing result = branch();
        result.narrowed.clear();
        result.lostRefinements.clear();
        return result;
    }
    public NullNarrowing branch() { return new NullNarrowing(this); }

    public void onIfCondition(ExpressionNode cond, boolean matching,
                              Function<String, Symbol> resolveBinding,
                              Function<Symbol, Type> declaredTypeOf) {
        onCondition(cond, matching,
            expr -> expr instanceof IdentifierExpr id
                ? new Subject(resolveBinding.apply(id.name()), null) : null,
            expr -> expr instanceof IdentifierExpr id
                ? declaredTypeOf.apply(resolveBinding.apply(id.name())) : null);
    }

    public void onCondition(ExpressionNode cond, boolean enterTrueBranch,
                            Function<ExpressionNode, Subject> resolveSubject,
                            Function<ExpressionNode, Type> declaredTypeOf) {
        if (cond instanceof UnaryExpr unary && unary.op() == UnaryOp.NOT) {
            onCondition(unary.expr(), !enterTrueBranch, resolveSubject, declaredTypeOf);
            return;
        }
        if (!(cond instanceof BinaryExpr comparison)
                || (comparison.op() != BinaryOp.EQ && comparison.op() != BinaryOp.NEQ)) return;
        boolean matching = (comparison.op() == BinaryOp.EQ) == enterTrueBranch;
        ExpressionNode subject = comparison.left(), singleton = comparison.right();
        if (isNullish(subject)) {
            ExpressionNode swap = subject; subject = singleton; singleton = swap;
        }
        if (isNullish(singleton)) {
            Subject key = resolveSubject.apply(subject);
            if (key == null || !(key.binding() instanceof Symbol.VariableSymbol)) return;
            Type current = narrowed.getOrDefault(key, declaredTypeOf.apply(subject));
            boolean undefined = singleton instanceof IdentifierExpr;
            // Impossible guard arms are still checked: use the declared
            // nullish envelope rather than inventing a bottom type.
            if (undefined ? current instanceof Type.Undefined : current instanceof Type.Null)
                current = declaredTypeOf.apply(subject);
            if (matching) narrow(key, undefined ? Type.Undefined.INSTANCE : Type.Null.INSTANCE);
            else if (current instanceof Type.Nullable n) {
                if (undefined && n.inner() instanceof Type.Undefined && n.admitsNull()) {
                    narrow(key, Type.Null.INSTANCE);
                } else {
                    boolean nullFlag = n.admitsNull() && undefined;
                    boolean undefinedFlag = n.admitsUndefined() && !undefined;
                    narrow(key, !nullFlag && !undefinedFlag ? n.inner()
                        : new Type.Nullable(n.inner(), nullFlag, undefinedFlag));
                }
            }
            return;
        }
        if (singleton instanceof UnaryExpr unary && unary.op() == UnaryOp.TYPEOF) {
            ExpressionNode swap = subject; subject = singleton; singleton = swap;
        }
        if (subject instanceof UnaryExpr unary && unary.op() == UnaryOp.TYPEOF
                && singleton instanceof LiteralExpr literal
                && literal.value() instanceof LiteralValue.StringLiteral text) {
            Subject key = resolveSubject.apply(unary.expr());
            if (key == null) return;
            Type current = narrowed.getOrDefault(key, declaredTypeOf.apply(unary.expr()));
            Type type = switch (text.value()) {
                case "undefined" -> Type.Undefined.INSTANCE;
                case "boolean" -> Type.Boolean.INSTANCE;
                case "number" -> Type.Number.INSTANCE;
                case "string" -> Type.String.INSTANCE;
                default -> null;
            };
            if (type == null || current == null) return;
            if (current instanceof Type.Unknown) {
                if (matching) narrow(key, type);
            } else if (current instanceof Type.Nullable nullable) {
                if (type instanceof Type.Undefined) {
                    if (nullable.admitsUndefined() || nullable.inner() instanceof Type.Undefined) {
                        if (matching) narrow(key, type);
                        else if (nullable.inner() instanceof Type.Undefined) narrow(key, Type.Null.INSTANCE);
                        else narrow(key, nullable.admitsNull()
                            ? Types.withNullish(nullable.inner(), false, false) : nullable.inner());
                    }
                } else if (Types.equals(nullable.inner(), type)) {
                    if (matching) narrow(key, type);
                    else if (nullable.admitsNull() && nullable.admitsUndefined())
                        narrow(key, Types.optionalRead(Type.Null.INSTANCE));
                    else if (nullable.admitsNull()) narrow(key, Type.Null.INSTANCE);
                    else if (nullable.admitsUndefined()) narrow(key, Type.Undefined.INSTANCE);
                }
            }
        }
    }

    private static boolean isNullish(ExpressionNode expression) {
        return expression instanceof LiteralExpr literal && literal.value() instanceof LiteralValue.NullLiteral
            || expression instanceof IdentifierExpr id && id.name().equals("undefined");
    }

    public void narrow(Symbol binding, Type type) { narrow(new Subject(binding, null), type); }
    public void narrow(Subject subject, Type type) {
        if (subject != null && subject.binding() instanceof Symbol.VariableSymbol) {
            narrowed.put(subject, type);
            lostRefinements.remove(subject);
        }
    }
    public Type getNarrowedType(Symbol binding) { return getNarrowedType(new Subject(binding, null)); }
    public Type getNarrowedType(Subject subject) { return narrowed.get(subject); }
    public void onAssignment(Symbol binding) { invalidate(binding); }
    public LostRefinement lostRefinement(Subject subject) { return lostRefinements.get(subject); }
    /** Cleanup of a snapshot already invalidated at the owning assignment. */
    public void discardRefinement(Symbol binding) {
        narrowed.keySet().removeIf(key -> key.binding() == binding);
    }
    public void invalidate(Symbol binding) {
        narrowed.keySet().removeIf(key -> key.binding() == binding);
        lostRefinements.keySet().removeIf(key -> key.binding() == binding);
    }
    public void invalidateProperties() {
        narrowed.keySet().removeIf(key -> key.field() != null);
        lostRefinements.keySet().removeIf(key -> key.field() != null);
        propertyEpoch = new Object();
    }
    public void invalidateAtAssignment(Symbol binding, deal.diagnostics.DiagnosticRange range) {
        invalidateWithEvidence(subject -> subject.binding() == binding, range, Invalidation.ASSIGNMENT);
    }
    public void invalidatePropertiesAt(deal.diagnostics.DiagnosticRange range, Invalidation operation) {
        invalidateWithEvidence(subject -> subject.field() != null, range, operation);
        propertyEpoch = new Object();
    }
    private void invalidateWithEvidence(java.util.function.Predicate<Subject> affected,
            deal.diagnostics.DiagnosticRange range, Invalidation operation) {
        // An intervening invalidation supersedes old evidence even if no refinement remained.
        lostRefinements.keySet().removeIf(affected);
        narrowed.forEach((subject, type) -> {
            if (affected.test(subject)) lostRefinements.put(subject, new LostRefinement(type, range, operation));
        });
        narrowed.keySet().removeIf(affected);
    }
    public void invalidateAll() {
        narrowed.clear();
        lostRefinements.clear();
        propertyEpoch = new Object();
    }
    public void invalidateAtAwait(deal.diagnostics.DiagnosticRange range) {
        narrowed.forEach((subject, type) -> lostRefinements.put(subject, new LostRefinement(type, range, Invalidation.AWAIT)));
        narrowed.clear();
        propertyEpoch = new Object();
    }
    public boolean isEmpty() { return narrowed.isEmpty(); }

    /** Facts survive a join only for identical declarations and identical types. */
    public void merge(NullNarrowing other) {
        narrowed.entrySet().removeIf(entry -> !Types.equals(entry.getValue(), other.narrowed.get(entry.getKey())));
        // A single exact cause is advertised only when both incoming paths retain it.
        lostRefinements.entrySet().removeIf(entry -> !entry.getValue().equals(other.lostRefinements.get(entry.getKey())));
        if (propertyEpoch != other.propertyEpoch) propertyEpoch = new Object();
    }
    public void retainRefinements(NullNarrowing allowed) { merge(allowed); }
}
