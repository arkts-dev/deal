package deal.codegen.lua;

import java.util.Set;

/**
 * Explicit Lua ABI emission layer (ISSUE-0074).
 *
 * <p>Owns the mandated name/key policies for generated Lua code:
 * <ul>
 *   <li>generated {@code $}-identifier references and class artifacts live in
 *       the {@link #NAMESPACE} table ({@code __deal}), so user bindings can
 *       never shadow them;</li>
 *   <li>table-field / member-access / export keys use the dot form iff the
 *       key is a safe Lua identifier, else the bracket-string form;</li>
 *   <li>helper names and export keys are derived per {@link HelperKind}.</li>
 * </ul>
 *
 * <p>The reusable decision surface ({@link #isReserved}, {@link #isSafeIdentifier},
 * {@link #fieldKeyForm}) is parameterized by a caller-supplied keyword set:
 * the Lua backend passes {@link #RESERVED} (the LuaJIT 24-word set), while a
 * future backend supplies its own target keyword set through the same
 * functions — there is no silent Lua-set binding for non-Lua targets.</p>
 *
 * <p>The Lua text composers ({@link #stringKeyLiteral}, {@link #memberAccess},
 * {@link #hasCheck}, {@link #tableField}, {@link #exportAssignment},
 * {@link #generatedRef}, {@link #helperRef}, {@link #namespaceAssignment})
 * are thin Lua-literal wrappers that evaluate the parameterized predicates
 * with {@code RESERVED}.</p>
 *
 * <p>This class is stateless and depends only on the JDK; it never depends
 * on {@code deal.ast}, {@code deal.checker}, or {@code deal.module}.</p>
 */
public final class LuaAbi {

    private LuaAbi() {
        // Stateless utility class — no instances.
    }

    /** Name of the generated-namespace table local in emitted Lua chunks. */
    public static final String NAMESPACE = "__deal";

    /**
     * The LuaJIT target keyword set: Lua 5.1's 21 reserved words plus the
     * three LuaJIT 2.1 hard tokens ({@code goto}, {@code const},
     * {@code continue}). Immutable.
     */
    public static final Set<String> RESERVED = Set.of(
        "and", "break", "do", "else", "elseif", "end", "false", "for",
        "function", "goto", "if", "in", "local", "nil", "not", "or",
        "repeat", "return", "then", "true", "until", "while",
        "const", "continue");

    /** The Lua emission encoding of the safe/unsafe key decision. */
    public enum KeyForm { DOT, BRACKET }

    /**
     * The six kinds of per-class generated artifacts. {@link #PLAN} is the
     * v1.2 extension of the frozen helper-key set (emitter page D4): the
     * module-scope compiler-class default-plan artifact ({@code <C>_plan})
     * holding the ordered field-entry list with evaluator closures. Host
     * modules never carry a PLAN key ({@code host-module-abi} D1/D2) —
     * host-declared classes keep the {@link #DEFAULTS} defaults-map seam.
     */
    public enum HelperKind { DEFAULTS, META, FIELDS, FROM_JSON, TO_JSON, PLAN }

    // =========================================================================
    // Backend-neutral decision surface (reusable for future backends)
    // =========================================================================

    /**
     * Returns true iff {@code name} is in the caller-supplied reserved set.
     */
    public static boolean isReserved(Set<String> reserved, String name) {
        if (name == null) return false;
        return reserved.contains(name);
    }

