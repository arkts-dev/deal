package deal.types;

import java.util.List;
import java.util.Optional;

/**
 * Sealed hierarchy for internal type representation used during type checking.
 * All canonical forms are covered.
 *
 * <p>Type equality is structural identity for primitives/arrays/nullables,
 * nominal for classes, and exact match for functions (with arity extension).</p>
 */
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
     * Nominal class type, identified by name and module path.
     * Uses fully qualified java.lang.String to avoid ambiguity
     * with the nested {@link Type.String} enum.
     */
    record Class(java.lang.String name, java.lang.String modulePath) implements Type {
        public Class {
            if (name == null) throw new IllegalArgumentException("name must not be null");
            if (modulePath == null) throw new IllegalArgumentException("modulePath must not be null");
        }
    }

    /**
     * Function type: (T1, ..., TN, ...rest?) => R.
     *
     * @param paramTypes non-rest parameter types in order
     * @param restType   rest parameter type, if any (must be an Array type)
     * @param returnType return type
     * @param isAsync    whether this function type is async
     */
    record Func(
        List<Type> paramTypes,
        Optional<Array> restType,
        Type returnType,
        boolean isAsync
    ) implements Type {
        public Func {
            if (paramTypes == null) throw new IllegalArgumentException("paramTypes must not be null");
            if (restType == null) throw new IllegalArgumentException("restType must not be null");
            if (returnType == null) throw new IllegalArgumentException("returnType must not be null");
        }

        /** Convenience constructor: sync function (isAsync = false). */
        public Func(List<Type> paramTypes, Optional<Array> restType, Type returnType) {
            this(paramTypes, restType, returnType, false);
        }
    }
}
