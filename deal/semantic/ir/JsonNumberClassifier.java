package deal.semantic.ir;

import java.util.Objects;

/**
 * The one exact decimal classification of the RFC-8259 numeric lexemes
 * the JSON parsers scan (DEAL v1.3 JSON number classification): a
 * grammar-valid numeric token denotes the signed32 integer
 * {@link Integer} if and only if its exact mathematical decimal value is
 * integral and inside {@code [-2147483648, 2147483647]}; every other
 * token denotes the IEEE-754 binary64 {@code double} of the standard
 * decimal-to-double conversion.
 *
 * <p>Classification is exact and precedes binary64 rounding: the token's
 * coefficient digits, fraction scale, and exponent sign/magnitude are
 * combined without constructing unbounded powers of ten — zero
 * classifies immediately, the fractional scale is cancelled by the
 * coefficient's trailing zeroes, and the exponent text is processed with
 * bounded magnitude comparisons (never a machine-int overflow and never
 * an input-length limit). {@code "1"}, {@code "1.0"}, {@code "1e0"},
 * {@code "100e-2"}, and {@code "-0.0"} therefore classify as int
 * ({@code -0} normalizing to {@code 0}), while a fractional value that
 * merely rounds to an integral double (for example
 * {@code "2147483647.00000001"}) stays a number. The scan is linear in
 * the lexeme length and allocates nothing per digit.</p>
 */
public final class JsonNumberClassifier {

    private JsonNumberClassifier() {
        // Static algorithm contract; no instances.
    }

    /** The signed32 magnitude bound: 2147483647 for positive, 2147483648 for negative. */
    private static final long POSITIVE_LIMIT = 2147483647L;
    private static final long NEGATIVE_LIMIT = 2147483648L;

    /**
     * The exponent magnitude saturation: any exponent whose magnitude
     * exceeds this bound classifies by the bound (well beyond every
     * meaningful coefficient length), so the digit/exponent arithmetic
     * never overflows.
     */
    private static final long EXPONENT_SATURATION = 1_000_000_000_000L;

    /**
     * The exact signed32 value of one grammar-valid RFC-8259 numeric
     * lexeme whose mathematical value is integral and inside
     * {@code [-2147483648, 2147483647]}, or {@code null} when the value is
     * fractional or outside the signed32 range (the caller decodes the
     * lexeme to binary64). {@code -0} and {@code -0.0} yield the int
     * {@code 0}.
     */
    public static Integer exactInt32OrNull(String lexeme) {
        Objects.requireNonNull(lexeme, "lexeme must not be null");

        int i = 0;
        boolean negative = false;
        if (lexeme.charAt(i) == '-') {
            negative = true;
            i++;
        }
        int intStart = i;
        while (i < lexeme.length() && isDigit(lexeme.charAt(i))) {
            i++;
        }
        int intEnd = i;
        int fracStart = -1;
        int fracEnd = -1;
        if (i < lexeme.length() && lexeme.charAt(i) == '.') {
            i++;
            fracStart = i;
            while (i < lexeme.length() && isDigit(lexeme.charAt(i))) {
                i++;
            }
            fracEnd = i;
        }

        // The shared decimal exponent: 0 unless the token carries one; the
        // magnitude saturates so the arithmetic below cannot overflow.
        long exponent = 0;
        if (i < lexeme.length() && (lexeme.charAt(i) == 'e' || lexeme.charAt(i) == 'E')) {
            i++;
            boolean exponentNegative = false;
            if (lexeme.charAt(i) == '+' || lexeme.charAt(i) == '-') {
                exponentNegative = lexeme.charAt(i) == '-';
                i++;
            }
            long magnitude = 0;
            while (i < lexeme.length()) {
                int digit = lexeme.charAt(i) - '0';
                if (magnitude > (EXPONENT_SATURATION - digit) / 10) {
                    magnitude = EXPONENT_SATURATION;
                } else {
                    magnitude = magnitude * 10 + digit;
                }
                i++;
            }
            exponent = exponentNegative ? -magnitude : magnitude;
        }

        // The virtual coefficient digit sequence: the integer digits then
        // the fraction digits. All-zero coefficients (including "-0" and
        // "-0.0") classify immediately as the integral value 0.
        int coefficientLength = (intEnd - intStart) + (fracEnd - fracStart >= 0
            ? fracEnd - fracStart : 0);
        int firstNonZero = firstNonZeroDigit(lexeme, intStart, intEnd, fracStart, fracEnd,
            coefficientLength);
        if (firstNonZero < 0) {
            return 0;
        }
        int trailingZeroes = 0;
        while (digitAt(lexeme, intStart, intEnd, fracStart, fracEnd,
                coefficientLength - 1 - trailingZeroes) == '0') {
            trailingZeroes++;
        }

        // The remaining significant digits carry no trailing zero, so the
        // mathematical value is integral exactly when the trailing zeroes
        // cancel the fractional scale (the exponent minus the fraction
        // length) completely.
        long fractionLength = Math.max(0, fracEnd - fracStart);
        long effectiveScale = saturatingAdd(exponent, trailingZeroes - fractionLength);
        if (effectiveScale < 0) {
            return null;
        }
        int significantLength = coefficientLength - firstNonZero - trailingZeroes;
        long totalDigits = saturatingAdd(effectiveScale, significantLength);
        if (totalDigits > 10) {
            return null;
        }

        // At most ten decimal digits: the long accumulation and the
        // bounded power-of-ten multiply are exact.
        long magnitude = 0;
        for (int d = 0; d < significantLength; d++) {
            magnitude = magnitude * 10
                + (digitAt(lexeme, intStart, intEnd, fracStart, fracEnd,
                    firstNonZero + d) - '0');
        }
        for (int d = 0; d < effectiveScale; d++) {
            magnitude = magnitude * 10;
        }
        long limit = negative ? NEGATIVE_LIMIT : POSITIVE_LIMIT;
        if (magnitude > limit) {
            return null;
        }
        return (int) (negative ? -magnitude : magnitude);
    }

    /**
     * The IEEE-754 binary64 value of one grammar-valid RFC-8259 numeric
     * lexeme: the standard correctly-rounded decimal-to-double
     * conversion (a magnitude above the binary64 range converts to
     * infinity, and one below its smallest subnormal to a signed zero).
     */
    public static double toDouble(String lexeme) {
        Objects.requireNonNull(lexeme, "lexeme must not be null");
        return Double.parseDouble(lexeme);
    }

    /** The index of the first non-zero coefficient digit, or -1 when all are zero. */
    private static int firstNonZeroDigit(String lexeme, int intStart, int intEnd,
                                         int fracStart, int fracEnd,
                                         int coefficientLength) {
        for (int d = 0; d < coefficientLength; d++) {
            if (digitAt(lexeme, intStart, intEnd, fracStart, fracEnd, d) != '0') {
                return d;
            }
        }
        return -1;
    }

    /** The d-th coefficient digit: integer digits first, then fraction digits. */
    private static char digitAt(String lexeme, int intStart, int intEnd, int fracStart,
                                int fracEnd, int d) {
        if (d < intEnd - intStart) {
            return lexeme.charAt(intStart + d);
        }
        return lexeme.charAt(fracStart + (d - (intEnd - intStart)));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** One saturating addition over the bounded exponent/digit magnitudes. */
    private static long saturatingAdd(long left, long right) {
        long sum = left + right;
        if (left > 0 && right > 0 && sum < 0) {
            return Long.MAX_VALUE;
        }
        if (left < 0 && right < 0 && sum > 0) {
            return Long.MIN_VALUE;
        }
        return sum;
    }
}
