package deal.semantic.ir;

import java.util.Objects;

/**
 * The closed canonical scalar of {@code CONST} (parent closed operation
 * table): exactly {@code null}, {@code boolean}, binary64 {@code number},
 * or {@code string}.
 *
 * <p>Closed sealed family; no other scalar shape exists.</p>
 */
public sealed interface ScalarValue
    permits ScalarValue.Null, ScalarValue.Boolean,
            ScalarValue.Number, ScalarValue.String {

    /** The canonical {@code null} scalar. */
    enum Null implements ScalarValue {
        INSTANCE
    }

    /** A canonical boolean scalar. */
    record Boolean(boolean value) implements ScalarValue {
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