    /**
     * Returns true iff {@code name} has the DEAL identifier shape
     * (ASCII {@code [A-Za-z_][A-Za-z0-9_]*}) and is not in the
     * caller-supplied reserved set.
     *
     * <p>The ASCII shape rule is the DEAL identifier grammar; it also
     * rejects {@code $}-containing names, because Lua identifiers cannot
     * contain {@code $}.</p>
     */
    public static boolean isSafeIdentifier(Set<String> reserved, String name) {
        if (name == null || name.isEmpty()) return false;
        char first = name.charAt(0);
        if (!isAsciiLetter(first) && first != '_') return false;
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!isAsciiLetter(c) && c != '_' && (c < '0' || c > '9')) {
                return false;
            }
        }
        return !reserved.contains(name);
    }

    /**
     * Returns the emission key form for {@code name} under the
     * caller-supplied reserved set: {@link KeyForm#DOT} iff
     * {@code isSafeIdentifier(reserved, name)}, else
     * {@link KeyForm#BRACKET}.
     */
    public static KeyForm fieldKeyForm(Set<String> reserved, String name) {
        return isSafeIdentifier(reserved, name) ? KeyForm.DOT : KeyForm.BRACKET;
    }

    /**
     * Returns the raw DEAL artifact name for a class helper of the given
     * kind (e.g. {@code User_defaults}, {@code User$fromJson},
     * {@code User_plan}). The returned names are the frozen export keys;
     * {@code $} names are preserved raw.
     */
    public static String helperKey(String className, HelperKind kind) {
        return switch (kind) {
            case DEFAULTS -> className + "_defaults";
            case META -> className + "_meta";
            case FIELDS -> className + "_fields";
            case FROM_JSON -> className + "$fromJson";
            case TO_JSON -> className + "$toJson";
            case PLAN -> className + "_plan";
        };
    }

    // =========================================================================
    // Lua emission composers (evaluate the predicates with RESERVED)
    // =========================================================================

    /**
     * Returns a bracketed, quoted, Lua-5.1-escaped string key literal:
     * {@code ["<escaped key>"]}.
     */
    public static String stringKeyLiteral(String key) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\"");
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
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
                            i + 1 < key.length()
                                && isDecimalDigit(key.charAt(i + 1)));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"]");
        return sb.toString();
    }

    /**
     * Appends a {@code \ddd} decimal escape for a control byte. When the
     * next character is a decimal digit the escape is exactly three digits
     * (zero-padded), so the following digit is not absorbed into the
     * escape sequence (Lua 5.1 takes up to three decimal digits).
     */
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

    /**
     * Emits a member access on {@code objExpr} for DEAL field
     * {@code field}: {@code obj.field} when safe, else
     * {@code obj["<field>"]}.
     */
    public static String memberAccess(String objExpr, String field) {
        if (fieldKeyForm(RESERVED, field) == KeyForm.DOT) {
            return objExpr + "." + field;
        }
        return objExpr + stringKeyLiteral(field);
    }

    /**
     * Emits a {@code has()} presence check for a field:
     * {@code obj.field ~= nil} when safe, else
     * {@code obj["<field>"] ~= nil}.
     */
    public static String hasCheck(String objExpr, String field) {
        return memberAccess(objExpr, field) + " ~= nil";
    }

    /**
     * Emits a table-constructor field entry: {@code key = value} when the
     * key is safe, else {@code ["key"] = value}.
     */
    public static String tableField(String key, String valueExpr) {
        if (fieldKeyForm(RESERVED, key) == KeyForm.DOT) {
            return key + " = " + valueExpr;
        }
        return stringKeyLiteral(key) + " = " + valueExpr;
    }

    /**
     * Emits an assignment on the module's {@code exports} local:
     * {@code exports.key = value} when safe, else
     * {@code exports["key"] = value}.
     */
    public static String exportAssignment(String key, String valueExpr) {
        if (fieldKeyForm(RESERVED, key) == KeyForm.DOT) {
            return "exports." + key + " = " + valueExpr;
        }
        return "exports" + stringKeyLiteral(key) + " = " + valueExpr;
    }

    /**
     * Emits a reference to a generated (compiler-owned) DEAL name:
     * {@code __deal["<name>"]}.
     */
    public static String generatedRef(String dealName) {
        return NAMESPACE + stringKeyLiteral(dealName);
    }

    /**
     * Emits a reference to a class artifact:
     * {@code __deal["<C>_defaults"]}, {@code __deal["<C>$fromJson"]},
     * {@code __deal["<C>_plan"]}, etc.
     */
    public static String helperRef(String className, HelperKind kind) {
        return generatedRef(helperKey(className, kind));
    }

    /**
     * Emits an assignment of a generated artifact into the namespace
     * table: {@code __deal["<name>"] = <valueExpr>}.
     */
    public static String namespaceAssignment(String dealName, String valueExpr) {
        return generatedRef(dealName) + " = " + valueExpr;
    }
}
