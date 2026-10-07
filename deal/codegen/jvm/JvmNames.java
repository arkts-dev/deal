package deal.codegen.jvm;

import deal.descriptors.CanonicalRuntimeTypeDescriptor;
import deal.identity.CanonicalClassIdentityIndex;
import deal.identity.CanonicalModuleIdentity;
import deal.module.ModuleIdentityResolver;
import deal.types.Type;

import java.util.Map;
import java.util.Set;

/**
 * The static JVM emission naming surface: the class-file name derivation,
 * the DEAL-identifier translation, the injective identifier-safe escape,
 * the canonical runtime descriptor text, the function-shape ids, the
 * declared host-record names, and the async-export host constants.
 *
 * <p>The surface is a pure function of its inputs (no compilation
 * state); the production emitters, the JVM host ABI emission, the async
 * export invoker, the production project emission, and the retained
 * test-scope AST emitter all resolve names through it, so one name
 * derivation exists per name class.</p>
 */
public final class JvmNames {

    private JvmNames() {
        // Static naming surface only; no instances.
    }

    /** The argument name of the async-export host entry. */
    public static final String ASYNC_EXPORT_HOST_ARG = "$asyncExportHost";

    /** The synthesized async-export host class-name suffix. */
    public static final String ASYNC_EXPORT_HOST_CLASS_SUFFIX =
        "$AsyncExportHost";

