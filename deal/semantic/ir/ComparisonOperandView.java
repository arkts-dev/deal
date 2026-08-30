package deal.semantic.ir;

import java.util.Objects;

/**
 * The closed operand view of a {@code BINARY} comparison op
 * (ISSUE-0234 design B-D1, binary-comparison-selectors): the completed
 * operand data the comparison's single execution form
 * ({@link ComparisonExecutor}) consumes. Exactly:
 *
 * <pre>{@code
 * Null | Missing | Boolean(bool) | Int(signed32) | Number(double)
 *      | String(validated scalars) | Ref(allocationIdentity)
 * }</pre>
 *
 * <p>No other shape exists. Operands are completed {@code ValueId}s
 * evaluated left-to-right before {@code BINARY} START; the enclosing op
 * stream owns evaluation order and single evaluation (B-D1) — this view
 * is pure classification data, never a target representation and never
 * an op. A {@code Missing} operand — a past-end array read at a
 * comparison operand position — compares as language null at both
 * operand positions: no typed boundary runs at a comparison operand and
 * no E8001 is raised there (B-D1/B-D5). Strings carry validated scalars:
 * the {@link String} variant holds a {@link UnicodeScalars.Valid} record,
 * whose construction fails closed for a lone UTF-16 surrogate unit, so
 * invalid scalar encodings are rejected at the producing string
 * boundary and never reach a comparison. {@code Ref} carries an opaque
 * allocation-identity token (array, table, class instance, or function);
 * {@link ComparisonExecutor} compares tokens with
 * {@link Objects#equals(Object, Object)} — unique instance tokens
 * compare by object identity, and identity-record tokens (for example
 * {@link FunctionAllocationIdentity}) by their value equality, which is
 * the allocation identity.</p>
 *
 * <p>The view is realized by the oracle's value model and the shared
 * emitters' adapter layers; the executor interprets variants only. The
 * component depends on the closed scalar model
 * ({@link UnicodeScalars}) and nothing else in the schema.</p>
 */
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
