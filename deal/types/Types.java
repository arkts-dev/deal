package deal.types;

import java.util.List;
import java.util.Objects;

/**
 * Static utility methods for working with internal {@link Type} values:
 * canonicalization, type equality, and arity extension checks.
 *
 * <p>DEAL v1.2: function types carry no rest parameter.  Function-type
 * equality is exact: async marker, parameter list, and return type.</p>
 */
public final class Types {

    private Types() { /* utility class */ }

    // =========================================================================
    // Type canonicalization (rules 1–13 from the design)
    // =========================================================================

    /**
     * Canonicalize a type, enforcing invariants and normalizing representation.
     *
     * <p>This is idempotent for already-canonical types but also serves
     * as the public entry point for constructing compound types from
     * user-provided type expressions.</p>
     *
     * @param type the type to canonicalize
     * @return the canonical form (may be the same object if already canonical)
     * @throws IllegalArgumentException if invariants are violated
     *         (e.g., Nullable(null) or Nullable(Nullable(T)))
     */
    public static Type canonicalize(Type type) {
        Objects.requireNonNull(type, "type must not be null");
        if (type instanceof Type.Nullable n) {
            if (n.inner() instanceof Type.Null) {
                throw new IllegalArgumentException("Nullable inner must not be null");
            }
            return new Type.Nullable(canonicalize(n.inner()));
        }
        if (type instanceof Type.Array a) {
            return new Type.Array(canonicalize(a.element()));
        }
        if (type instanceof Type.Func f) {
            List<Type> canonParams = f.paramTypes().stream()
                .map(Types::canonicalize)
                .toList();
            Type canonRet = canonicalize(f.returnType());
            return new Type.Func(canonParams, canonRet, f.isAsync());
        }
        return type;
    }

    // =========================================================================
    // Type equality (exact match)
    // =========================================================================

    /**
     * Returns {@code true} if two types are equal according to the DEAL
     * type equality rules:
     * <ul>
     *   <li>Primitives: same enum constant</li>
     *   <li>Array: equal element types</li>
     *   <li>Nullable: equal inner types</li>
     *   <li>Class: same name AND same modulePath (nominal)</li>
     *   <li>Function: equal paramTypes, equal returnType, AND equal isAsync</li>
     * </ul>
     */
    public static boolean equals(Type a, Type b) {
        if (a == b) return true;
        if (a == null || b == null) return false;

        if (a.getClass() != b.getClass()) return false;

        return switch (a) {
            case Type.Null ignored -> true;
            case Type.Boolean ignored -> true;
            case Type.Int ignored -> true;
            case Type.Number ignored -> true;
            case Type.String ignored -> true;
            case Type.Table ignored -> true;
            case Type.Error ignored -> true;

            case Type.Array aa -> {
                Type.Array ab = (Type.Array) b;
                yield equals(aa.element(), ab.element());
            }
            case Type.Nullable na -> {
                Type.Nullable nb = (Type.Nullable) b;
                yield equals(na.inner(), nb.inner());
            }
            case Type.Class ca -> {
                Type.Class cb = (Type.Class) b;
                yield ca.name().equals(cb.name())
                   && ca.modulePath().equals(cb.modulePath());
            }
            case Type.Func fa -> {
                Type.Func fb = (Type.Func) b;
                if (fa.isAsync() != fb.isAsync()) yield false;
                if (!equals(fa.returnType(), fb.returnType())) yield false;
                if (fa.paramTypes().size() != fb.paramTypes().size()) yield false;
                for (int i = 0; i < fa.paramTypes().size(); i++) {
                    if (!equals(fa.paramTypes().get(i), fb.paramTypes().get(i)))
                        yield false;
                }
                yield true;
            }
        };
    }

    // =========================================================================
    // Arity extension
    // =========================================================================

    /**
     * Checks whether a function type {@code actual} is assignable to
     * a function type {@code target} under the arity extension rule.
     *
     * <p>Arity extension: {@code (T1) => R} is assignable to
     * {@code (T1, T2) => R} (actual has fewer params than target).
     * The reverse is NOT allowed.
     *
     * <p>DEAL v1.2: function types have no rest parameters, so every
     * arity-extension comparison is against fixed parameter lists.</p>
     *
     * <p>Return types must match exactly.
     * Async/sync mismatch is rejected.</p>
     *
     * @param actual the source function type (e.g., the value being assigned)
     * @param target the destination function type (e.g., the variable/parameter type)
     * @return true if {@code actual} is assignable to {@code target}
     *         under arity extension rules
     */
    public static boolean isAssignable(Type.Func actual, Type.Func target) {
        // Async/sync mismatch — always reject
        if (actual.isAsync() != target.isAsync()) return false;

        // Exact match: always assignable
        if (equals(actual, target)) return true;

        // Return types must match exactly
        if (!equals(actual.returnType(), target.returnType())) return false;

        // Arity extension: actual can have FEWER params than target
        // (T1) => R is assignable to (T1, T2) => R
        int actualCount = actual.paramTypes().size();
        int targetCount = target.paramTypes().size();

        if (actualCount > targetCount) return false;

        // Each param of actual must match the corresponding param of target
        for (int i = 0; i < actualCount; i++) {
            if (!equals(actual.paramTypes().get(i), target.paramTypes().get(i)))
                return false;
        }

        // Remaining target params are "extra" — arity extension allows this
        return true;
    }

    /**
     * Convenience overload: checks if any function type is assignable to another.
     */
    public static boolean isAssignable(Type actual, Type target) {
        if (actual instanceof Type.Func af && target instanceof Type.Func tf) {
            return isAssignable(af, tf);
        }
        return equals(actual, target);
    }

    // =========================================================================
    // Factory helpers for common types
    // =========================================================================

    /** Make a Nullable type, canonicalizing the inner type. */
    public static Type.Nullable nullable(Type inner) {
        return (Type.Nullable) canonicalize(new Type.Nullable(inner));
    }

    /** Make an Array type, canonicalizing the element type. */
    public static Type.Array array(Type element) {
        return (Type.Array) canonicalize(new Type.Array(element));
    }

    /** Make a Class type. */
    public static Type.Class classType(java.lang.String name, java.lang.String modulePath) {
        return new Type.Class(name, modulePath);
    }

    /** Make a Function type, with isAsync. */
    public static Type.Func func(List<Type> paramTypes, Type returnType, boolean isAsync) {
        return (Type.Func) canonicalize(
            new Type.Func(List.copyOf(paramTypes), returnType, isAsync));
    }

    /** Make a sync Function type. */
    public static Type.Func func(List<Type> paramTypes, Type returnType) {
        return (Type.Func) canonicalize(
            new Type.Func(List.copyOf(paramTypes), returnType));
    }

    // =========================================================================
    // Null helpers
    // =========================================================================

    /**
     * Returns true when {@code t} is a null type ({@code Null}).
     */
    public static boolean isNull(Type t) {
        return t instanceof Type.Null;
    }
}