    /**
     * Java reserved words. DEAL keywords are not identifiers, so this
     * list is exactly the Java keywords that can appear as DEAL
     * identifiers.
     */
    private static final Set<String> JAVA_RESERVED = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch",
        "char", "class", "const", "continue", "default", "do", "double",
        "else", "enum", "extends", "final", "finally", "float", "for",
        "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private",
        "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while", "_",
        "true", "false", "null");

    /**
     * The static identity index of {@link #typeDescriptor(Type)}:
     * {@code descriptorTextFor} projects from the identity carriers, so
     * one empty-classification index serves every identity.
     */
    private static final CanonicalClassIdentityIndex STATIC_DESCRIPTOR_INDEX =
        ModuleIdentityResolver.buildIndex(Map.of(
            "", CanonicalModuleIdentity.BuiltinModule.INSTANCE));

    /**
     * The one canonical descriptor service behind
     * {@link #typeDescriptor(Type)}: it delegates to
     * {@link CanonicalRuntimeTypeDescriptor#encode(Type)} — the
     * compilation's one Type&rarr;text producer — over the
     * identity-carrier projection index above.
     */
    private static final CanonicalRuntimeTypeDescriptor STATIC_DESCRIPTORS =
        new CanonicalRuntimeTypeDescriptor(STATIC_DESCRIPTOR_INDEX);

    /**
     * Derives the project class name of one module path: each slash- or
     * dot-separated segment is sanitized to a Java identifier and
     * capitalized; a reserved word is suffixed with {@code _}; an empty
     * path yields {@code Main}.
     */
    public static String classNameFor(String modulePath) {
        String path = modulePath == null ? "" : modulePath;
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("[/.]")) {
            String cleaned = sanitizeSegment(segment);
            if (cleaned.isEmpty()) continue;
            sb.append(Character.toUpperCase(cleaned.charAt(0)))
                .append(cleaned.substring(1));
        }
        String result = sb.toString();
        if (result.isEmpty()) result = "Main";
        return JAVA_RESERVED.contains(result) ? result + "_" : result;
    }

    /** Sanitizes one module-path segment to a Java identifier. */
    private static String sanitizeSegment(String segment) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            boolean ok = Character.isJavaIdentifierPart(c);
            if (i == 0) ok = ok && Character.isJavaIdentifierStart(c);
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    /**
     * Translates one DEAL identifier to its Java name: {@code $} becomes
     * {@code $d}, {@code _} becomes {@code $u}, and a name colliding
     * with a Java reserved word is prefixed with {@code _}.
     */
    public static String javaName(String dealIdentifier) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dealIdentifier.length(); i++) {
            char c = dealIdentifier.charAt(i);
            if (c == '$') sb.append("$d");
            else if (c == '_') sb.append("$u");
            else sb.append(c);
        }
        String encoded = sb.toString();
        if (JAVA_RESERVED.contains(encoded)) {
            return "_" + encoded;
        }
        return encoded;
    }

    /**
     * The injective identifier-safe escape of arbitrary text: ASCII
     * letters/digits stay raw, {@code $} becomes {@code $$}, {@code _}
     * becomes {@code $u}, the descriptor punctuation uses compact
     * two-character codes ({@code (}&rarr;{@code $l}, {@code )}&rarr;{@code $r},
     * {@code [}&rarr;{@code $B}, {@code ]}&rarr;{@code $E}, {@code ?}&rarr;{@code $Q},
     * {@code @}&rarr;{@code $a}, {@code -}&rarr;{@code $m}, {@code >}&rarr;{@code $g},
     * {@code ,}&rarr;{@code $c}, {@code .}&rarr;{@code $i}, {@code /}&rarr;{@code $s} —
     * keeping emitted class-file names far below the filesystem length
     * bound), and every other UTF-16 code unit becomes {@code $x} +
     * four lowercase hex digits. The code is prefix-free (a decoder
     * reading {@code $} consumes {@code $$}/{@code $u}/{@code $xhhhh}
     * or one two-character code greedily and unambiguously; {@code x}
     * never doubles as a two-character code), so distinct input texts
     * map to distinct output texts, and the output contains no raw
     * {@code _} — segment concatenations joined by {@code _} stay
     * injective.
     */
    public static String escapedIdentifier(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (c == '$') {
                sb.append("$$");
            } else if (c == '_') {
                sb.append("$u");
            } else {
                appendEscapedChar(sb, c);
            }
        }
        return sb.toString();
    }

    /** One escaped non-identifier code unit of {@link #escapedIdentifier}. */
    private static void appendEscapedChar(StringBuilder sb, char c) {
        switch (c) {
            case '(' -> sb.append("$l");
            case ')' -> sb.append("$r");
            case '[' -> sb.append("$B");
            case ']' -> sb.append("$E");
            case '?' -> sb.append("$Q");
            case '@' -> sb.append("$a");
            case '-' -> sb.append("$m");
            case '>' -> sb.append("$g");
            case ',' -> sb.append("$c");
            case '.' -> sb.append("$i");
            case '/' -> sb.append("$s");
            default -> sb.append(String.format("$x%04x", (int) c));
        }
    }

    /** The canonical runtime descriptor text of one type. */
    public static String typeDescriptor(Type t) {
        if (t == null) return "null";
        return STATIC_DESCRIPTORS.encode(t);
    }

    /**
     * The function-shape id of one function type: {@code Fn} plus
     * {@code A} for async, the parameter count, the per-parameter
     * segments, and the return segment.
     */
    public static String fnShapeId(Type.Func f) {
        StringBuilder sb = new StringBuilder("Fn");
        if (f.isAsync()) sb.append('A');
        sb.append(f.paramTypes().size());
        if (!f.paramTypes().isEmpty()) {
            sb.append('_');
            for (int i = 0; i < f.paramTypes().size(); i++) {
                if (i > 0) sb.append('_');
                sb.append(shapeSegment(f.paramTypes().get(i)));
            }
        }
        sb.append("_R_").append(shapeSegment(f.returnType()));
        return sb.toString();
    }

    /** One segment of {@link #fnShapeId}: the single-letter code for a
     * primitive, or {@code $} + the injectively escaped canonical
     * descriptor text for any other type. */
    private static String shapeSegment(Type t) {
        Character c = fnShapeLetter(t);
        if (c != null) return c.toString();
        return "$" + escapedIdentifier(typeDescriptor(t));
    }

    /** The one-letter shape code ({@code B}/{@code I}/{@code N}/
     * {@code S}/{@code V}/{@code Y} for boolean/int/number/string/null/
     * bytes). */
    private static Character fnShapeLetter(Type t) {
        return switch (t) {
            case Type.Boolean ignored -> 'B';
            case Type.Int ignored -> 'I';
            case Type.Number ignored -> 'N';
            case Type.String ignored -> 'S';
            case Type.Null ignored -> 'V';
            case Type.Bytes ignored -> 'Y';
            default -> null;
        };
    }

    /**
     * The simple name of the synthesized shared record of one declared
     * host class.
     */
    static String hostRecordSimpleName(String specifier,
                                       String className) {
        return "$Host$" + escapedIdentifier(
                specifier.replace('.', '/')) + "$"
            + javaName(className);
    }
}
