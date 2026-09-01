package deal.types;

import deal.identity.CanonicalClassIdentity;

import java.util.List;

/**
 * Sealed hierarchy for internal type representation used during type checking.
 * All canonical forms are covered.
 *
 * <p>Type equality is structural identity for primitives/arrays/nullables,
 * nominal for classes, and exact match for functions (with arity extension).</p>
 *
 * <p>DEAL v1.2: function types have no rest parameters.  A function type is
 * exactly an async marker, a fixed parameter list, and a return type.</p>
 *
 * <p>DEAL v1.2: {@link Bytes} is the canonical bytes primitive
 * (descriptor {@code bytes}, invariant, identity-compared).  The value
 * representation, allocation, indexing, and mutation surface is owned by
 * a later epic; the type layer pins the type and its
 * {@linkplain Types#containsBytes(Type) containsBytes} recursion only.</p>
 */
public sealed interface Type
    permits Type.Null,
           Type.Boolean,
           Type.Int,
           Type.Number,
           Type.String,
           Type.Bytes,
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

    /**
     * The canonical {@code bytes} primitive type (DEAL v1.2).  Invariant
     * and identity-compared; legal inside every Array/Nullable/Function/
     * Class construction at arbitrary depth.  The runtime value
     * representation and operations are not part of the type layer.
     */
    enum Bytes implements Type { INSTANCE }

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
     * Nominal class type, identified by name and canonical class
     * identity (the v1.2 identity carriage — design source
     * {@code descriptor-identity-propagation} D1): equality is identity
     * equality and descriptor text comes only from the compilation's
     * {@code CanonicalClassIdentityIndex}.  The retired dotted
     * {@code modulePath} carrier no longer exists; the private
     * deployment module id stays the import/export wiring key outside
     * the type.  Uses fully qualified java.lang.String to avoid
     * ambiguity with the nested {@link Type.String} enum.
     */
    record Class(java.lang.String name, CanonicalClassIdentity identity) implements Type {
        public Class {
            if (name == null) throw new IllegalArgumentException("name must not be null");
            if (identity == null) throw new IllegalArgumentException("identity must not be null");
        }
    }

    /**
     * Function type: (T1, ..., TN) => R.
     *
     * <p>DEAL v1.2: no rest parameters.  Two function types are equal iff
     * their async markers, parameter lists, and return types match exactly.</p>
     *
     * @param paramTypes parameter types in order
     * @param returnType return type
     * @param isAsync    whether this function type is async
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
