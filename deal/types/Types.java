package deal.types;

import deal.identity.CanonicalClassIdentity;

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
        // Every primitive is already canonical and returned unchanged (reference identity
        // preserved for the enum singletons).
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
     *   <li>Class: equal canonical class identities (nominal)</li>
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
                // v1.2 identity carriage: nominal equality is canonical
                // class-identity equality (record structural equality
                // over (moduleIdentity, className)) — never dotted-path
                // text comparison (descriptor-identity-propagation D1).
                //
                // The canonical identity names the defining file, so two
                // same-named sibling declarations of one directory never
                // share it. ISSUE-0809 exact_provider_carriage additionally
                // makes the defining provider participate in nominal
                // equality, so two declarations that a harness gives one
                // canonical identity (the same-module nested-shadowing
                // declarations, synthetic carriers) never compare equal
                // either. The unbound carrier (null: public descriptor
                // synthesis, the builtin Error, retained harnesses) names
                // no defining declaration and equals only another unbound
                // one.
                Type.Class cb = (Type.Class) b;
                yield ca.identity().equals(cb.identity())
                    && providerCompatible(ca.providerModule(),
                        cb.providerModule());
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

    /**
     * The canonical nullable wrap of one type — the spec's {@code T | null}
     * type ({@code docs/spec-v1.3.md} §Types: {@code T | null}
     * canonicalizes to {@code Nullable<T>}). The wrap canonicalizes its inner
     * type at construction, so an already-nullable type is its own wrap and
     * so is {@code null} itself: {@code null | null} is {@code null}, which
     * is the derived read type of an optional field declared {@code null}
     * and never the forbidden {@code Nullable<Null>} form. The one caller is
     * the optional-field read type; no surface annotation produces a
     * {@code null | null} spelling.
     */
    public static Type nullableOrSelf(Type inner) {
        Objects.requireNonNull(inner, "inner must not be null");
        return inner instanceof Type.Null || inner instanceof Type.Nullable
            ? inner : nullable(inner);
    }

    /** Make an Array type, canonicalizing the element type. */
    public static Type.Array array(Type element) {
        return (Type.Array) canonicalize(new Type.Array(element));
    }

    /**
     * Make a Class type carrying its canonical class identity
     * (descriptor-identity-propagation D1): the identity is supplied by
     * the module-identity layer, never reconstructed here. The provider
     * stays unbound (public descriptor synthesis, the builtin
     * {@code Error}, retained harnesses).
     */
    public static Type.Class classType(java.lang.String name, CanonicalClassIdentity identity) {
        return new Type.Class(name, identity);
    }

    /**
     * Make a Class type carrying its canonical class identity and the
     * defining provider module (ISSUE-0809 exact_provider_carriage): the
     * provider is the declaring module's own wiring key. The public
     * identity already names the defining file, so two sibling files'
     * same-named declarations differ there; the provider keeps the
     * distinct-declaration rule explicit for declarations whose identity a
     * harness shares (the same-module nested-shadowing declarations). Only
     * synthesis sites that know the declaring module supply it; the
     * unbound form remains for public descriptor and harness carriers.
     */
    public static Type.Class classType(java.lang.String name,
                                       CanonicalClassIdentity identity,
                                       java.lang.String providerModule) {
        return new Type.Class(name, identity, providerModule);
    }

    /**
     * The defining provider module of one declaration's wiring key: the
     * declaring module's own path, or {@code null} for the unbound
     * carrier (the intrinsic builtin module's empty key). The single
     * derivation every checked-class synthesis site consumes, so one
     * declaration never yields two provider spellings.
     */
    public static java.lang.String providerModule(java.lang.String wiringPath) {
        return wiringPath == null || wiringPath.isEmpty()
            ? null : wiringPath;
    }

    /**
     * Provider compatibility for nominal class equality: the defining
     * provider participates (two declarations of distinct providers are
     * never equal); an unbound (null) carrier names no defining
     * declaration and equals only another unbound one.
     */
    private static boolean providerCompatible(java.lang.String a,
                                              java.lang.String b) {
        return java.util.Objects.equals(a, b);
    }

    /**
     * The two class types name two distinct declarations of one same-named
     * class through two bound, distinct defining providers — the sibling
     * declaration crossing (two modules declaring the same class name, the
     * same-module nested-shadowing declaration, or a harness whose two
     * carriers share one canonical identity). The caller reports the named
     * cross-declaration failure instead of a bare same-spelled type
     * mismatch. Unbound carriers never cross (they name no second
     * declaration).
     */
    public static boolean crossesClassDeclarations(Type expected, Type actual) {
        Type.Class expectedClass = nonNullClassOrNull(expected);
        Type.Class actualClass = nonNullClassOrNull(actual);
        if (expectedClass == null || actualClass == null) {
            return false;
        }
        return expectedClass.name().equals(actualClass.name())
            && expectedClass.providerModule() != null
            && actualClass.providerModule() != null
            && !expectedClass.providerModule().equals(actualClass.providerModule());
    }

    /** The innermost class under any nullable wrapper, or null. */
    private static Type.Class nonNullClassOrNull(Type type) {
        Type current = type;
        while (current instanceof Type.Nullable nullable) {
            current = nullable.inner();
        }
        return current instanceof Type.Class cls ? cls : null;
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

    public static boolean isNull(Type t) {
        return t instanceof Type.Null;
    }
}
