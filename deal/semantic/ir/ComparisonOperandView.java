package deal.semantic.ir;

import java.util.Objects;

public sealed interface ComparisonOperandView
    permits ComparisonOperandView.Null,
            ComparisonOperandView.Missing,
            ComparisonOperandView.Boolean,
            ComparisonOperandView.Int,
            ComparisonOperandView.Number,
            ComparisonOperandView.String,
            ComparisonOperandView.Ref {

    /** Language null ({@link ActualKind#NULL}). */
    enum Null implements ComparisonOperandView {
        INSTANCE
    }

    /**
     * Internal missing ({@link ActualKind#MISSING}) — a past-end array
     * read at a comparison operand position. Compares as language null at
     * both operand positions (B-D1).
     */
    enum Missing implements ComparisonOperandView {
        INSTANCE
    }

    /** A boolean value. */
    record Boolean(boolean value) implements ComparisonOperandView {
    }

    /** A signed32 integer value (the Java {@code int} carrier is exactly signed32). */
    record Int(int value) implements ComparisonOperandView {
    }

    /** An IEEE-754 number value (NaN and infinity included). */
    record Number(double value) implements ComparisonOperandView {
    }

    /**
     * A string value carrying validated Unicode scalars: the
     * {@link UnicodeScalars.Valid} record, whose constructor fails closed
     * for a lone UTF-16 surrogate unit, so a {@code String} view always
     * denotes a valid scalar sequence (invalid scalar encodings never
     * reach a comparison — they are rejected at the producing string
     * boundary).
     */
    record String(UnicodeScalars.Valid scalars) implements ComparisonOperandView {

        public String {
            Objects.requireNonNull(scalars, "scalars must not be null");
        }
    }

    /**
     * A reference value carrying an opaque allocation-identity token:
     * the identity of an array, a table, a class instance, or a
     * function. Two views denote the same allocation exactly when their
     * tokens are equal by {@link Objects#equals(Object, Object)}
     * (instance tokens by object identity; identity records by their
     * value equality).
     */
    record Ref(Object allocationIdentity) implements ComparisonOperandView {

        public Ref {
            Objects.requireNonNull(allocationIdentity, "allocationIdentity must not be null");
        }
    }
}
