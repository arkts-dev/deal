package deal.semantic.ir;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** ECMAScript Number::toString (radix ten) for language-visible interpolation. */
public final class NumberSemantics {
    private NumberSemantics() {}

    /** ECMAScript ToIntegerOrInfinity, clamped before any host integer cast. */
    public static int spliceStart(double value, int length) {
        if (Double.isNaN(value) || value == 0) return 0;
        double integer = value < 0 ? Math.ceil(value) : Math.floor(value);
        return (int) Math.max(0, Math.min(length, integer < 0 ? length + integer : integer));
    }

    public static int spliceCount(double value, int available) {
        if (Double.isNaN(value) || value <= 0) return 0;
        return (int) Math.min(available, Math.floor(value));
    }

    public static String toString(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (value == Double.POSITIVE_INFINITY) return "Infinity";
        if (value == Double.NEGATIVE_INFINITY) return "-Infinity";
        if (value == 0) return "0";
        boolean negative = value < 0;
        double magnitude = Math.abs(value);
        BigDecimal exact = new BigDecimal(magnitude);
        BigDecimal shortest = exact;
        for (int precision = 1; precision <= 17; precision++) {
            BigDecimal rounded = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            if (Double.parseDouble(rounded.toString()) != magnitude) {
                // At powers of two the binary rounding interval is asymmetric.
                // The other adjacent decimal can be inside its wider half.
                BigDecimal step = BigDecimal.ONE.scaleByPowerOfTen(
                    exact.precision() - exact.scale() - precision);
                rounded = rounded.add(exact.compareTo(rounded) > 0 ? step : step.negate());
            }
            if (Double.parseDouble(rounded.toString()) == magnitude) {
                shortest = rounded.stripTrailingZeros();
                break;
            }
        }
        int exponent = shortest.precision() - shortest.scale() - 1;
        String text;
        if (exponent >= -6 && exponent < 21) {
            text = shortest.toPlainString();
        } else {
            String digits = shortest.unscaledValue().toString();
            text = digits.substring(0, 1) + (digits.length() > 1 ? "." + digits.substring(1) : "")
                + "e" + (exponent >= 0 ? "+" : "") + exponent;
        }
        return (negative ? "-" : "") + text;
    }
}
