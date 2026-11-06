package deal.semantic.ir;

import java.util.ArrayList;
import java.util.List;

/** ECMAScript string algorithms over UTF-16 carriers. */
public final class StringSemantics {
    private StringSemantics() {}

    /** Display only: UTF-8 cannot encode isolated UTF-16 surrogate units. */
    public static String consoleDisplay(String text) {
        StringBuilder display = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char unit = text.charAt(i);
            if (Character.isHighSurrogate(unit) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                display.append(unit).append(text.charAt(++i));
            } else {
                display.append(Character.isSurrogate(unit) ? '\uFFFD' : unit);
            }
        }
        return display.toString();
    }

    public static String substring(String text, double start, double end) {
        int a = bound(start, text.length());
        int b = bound(end, text.length());
        return text.substring(Math.min(a, b), Math.max(a, b));
    }

    private static int bound(double value, int length) {
        if (Double.isNaN(value) || value <= 0) return 0;
        return value >= length ? length : (int) value;
    }

    public static String replace(String text, String search, String replacement) {
        int at = text.indexOf(search);
        if (at < 0) return text;
        StringBuilder result = new StringBuilder(text.substring(0, at));
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c == '$' && i + 1 < replacement.length()) {
                switch (replacement.charAt(i + 1)) {
                    case '$' -> { result.append('$'); i++; continue; }
                    case '&' -> { result.append(search); i++; continue; }
                    case '`' -> { result.append(text, 0, at); i++; continue; }
                    case '\'' -> { result.append(text, at + search.length(), text.length()); i++; continue; }
                    default -> { }
                }
            }
            result.append(c);
        }
        return result.append(text.substring(at + search.length())).toString();
    }

    public static List<String> split(String text, String separator) {
        List<String> parts = new ArrayList<>();
        if (separator.isEmpty()) {
            for (int i = 0; i < text.length(); i++) parts.add(text.substring(i, i + 1));
        } else {
            int cursor = 0;
            int at;
            while ((at = text.indexOf(separator, cursor)) >= 0) {
                parts.add(text.substring(cursor, at));
                cursor = at + separator.length();
            }
            parts.add(text.substring(cursor));
        }
        return parts;
    }

    public static boolean isWhitespace(int unit) {
        return unit >= 0x09 && unit <= 0x0d || unit == 0x20 || unit == 0xa0
            || unit == 0x1680 || unit >= 0x2000 && unit <= 0x200a
            || unit == 0x2028 || unit == 0x2029 || unit == 0x202f
            || unit == 0x205f || unit == 0x3000 || unit == 0xfeff;
    }

    public static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isWhitespace(text.charAt(start))) start++;
        while (end > start && isWhitespace(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }
}
