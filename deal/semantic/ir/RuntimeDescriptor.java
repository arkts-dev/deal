package deal.semantic.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public sealed interface RuntimeDescriptor extends OpResultType
    permits RuntimeDescriptor.Null,
            RuntimeDescriptor.Boolean,
            RuntimeDescriptor.Number,
            RuntimeDescriptor.String,
            RuntimeDescriptor.Table,
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
     * the builtin {@code Error} atom, the
     * {@code @modulePath/ClassName} class atom, {@code [element]},
     * {@code ?inner}, {@code (p1,p2)->r}, {@code async(p1,p2)->r}.
     *
     * <p>The superseded v1.2 aliases {@code @/Error} and
     * {@code @$builtin/Error} never decode: the builtin class is exactly
     * the bare {@code Error} atom, and the reserved {@code $builtin}
     * namespace forms no class identity. A malformed or legacy class text
     * is the documented decode failure, never a fallback identity.</p>
     *
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
            // everything after it. The superseded v1.2 builtin spellings
            // are rejected at this decoding boundary: '@' requires a
            // non-empty module path, so '@/Error' (the empty builtin module
            // path) and '@$builtin/Error' (the reserved namespace) never
            // decode to an identity. The canonical builtin class is the
            // bare 'Error' atom below; no-slash segments (@User) and empty
            // class names (@src/, @/) stay rejected.
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
            java.lang.String modulePath = segment.substring(0, slash);
            if (modulePath.isEmpty()
                    || modulePath.equals("$builtin")
                    || modulePath.startsWith("$builtin/")) {
                throw new SemanticIrTextDecodeException(
                    "not a canonical descriptor text (the builtin Error class is the bare"
                        + " 'Error' atom; an '@' class atom requires a module path): \""
                        + text + "\"");
            }
            try {
                ClassId classId = new ClassId(modulePath,
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
        java.lang.String[] fixed = {"null", "boolean", "number", "string", "table"};
        RuntimeDescriptor[] mapped = {Null.INSTANCE, Boolean.INSTANCE,
            Number.INSTANCE, String.INSTANCE, Table.INSTANCE};
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
        // The builtin Error atom: the one class identity without the '@'.
        if (text.startsWith("Error", pos[0])) {
            int end = pos[0] + "Error".length();
            if (end == text.length() || text.charAt(end) == ')'
                    || text.charAt(end) == ']' || text.charAt(end) == ',') {
                pos[0] = end;
                return new Class(ClassId.ERROR);
            }
        }
        throw new SemanticIrTextDecodeException(
            "not a canonical descriptor text at offset " + pos[0] + ": \"" + text + "\"");
    }

    private static RuntimeDescriptor parseFunc(java.lang.String text, int[] pos, boolean isAsync) {
        expect(text, pos, '(');
        List<RuntimeDescriptor> params = new ArrayList<>();
        requireMore(text, pos);
        if (text.charAt(pos[0]) != ')') {
            while (true) {
                params.add(parseAt(text, pos, false));
                requireMore(text, pos);
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

    /**
     * Fails a truncated descriptor text as the documented decode
     * exception, never as an unchecked index error: the parameter-list
     * states of the grammar inspect the next character, so the shared
     * descriptor owner normalizes its end position here.
     */
    private static void requireMore(java.lang.String text, int[] pos) {
        if (pos[0] >= text.length()) {
            throw new SemanticIrTextDecodeException(
                "not a canonical descriptor text (unexpected end): \"" + text + "\"");
        }
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

    /**
     * The canonical nullable wrap of one descriptor — the descriptor-side
     * counterpart of {@link deal.types.Types#nullable}. The wrap is the
     * spec's {@code T | null} type, so it is canonicalized at construction:
     * an already-nullable descriptor is its own wrap, and the {@code null}
     * descriptor is too, because {@code null | null} is {@code null}
     * ({@code docs/spec-v1.4.md} §Field access — an optional {@code n?:
     * null} field's read yields language {@code null}). The forbidden
     * {@code ?null} form is therefore never constructed by a read-result
     * path, and no consumer has to normalize it.
     */
    static RuntimeDescriptor nullable(RuntimeDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        if (descriptor instanceof Null || descriptor instanceof Nullable) {
            return descriptor;
        }
        return new Nullable(descriptor);
    }

    /** The {@code boolean} descriptor; canonical text {@code "boolean"}. */
    enum Boolean implements RuntimeDescriptor {
        INSTANCE;

        @Override
        public java.lang.String canonicalSpecText() {
            return "boolean";
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
     * A nominal class descriptor; canonical text
     * {@code @modulePath/ClassName}, or exactly {@code Error} for the
     * builtin {@code Error} class (the one class atom without the
     * {@code @}).
     *
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
