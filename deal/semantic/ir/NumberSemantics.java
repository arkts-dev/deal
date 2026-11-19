package deal.semantic.ir;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** ECMAScript Number::toString (radix ten) for language-visible interpolation. */
public final class NumberSemantics {
    private NumberSemantics() {}

    /** Whole-string ECMAScript StringToNumber, not Java's wider numeric grammar. */
    public static double fromString(String text) {
        String s = StringSemantics.trim(text);
        if (s.isEmpty()) return 0;
        if (s.equals("Infinity") || s.equals("+Infinity")) return Double.POSITIVE_INFINITY;
        if (s.equals("-Infinity")) return Double.NEGATIVE_INFINITY;
        if (s.matches("0[xX][0-9a-fA-F]+")) return new java.math.BigInteger(s.substring(2), 16).doubleValue();
        if (s.matches("0[bB][01]+")) return new java.math.BigInteger(s.substring(2), 2).doubleValue();
        if (s.matches("0[oO][0-7]+")) return new java.math.BigInteger(s.substring(2), 8).doubleValue();
        if (!s.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) return Double.NaN;
        return Double.parseDouble(s);
    }

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

    public static boolean fixedDigits(double digits) {
        return digits >= 0 && digits <= 100 && digits == Math.floor(digits);
    }

    /** Exact binary64 rounding, ties away from zero; callers admit digits first. */
    public static String toFixed(double value, int digits) {
        if (!Double.isFinite(value) || Math.abs(value) >= 1e21) return toString(value);
        return (value < 0 ? "-" : "") + new BigDecimal(Math.abs(value))
            .setScale(digits, RoundingMode.HALF_UP).toPlainString();
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
