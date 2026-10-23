package deal.types;

import deal.identity.CanonicalClassIdentity;

import java.util.List;

public sealed interface Type
    permits Type.Null,
           Type.Boolean,
           Type.Int,
           Type.Number,
           Type.String,
           Type.Table,
           Type.Error,
           Type.Array,
           Type.Nullable,
           Type.Class,
           Type.Func {

    // -- Primitive types (enum singletons) --

    /** The {@code null} literal type. */
    enum Null implements Type { INSTANCE }

    enum Boolean implements Type { INSTANCE }
    enum Int implements Type { INSTANCE }
    enum Number implements Type { INSTANCE }
    enum String implements Type { INSTANCE }

    enum Table implements Type { INSTANCE }

    /**
     * Internal error sentinel returned when type checking fails on a
     * sub-expression.  Not to be confused with the user-visible
     * {@code Error} class type ({@link Type.Class}).
     */
    enum Error implements Type { INSTANCE }

    // -- Compound types --

    /** T[] — array with an element type. */
    record Array(Type element) implements Type {
        public Array {
            if (element == null) throw new IllegalArgumentException("element must not be null");
        }
    }

    /**
     * T | null — nullable wrapper.
     *
     * <p>Invariants enforced at construction:
     * <ul>
     *   <li>inner != Null.INSTANCE</li>
     *   <li>inner is not instanceof Nullable</li>
     * </ul>
     */
    record Nullable(Type inner) implements Type {
        public Nullable {
            if (inner == null) throw new IllegalArgumentException("inner must not be null");
            if (inner instanceof Null) throw new IllegalArgumentException(
                "Nullable inner must not be null; use Null.INSTANCE directly");
            if (inner instanceof Nullable) throw new IllegalArgumentException(
                "Nullable inner must not be another Nullable; flatten at construction");
        }
    }

    /**
     * Nominal class type, identified by name, canonical class identity
     * and — when the carrier binds one — the defining provider module
     * (ISSUE-0809 {@code exact_provider_carriage}): the canonical
     * identity's public projection is the defining declaration (root
     * text, the path to the defining file, and the class name), so the
     * identity alone already separates two same-named sibling
     * declarations; the provider keeps the distinct-declaration rule
     * explicit for carriers whose identities a harness shares (the
     * same-module nested-shadowing declarations). The
     * provider is the defining module's existing wiring key (the
     * {@code ClassSymbol.modulePath} the import/export routing uses),
     * never an import alias or public text; descriptor text still comes
     * only from the compilation's {@code CanonicalClassIdentityIndex}.
     * A {@code null} provider is the unbound carrier (public descriptor
     * synthesis, builtin {@code Error}, retained harnesses); the bound
     * forms keep same-spelled declarations of distinct providers
     * nominal-distinct. Uses fully qualified java.lang.String to avoid
     * ambiguity with the nested {@link Type.String} enum.
     */
    record Class(java.lang.String name, CanonicalClassIdentity identity,
                 java.lang.String providerModule) implements Type {
        public Class {
            if (name == null) throw new IllegalArgumentException("name must not be null");
            if (identity == null) throw new IllegalArgumentException("identity must not be null");
        }

        /**
         * The provider-unbound carrier (public descriptor synthesis, the
         * intrinsic builtin {@code Error}, retained harnesses).
         */
        public Class(java.lang.String name, CanonicalClassIdentity identity) {
            this(name, identity, null);
        }
    }

    /**
     * Function type: (T1, ..., TN) => R.
     *
     * <p>DEAL v1.2: no rest parameters.  Two function types are equal iff
     * their async markers, parameter lists, and return types match exactly.</p>
     *
     */
    record Func(
        List<Type> paramTypes,
        Type returnType,
        boolean isAsync
    ) implements Type {
        public Func {
            if (paramTypes == null) throw new IllegalArgumentException("paramTypes must not be null");
            if (returnType == null) throw new IllegalArgumentException("returnType must not be null");
        }

        /** Convenience constructor: sync function (isAsync = false). */
        public Func(List<Type> paramTypes, Type returnType) {
            this(paramTypes, returnType, false);
        }
    }
}
