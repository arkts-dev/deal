package deal.semantic.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The sealed structural runtime descriptor of {@code deal.semantic-ir/1}
 * (parent D6; schema S2/S3).
 *
 * <p>Closed hierarchy — exactly the variants below and no others:
 * {@code null}, {@code boolean}, signed32 {@code int}, {@code number},
 * {@code string}, {@code table}, {@code bytes}, {@code class(ClassId)},
 * {@code array}, {@code nullable}, and sync/async {@code function}.
 * Structural equality is authoritative. {@code int} is signed 32-bit: no
 * safe-range (±2^53−1) representation exists anywhere in the schema.
 * {@code bytes} is the v1.2 mutable reference type (ISSUE-0158 added the
 * member with the bytes comparison row): identity-compared, non-jsonable,
 * copied by reference, descriptor text {@code "bytes"}. Descriptor
 * <em>production</em> from checked {@code Type}s is the
 * {@code DescriptorService}'s (ISSUE-0233) and is excluded here; this
 * hierarchy is the sealed data type the service produces into.</p>
 *
 * <p>Canonical spec text (parent D6, {@code docs/spec-v1.2.md:2231-2283}):
 * {@code null}, {@code boolean}, {@code int}, {@code number},
 * {@code string}, {@code table}, {@code bytes}; {@code ClassDescriptor} =
 * {@code @modulePath/ClassName} (target class names never appear);
 * {@code ArrayDescriptor} = {@code "[" RuntimeTypeDescriptor "]"};
 * {@code NullableDescriptor} = {@code "?" RuntimeTypeDescriptor} with
 * inner never {@code null} and never another nullable;
 * {@code FunctionDescriptor} = {@code AsyncMarker? "(" ParamDescriptorList?
 * ")" "->" RuntimeTypeDescriptor} with {@code ","}-joined parameter texts
 * and no spaces. Examples: {@code [int]}, {@code ?string},
 * {@code ?[@src/app/User]}, {@code (int,string)->boolean},
 * {@code ()->null}, {@code async(int)->string}, {@code [bytes]},
 * {@code ?bytes}.</p>
 *
 * <p>{@link #parseCanonicalText(String)} is the exact inverse of
 * {@code canonicalSpecText()}: it decodes pinned descriptor text back into
 * the sealed variants. It is the single descriptor-text decoder the
 * validator's text surface uses; a text that is not a canonical descriptor
 * spelling is a transport-level decode failure
 * ({@link SemanticIrTextDecodeException}), never a validator rule and
 * never E6005.</p>
 */
public sealed interface RuntimeDescriptor extends OpResultType
    permits RuntimeDescriptor.Null,
            RuntimeDescriptor.Boolean,
            RuntimeDescriptor.Int,
            RuntimeDescriptor.Number,
            RuntimeDescriptor.String,
            RuntimeDescriptor.Table,
            RuntimeDescriptor.Bytes,
            RuntimeDescriptor.Class,
            RuntimeDescriptor.Array,
            RuntimeDescriptor.Nullable,
            RuntimeDescriptor.Func {

    /** The canonical spec text of this descriptor (parent D6 grammar). */
    java.lang.String canonicalSpecText();

    /**
     * Parses a canonical descriptor spec text back into the sealed
     * variants — the exact inverse of {@link #canonicalSpecText()}. The
     * whole text must be consumed: {@code null}, {@code boolean},
     * {@code int}, {@code number}, {@code string}, {@code table},
     * {@code bytes}, {@code @modulePath/ClassName}, {@code [element]},
     * {@code ?inner}, {@code (p1,p2)->r}, {@code async(p1,p2)->r}.
     *
     * @param text the canonical descriptor text; non-null
     * @return the decoded descriptor
     * @throws SemanticIrTextDecodeException if the text is not a canonical
     *         descriptor spelling (transport-level rejection, never E6005)
     */
    static RuntimeDescriptor parseCanonicalText(java.lang.String text) {
        Objects.requireNonNull(text, "text must not be null");
        int[] pos = {0};
        RuntimeDescriptor descriptor = parseAt(text, pos, false);
        if (pos[0] != text.length()) {
            throw new SemanticIrTextDecodeException(
                "not a canonical descriptor text (trailing content at offset " + pos[0]
                    + "): \"" + text + "\"");
        }
        return descriptor;
    }

    /** Recursive-descent decoder over the canonical descriptor grammar. */
    private static RuntimeDescriptor parseAt(java.lang.String text, int[] pos, boolean inNulled) {
        if (pos[0] >= text.length()) {
            throw new SemanticIrTextDecodeException(
                "not a canonical descriptor text (unexpected end): \"" + text + "\"");
        }
        char c = text.charAt(pos[0]);
        if (c == '[') {
            pos[0]++;
            RuntimeDescriptor element = parseAt(text, pos, false);
            expect(text, pos, ']');
            return new Array(element);
        }
        if (c == '?') {
            pos[0]++;
            RuntimeDescriptor inner = parseAt(text, pos, true);
            try {
                return new Nullable(inner);
            } catch (IllegalArgumentException e) {
                throw new SemanticIrTextDecodeException(
                    "not a canonical descriptor text (invalid nullable inner): \""
                        + text + "\"");
            }
        }
        if (c == '@') {
            // @modulePath/ClassName: the class segment ends at the next
            // ')' / ']' / ',' (none of those may appear inside a module
            // path or class name) or at the end of the text; the module
            // path is everything before the last '/', the class name
            // everything after it. An empty module path is the pinned
            // builtin spelling (@/Error == ClassId.ERROR); no-slash
            // segments (@User) and empty class names (@src/, @/) stay
            // rejected.
            int end = pos[0] + 1;
            while (end < text.length() && text.charAt(end) != ')'
                    && text.charAt(end) != ']' && text.charAt(end) != ',') {
                end++;
            }
            java.lang.String segment = text.substring(pos[0] + 1, end);
            int slash = segment.lastIndexOf('/');
            if (slash < 0 || slash == segment.length() - 1) {
                throw new SemanticIrTextDecodeException(
                    "not a canonical descriptor text (class text lacks modulePath/ClassName): \""
                        + text + "\"");
            }
            try {
                ClassId classId = new ClassId(segment.substring(0, slash),
                    segment.substring(slash + 1));
                pos[0] = end;
                return new Class(classId);
            } catch (IllegalArgumentException e) {
                throw new SemanticIrTextDecodeException(
                    "not a canonical descriptor text (invalid class identity): \""
                        + text + "\"");
            }
        }
        if (c == 'a' && text.startsWith("async(", pos[0])) {
            pos[0] += "async".length();
            return parseFunc(text, pos, true);
        }
        if (c == '(') {
            return parseFunc(text, pos, false);
        }
        // Fixed scalar spellings.
        java.lang.String[] fixed = {"null", "boolean", "int", "number", "string", "bytes",
            "table"};
        RuntimeDescriptor[] mapped = {Null.INSTANCE, Boolean.INSTANCE, Int.INSTANCE,
            Number.INSTANCE, String.INSTANCE, Bytes.INSTANCE, Table.INSTANCE};
        for (int i = 0; i < fixed.length; i++) {
            if (text.startsWith(fixed[i], pos[0])) {
                int end = pos[0] + fixed[i].length();
                if (end == text.length() || text.charAt(end) == ')'
                        || text.charAt(end) == ']' || text.charAt(end) == ',') {
                    pos[0] = end;
                    return mapped[i];
                }
            }
        }
        throw new SemanticIrTextDecodeException(
            "not a canonical descriptor text at offset " + pos[0] + ": \"" + text + "\"");
    }

    private static RuntimeDescriptor parseFunc(java.lang.String text, int[] pos, boolean isAsync) {
        expect(text, pos, '(');
        List<RuntimeDescriptor> params = new ArrayList<>();
        if (text.charAt(pos[0]) != ')') {
            while (true) {
                params.add(parseAt(text, pos, false));
                if (text.charAt(pos[0]) == ',') {
                    pos[0]++;
                    continue;
                }
                break;
            }
        }
        expect(text, pos, ')');
        expect(text, pos, '-');
        expect(text, pos, '>');
        RuntimeDescriptor returnType = parseAt(text, pos, false);
        return new Func(params, returnType, isAsync);
    }

    private static void expect(java.lang.String text, int[] pos, char expected) {
        if (pos[0] >= text.length() || text.charAt(pos[0]) != expected) {
            throw new SemanticIrTextDecodeException(
                "not a canonical descriptor text (expected '" + expected + "' at offset "
                    + pos[0] + "): \"" + text + "\"");
        }
        pos[0]++;
    }

    /** The {@code null} descriptor; canonical text {@code "null"}. */
    enum Null implements RuntimeDescriptor {
        INSTANCE;

        @Override
        public java.lang.String canonicalSpecText() {
            return "null";
        }
    }

    /** The {@code boolean} descriptor; canonical text {@code "boolean"}. */
    enum Boolean implements RuntimeDescriptor {
        INSTANCE;

        @Override
        public java.lang.String canonicalSpecText() {
            return "boolean";
        }
    }

    /**
     * The signed32 {@code int} descriptor; canonical text {@code "int"}.
     *
     * <p>Integer descriptor values are signed 32-bit
     * ({@code [-2147483648, 2147483647]}); every integer descriptor path
     * delegates to signed32 and can produce E8004 after kind/integrality
     * validation. No safe-range representation exists.</p>
     */
    enum Int implements RuntimeDescriptor {
        INSTANCE;

        @Override
        public java.lang.String canonicalSpecText() {
            return "int";
        }
    }

    /** The {@code number} descriptor; canonical text {@code "number"}. */
    enum Number implements RuntimeDescriptor {
        INSTANCE;

        @Override
        public java.lang.String canonicalSpecText() {
            return "number";
        }
    }

    /** The {@code string} descriptor; canonical text {@code "string"}. */
    enum String implements RuntimeDescriptor {
        INSTANCE;

        @Override
        public java.lang.String canonicalSpecText() {
            return "string";
        }
    }

    /** The {@code table} descriptor; canonical text {@code "table"}. */
    enum Table implements RuntimeDescriptor {
        INSTANCE;

        @Override
        public java.lang.String canonicalSpecText() {
            return "table";
        }
    }

    /**
     * The v1.2 {@code bytes} descriptor; canonical text {@code "bytes"}
     * (ISSUE-0158 added the member together with the
     * {@code BYTES_EQ}/{@code BYTES_NE} comparison row).
     *
     * <p>Bytes values are mutable reference-typed buffers with a
     * signed-int32 logical length: reference identity comparison, no
     * JSON representation, copied by reference. The descriptor carries no
     * payload — the identity of a bytes value is the allocation identity
     * of its runtime carrier, never descriptor text.</p>
     */
    enum Bytes implements RuntimeDescriptor {
        INSTANCE;

        @Override
        public java.lang.String canonicalSpecText() {
            return "bytes";
        }
    }

    /**
     * A nominal class descriptor; canonical text {@code @modulePath/ClassName}.
     *
     * @param classId the closed class identity; non-null
     */
    record Class(ClassId classId) implements RuntimeDescriptor {

        public Class {
            Objects.requireNonNull(classId, "classId must not be null");
        }

        @Override
        public java.lang.String canonicalSpecText() {
            return classId.text();
        }
    }

    /**
     * An array descriptor; canonical text {@code "[" element "]"}. Function
     * and nullable elements keep the spec bracket form (no legacy
     * {@code T[]}/{@code T|null} spellings exist in the schema).
     *
     * @param element the element descriptor; non-null
     */
    record Array(RuntimeDescriptor element) implements RuntimeDescriptor {

        public Array {
            Objects.requireNonNull(element, "element must not be null");
        }

        @Override
        public java.lang.String canonicalSpecText() {
            return "[" + element.canonicalSpecText() + "]";
        }
    }

    /**
     * A nullable descriptor; canonical text {@code "?" inner}.
     *
     * <p>Invariants enforced at construction (the pinned spec constraint):
     * the inner descriptor must not be {@code null} and must not be
     * another nullable (flatten at construction).</p>
     *
     * @param inner the inner descriptor; non-null, not {@code Null}, not nullable
     */
    record Nullable(RuntimeDescriptor inner) implements RuntimeDescriptor {

        public Nullable {
            Objects.requireNonNull(inner, "inner must not be null");
            if (inner instanceof Null) {
                throw new IllegalArgumentException(
                    "Nullable inner must not be null; use Null.INSTANCE directly");
            }
            if (inner instanceof Nullable) {
                throw new IllegalArgumentException(
                    "Nullable inner must not be another Nullable; flatten at construction");
            }
        }

        @Override
        public java.lang.String canonicalSpecText() {
            return "?" + inner.canonicalSpecText();
        }
    }

    /**
     * A function descriptor; canonical text
     * {@code (p1,p2)->r} (sync) or {@code async(p1,p2)->r} (async), with
     * parameter texts joined by {@code ","} and no spaces.
     *
     * @param paramTypes the parameter descriptors in order; non-null
     * @param returnType the return descriptor; non-null
     * @param isAsync    whether the function is async
     */
    record Func(List<RuntimeDescriptor> paramTypes, RuntimeDescriptor returnType, boolean isAsync)
        implements RuntimeDescriptor {

        public Func(List<RuntimeDescriptor> paramTypes, RuntimeDescriptor returnType, boolean isAsync) {
            this.paramTypes = List.copyOf(paramTypes);
            this.returnType = Objects.requireNonNull(returnType, "returnType must not be null");
            this.isAsync = isAsync;
        }

        /** Convenience constructor: sync function ({@code isAsync = false}). */
        public Func(List<RuntimeDescriptor> paramTypes, RuntimeDescriptor returnType) {
            this(paramTypes, returnType, false);
        }

        @Override
        public java.lang.String canonicalSpecText() {
            StringBuilder sb = new StringBuilder();
            if (isAsync) {
                sb.append("async");
            }
            sb.append("(");
            for (int i = 0; i < paramTypes.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(paramTypes.get(i).canonicalSpecText());
            }
            sb.append(")->").append(returnType.canonicalSpecText());
            return sb.toString();
        }
    }
}
