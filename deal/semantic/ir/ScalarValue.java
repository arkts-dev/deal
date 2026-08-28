package deal.semantic.ir;

import java.util.Objects;

/**
 * The closed canonical scalar of {@code CONST} (parent closed operation
 * table): exactly {@code null}, {@code boolean}, signed32 {@code int},
 * {@code number}, or {@code string}. Out-of-range int literals never reach
 * the IR (E1036 frontend), so {@link Int} is a Java {@code int} and no
 * safe-range representation exists.
 *
 * <p>Closed sealed family; no other scalar shape exists.</p>
 */
public sealed interface ScalarValue
    permits ScalarValue.Null, ScalarValue.Boolean, ScalarValue.Int,
            ScalarValue.Number, ScalarValue.String {

    /** The canonical {@code null} scalar. */
    enum Null implements ScalarValue {
        INSTANCE
    }

    /** A canonical boolean scalar. */
    record Boolean(boolean value) implements ScalarValue {
    }

    /** A canonical signed32 integer scalar. */
    record Int(int value) implements ScalarValue {
    }

    /** A canonical IEEE-754 number scalar. */
    record Number(double value) implements ScalarValue {
    }

    /** A canonical string scalar. */
    record String(java.lang.String value) implements ScalarValue {

        public String {
            Objects.requireNonNull(value, "value must not be null");
        }
    }
}
