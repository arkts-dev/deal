package deal.checker;

import deal.ast.*;
import deal.types.Type;
import deal.types.Types;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

/** Forward flow facts owned by declaration identity, never identifier spelling. */
public final class NullNarrowing {
    private final Map<Symbol, Type> narrowed = new IdentityHashMap<>();

    public NullNarrowing() {}

    private NullNarrowing(NullNarrowing other) {
        narrowed.putAll(other.narrowed);
    }

    public NullNarrowing branch() { return new NullNarrowing(this); }

    public void onIfCondition(ExpressionNode cond, boolean enterTrueBranch,
                              Function<String, Symbol> resolveBinding,
                              Function<Symbol, Type> declaredTypeOf) {
        if (cond instanceof UnaryExpr unary && unary.op() == UnaryOp.NOT) {
            onIfCondition(unary.expr(), !enterTrueBranch, resolveBinding, declaredTypeOf);
            return;
        }
        if (cond instanceof BinaryExpr comparison
                && (comparison.op() == BinaryOp.EQ || comparison.op() == BinaryOp.NEQ)) {
            boolean matching = (comparison.op() == BinaryOp.EQ) == enterTrueBranch;
            ExpressionNode subject = comparison.left();
            ExpressionNode singleton = comparison.right();
            if (isNullish(singleton)) {
                refineNullish(subject, singleton, matching, resolveBinding, declaredTypeOf);
                return;
            }
            if (isNullish(subject)) {
                refineNullish(singleton, subject, matching, resolveBinding, declaredTypeOf);
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
                    && literal.value() instanceof LiteralValue.StringLiteral text) {
                Symbol binding = resolveBinding.apply(id.name());
                if (binding == null || !(currentType(binding, declaredTypeOf) instanceof Type.Unknown)) return;
                Type type = switch (text.value()) {
                    case "undefined" -> Type.Undefined.INSTANCE;
                    case "boolean" -> Type.Boolean.INSTANCE;
                    case "number" -> Type.Number.INSTANCE;
                    case "string" -> Type.String.INSTANCE;
                    default -> null;
                };
                if (type != null) narrow(binding, type);
            }
        }
    }

    public void narrow(Symbol binding, Type type) {
        if (binding instanceof Symbol.VariableSymbol) narrowed.put(binding, type);
    }

    private Type currentType(Symbol binding, Function<Symbol, Type> declaredTypeOf) {
        return narrowed.getOrDefault(binding, declaredTypeOf.apply(binding));
    }

    private static boolean isNullish(ExpressionNode expression) {
        return expression instanceof LiteralExpr literal && literal.value() instanceof LiteralValue.NullLiteral
            || expression instanceof IdentifierExpr id && id.name().equals("undefined");
    }

    private void refineNullish(ExpressionNode subject, ExpressionNode singleton, boolean matching,
                               Function<String, Symbol> resolveBinding,
                               Function<Symbol, Type> declaredTypeOf) {
        if (!(subject instanceof IdentifierExpr id)) return;
        Symbol binding = resolveBinding.apply(id.name());
        if (!(binding instanceof Symbol.VariableSymbol)) return;
        boolean undefined = singleton instanceof IdentifierExpr;
        Type current = currentType(binding, declaredTypeOf);
        if (matching) {
            narrow(binding, undefined ? Type.Undefined.INSTANCE : Type.Null.INSTANCE);
        } else if (current instanceof Type.Nullable n) {
            if (undefined && n.inner() instanceof Type.Undefined && n.admitsNull()) {
                narrow(binding, Type.Null.INSTANCE);
                return;
            }
            boolean nullFlag = n.admitsNull() && undefined;
            boolean undefinedFlag = n.admitsUndefined() && !undefined;
            narrow(binding, !nullFlag && !undefinedFlag ? n.inner()
                : new Type.Nullable(n.inner(), nullFlag, undefinedFlag));
        }
    }

    public Type getNarrowedType(Symbol binding) { return narrowed.get(binding); }
    public void onAssignment(Symbol binding) { narrowed.remove(binding); }
    public void invalidate(Symbol binding) { narrowed.remove(binding); }
    public void invalidateAll() { narrowed.clear(); }
    public boolean isEmpty() { return narrowed.isEmpty(); }

    /** Facts survive a join only for the identical binding and identical type. */
    public void merge(NullNarrowing other) {
        narrowed.entrySet().removeIf(entry -> !Types.equals(entry.getValue(), other.narrowed.get(entry.getKey())));
    }

    public void retainRefinements(NullNarrowing allowed) { merge(allowed); }
}
