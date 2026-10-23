package deal.semantic.ir;

import java.util.Objects;

/** Binary64 decoding of grammar-validated RFC-8259 numeric lexemes. */
public final class JsonNumberClassifier {
    private JsonNumberClassifier() {}

    /** Correctly rounded conversion, preserving signed zero and overflow to infinity. */
    public static double toDouble(String lexeme) {
        return Double.parseDouble(Objects.requireNonNull(lexeme, "lexeme must not be null"));
    }
}
