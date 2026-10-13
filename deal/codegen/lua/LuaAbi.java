package deal.codegen.lua;

import java.util.Set;

public final class LuaAbi {

    private LuaAbi() {
        // Stateless utility class — no instances.
    }

    /** Lua 5.1 keywords and LuaJIT 2.1 hard tokens. */
    private static final Set<String> RESERVED = Set.of(
        "and", "break", "do", "else", "elseif", "end", "false", "for",
        "function", "goto", "if", "in", "local", "nil", "not", "or",
        "repeat", "return", "then", "true", "until", "while",
        "const", "continue");

    /** ASCII DEAL identifier shape, excluding Lua keywords. */
    private static boolean isSafeIdentifier(String name) {
        if (name == null || name.isEmpty()) return false;
        char first = name.charAt(0);
        if (!isAsciiLetter(first) && first != '_') return false;
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!isAsciiLetter(c) && c != '_' && (c < '0' || c > '9')) {
                return false;
            }
        }
        return !RESERVED.contains(name);
    }

    private static String stringKeyLiteral(String key) {
        return "[" + stringLiteral(key) + "]";
    }

    /** Lua 5.1 string literal; decimal control escapes cannot absorb digits. */
    public static String stringLiteral(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\u0007' -> sb.append("\\a");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\u000b' -> sb.append("\\v");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        appendDecimalEscape(sb, c,
                            i + 1 < text.length()
                                && isDecimalDigit(text.charAt(i + 1)));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private static void appendDecimalEscape(StringBuilder sb, char c,
                                            boolean followedByDigit) {
        String digits = Integer.toString(c);
        if (followedByDigit) {
            while (digits.length() < 3) {
                digits = "0" + digits;
            }
        }
        sb.append('\\').append(digits);
    }

    private static boolean isDecimalDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    /** Member access using brackets for unsafe field names. */
    public static String memberAccess(String objExpr, String field) {
        if (isSafeIdentifier(field)) {
            return objExpr + "." + field;
        }
        return objExpr + stringKeyLiteral(field);
    }

    /** Table-constructor entry using brackets for unsafe keys. */
    public static String tableField(String key, String valueExpr) {
        if (isSafeIdentifier(key)) {
            return key + " = " + valueExpr;
        }
        return stringKeyLiteral(key) + " = " + valueExpr;
    }
}
