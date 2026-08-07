package deal.ast;

/**
 * Sealed hierarchy for literal values appearing in {@link LiteralExpr}.
 */
public sealed interface LiteralValue
    permits LiteralValue.NullLiteral,
            LiteralValue.BooleanLiteral,
            LiteralValue.IntLiteral,
            LiteralValue.NumberLiteral,
            LiteralValue.StringLiteral {

    record NullLiteral() implements LiteralValue {
        @Override public String toString() { return "null"; }
    }

    record BooleanLiteral(boolean value) implements LiteralValue {
        @Override public String toString() { return String.valueOf(value); }
    }

    record IntLiteral(long value) implements LiteralValue {
        @Override public String toString() { return String.valueOf(value); }
    }

    record NumberLiteral(double value) implements LiteralValue {
        @Override public String toString() { return String.valueOf(value); }
    }

    record StringLiteral(String value) implements LiteralValue {
        @Override public String toString() { return "\"" + value + "\""; }
    }
}
