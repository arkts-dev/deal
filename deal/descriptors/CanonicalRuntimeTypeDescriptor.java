package deal.descriptors;

import deal.identity.CanonicalClassIdentityIndex;
import deal.types.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The canonical runtime type descriptor service (DEAL v1.2): one
 * per-compilation instance over the compilation's
 * {@link CanonicalClassIdentityIndex} (design source
 * {@code canonical-type-system-and-runtime-descriptors} D2/D5), with
 * strict static {@linkplain #parse(String) parsing}, verbatim static
 * {@linkplain #render(DescriptorAst) rendering}, and the instance
 * {@linkplain #encode(Type) encode} surface — the single Type→text
 * authority; no second Type→text producer may exist.
 *
 * <pre>
 * descriptor := primitive | class | array | nullable | function
 * primitive  := "null" | "boolean" | "int" | "number" | "string" | "bytes" | "table"
 * class      := "@" component ("/" component)+
 * array      := "[" descriptor "]"
 * nullable   := "?" descriptor
 * function   := "async"? "(" (descriptor ("," descriptor)*)? ")" "-&gt;" descriptor
 * </pre>
 *
 * <p>A component is a non-empty maximal scalar run that is not
 * {@code .}/{@code ..} as a whole component and is free of U+0000,
 * C0/DEL controls, Unicode whitespace, {@code @ [ ] ? ( ) , /}, and
 * contiguous {@code -&gt;}.  The final component is the class name and must
 * match the source identifier shape {@code [A-Za-z_][A-Za-z0-9_]*}; all
 * other components are text-opaque (dots are legal inside non-final
 * components).</p>
 *
 * <p>Parsing is strict recursive descent over decoded Unicode scalars
 * with complete-input consumption.  Offsets in
 * {@link DescriptorSyntaxError} are 0-based scalar offsets.  Legacy
 * spellings ({@code T[]}, {@code T|null}, rest-parameter sigs, bare
 * class names, dotted class-name text) never parse to a valid
 * descriptor.  Parsing never throws and never returns a partial AST.</p>
 *
 * <p>Rendering is total and verbatim: primitive names render as-is, a
 * {@link DescriptorAst.ClassAtom} renders its full text unchanged, and
 * array/nullable/function atoms rebuild {@code [D]}/{@code ?D}/
 * {@code async? (...) -&gt; D}.  For every accepted text,
 * {@code render(parse(text))} is byte-identical to {@code text}, and
 * {@code parse(render(ast))} round-trips for every atom.</p>
 *
 * <p>Encoding is total for class-free legal types and never emits a
 * legacy spelling ({@code T[]}, {@code T|null}, bare names, rest sigs):
 * primitives encode verbatim ({@code bytes} → {@code bytes});
 * {@code Array<T>} → {@code [D]}; {@code Nullable<T>} → {@code ?D};
 * functions carry the exact sync/async marker, the ordered parameter
 * descriptors, and the return descriptor.  {@link Type.Error} — the
 * internal sentinel — has no descriptor and is a pinned internal
 * invariant violation (internal error, never fallback text, never an
 * artifact).  {@link Type.Class} encoding lands with the
 * identity-carriage sibling: at this stage it is an explicit internal
 * invariant violation; the class branch consumes the held index.  For
 * every class-free legal type {@code T},
 * {@code render(parse(encode(T)))} equals {@code encode(T)}
 * byte-for-byte.</p>
 *
 * <p>The runtime matcher remains a separate service layer.</p>
 */
public final class CanonicalRuntimeTypeDescriptor {

    /**
     * The pinned canonical descriptor text of
     * {@code deal.types.Type.Bytes.INSTANCE} (parent D3: "its descriptor
     * is {@code bytes}").  Parsing this constant yields a
     * {@link DescriptorAst.PrimitiveAtom} whose name is this constant,
     * and encoding {@code Type.Bytes.INSTANCE} returns exactly this
     * constant.
     */
    public static final String BYTES_DESCRIPTOR = "bytes";

    /** The canonical primitive keywords, in grammar order. */
    private static final List<String> PRIMITIVE_KEYWORDS =
        List.of("null", "boolean", "int", "number", "string", BYTES_DESCRIPTOR, "table");

    /**
     * The compilation's canonical class-identity index.  The class encode
     * branch (the identity-carriage sibling) consumes this index; this
     * class never recomputes or reverse-parses identity text.
     */
    private final CanonicalClassIdentityIndex index;

    /**
     * Constructs the per-compilation descriptor service over the
     * compilation's validated {@link CanonicalClassIdentityIndex}
     * (design source {@code canonical-type-system-and-runtime-descriptors}
     * D5: one service instance per compilation, constructed after the
     * identity layer's index exists).
     *
     * @param index the compilation's canonical class-identity index;
     *              never {@code null}
     */
    public CanonicalRuntimeTypeDescriptor(CanonicalClassIdentityIndex index) {
        this.index = Objects.requireNonNull(index, "index must not be null");
    }

    // =========================================================================
    // encode
    // =========================================================================

    /**
     * Encodes a checked {@link Type} to canonical descriptor text — the
     * single Type→text authority of the compilation.
     *
     * <ul>
     *   <li>Primitives verbatim: {@code null}, {@code boolean},
     *       {@code int}, {@code number}, {@code string}, {@code bytes},
     *       {@code table}.</li>
     *   <li>{@code Array<T>} → {@code [D]}; {@code Nullable<T>} →
     *       {@code ?D}; functions carry the exact sync/async marker plus
     *       the ordered parameter descriptors and the return
     *       descriptor.</li>
     *   <li>No legacy spelling ({@code T[]}, {@code T|null}, bare
     *       names, rest sigs) is ever emitted for any input.</li>
     * </ul>
     *
     * <p>Total for class-free legal types: for every such {@code T},
     * {@code render(parse(encode(T)))} equals {@code encode(T)}
     * byte-for-byte.</p>
     *
     * <p><b>Internal invariant violations:</b> {@link Type.Error} (the
     * internal sentinel) has no descriptor and {@link Type.Class} is not
     * encodable until the class branch lands with the identity-carriage
     * sibling; both raise {@link IllegalStateException} — an internal
     * error with never a fallback text and never an artifact.</p>
     *
     * @param type the checked type to encode (never {@code null})
     * @return the canonical descriptor text
     * @throws NullPointerException     when {@code type} is {@code null}
     * @throws IllegalStateException    when {@code type} is
     *                                  {@link Type.Error} or
     *                                  {@link Type.Class} (pinned
     *                                  internal invariant violations at
     *                                  this stage)
     */
    public String encode(Type type) {
        Objects.requireNonNull(type, "type must not be null");
        return switch (type) {
            case Type.Null ignored -> "null";
            case Type.Boolean ignored -> "boolean";
            case Type.Int ignored -> "int";
            case Type.Number ignored -> "number";
            case Type.String ignored -> "string";
            case Type.Bytes ignored -> BYTES_DESCRIPTOR;
            case Type.Table ignored -> "table";
            case Type.Array a -> "[" + encode(a.element()) + "]";
            case Type.Nullable n -> "?" + encode(n.inner());
            case Type.Func f -> encodeFunction(f);
            case Type.Error ignored -> throw new IllegalStateException(
                "Type.Error is the internal sentinel and has no canonical "
                + "descriptor; it must never be silently emitted and never "
                + "reach artifact publication");
            case Type.Class c -> throw new IllegalStateException(
                "Type.Class is not encodable in the class-free stage: the "
                + "class branch lands with the identity-carriage sibling; "
                + "got " + c);
        };
    }

    /**
     * {@code async? "(" (D ("," D)*)? ")" "->" D} — the exact sync/async
     * marker, the ordered parameter descriptors, and the return
     * descriptor.
     */
    private String encodeFunction(Type.Func f) {
        StringBuilder sb = new StringBuilder();
        if (f.isAsync()) {
            sb.append("async");
        }
        sb.append('(');
        for (int i = 0; i < f.paramTypes().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(encode(f.paramTypes().get(i)));
        }
        sb.append(")->");
        sb.append(encode(f.returnType()));
        return sb.toString();
    }

    // =========================================================================
    // parse
    // =========================================================================

    /**
     * Strictly parses complete canonical descriptor text.
     *
     * @param text descriptor text ({@code null} is treated as empty input)
     * @return the complete {@link DescriptorAst}, or a
     *         {@link DescriptorSyntaxError} with the 0-based scalar offset
     *         of the failure — never {@code null}, never an exception, never
     *         a partial AST
     */
    public static DescriptorParseResult parse(String text) {
        Parser parser = new Parser(text == null ? "" : text);
        DescriptorParseResult result = parser.parseDescriptor();
        if (result instanceof DescriptorSyntaxError) {
            return result;
        }
        if (parser.pos < parser.cps.length) {
            // A complete descriptor was parsed but scalars remain: complete
            // consumption is mandatory (this rejects the legacy T[]/T|null
            // and any other trailing-content spellings).
            return new DescriptorSyntaxError(
                parser.pos,
                DescriptorSyntaxError.Kind.TRAILING_CONTENT,
                "unexpected trailing content at scalar offset " + parser.pos);
        }
        return result;
    }

    // =========================================================================
    // render
    // =========================================================================

    /**
     * Renders an atom back to canonical descriptor text verbatim: primitive
     * names as-is, a class atom's full text unchanged, and
     * array/nullable/function atoms rebuilt as {@code [D]}/{@code ?D}/
     * {@code async? (...) -> D}.
     *
     * <p>Total for every atom: for every accepted text,
     * {@code render(parse(text))} equals {@code text} byte-for-byte.</p>
     *
     * @param ast the atom to render
     * @return the byte-identical canonical descriptor text
     */
    public static String render(DescriptorAst ast) {
        Objects.requireNonNull(ast, "ast must not be null");
        if (ast instanceof DescriptorAst.PrimitiveAtom p) {
            return p.name();
        }
        if (ast instanceof DescriptorAst.ClassAtom c) {
            // Verbatim: the full text including the leading '@', never
            // re-derived or re-split.
            return c.fullDescriptorText();
        }
        if (ast instanceof DescriptorAst.ArrayAtom a) {
            return "[" + render(a.element()) + "]";
        }
        if (ast instanceof DescriptorAst.NullableAtom n) {
            return "?" + render(n.inner());
        }
        DescriptorAst.FunctionAtom f = (DescriptorAst.FunctionAtom) ast;
        StringBuilder sb = new StringBuilder();
        if (f.isAsync()) {
            sb.append("async");
        }
        sb.append('(');
        for (int i = 0; i < f.params().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(render(f.params().get(i)));
        }
        sb.append(")->");
        sb.append(render(f.returnType()));
        return sb.toString();
    }

    // =========================================================================
    // Strict recursive-descent parser over decoded Unicode scalars
    // =========================================================================

    /** Recursive-descent parser state over a decoded scalar (code point) array. */
    private static final class Parser {
        /** The input, decoded into Unicode scalar values. */
        private final int[] cps;

        /** Current position, a 0-based scalar offset. */
        private int pos;

        private Parser(String text) {
            this.cps = text.codePoints().toArray();
        }

        /**
         * Parses one descriptor at the current position.  On success the
         * position is advanced past the complete descriptor; on failure a
         * {@link DescriptorSyntaxError} is returned and no partial AST is
         * produced.
         */
        private DescriptorParseResult parseDescriptor() {
            if (pos >= cps.length) {
                return unexpectedEnd();
            }
            int cp = cps[pos];
            if (cp == '[') {
                return parseArray();
            }
            if (cp == '?') {
                return parseNullable();
            }
            if (cp == '(') {
                return parseFunction(false);
            }
            if (cp == '@') {
                return parseClass();
            }
            // Primitive keywords (disjoint, so order does not matter).
            for (String keyword : PRIMITIVE_KEYWORDS) {
                if (matchKeyword(keyword)) {
                    pos += keyword.length();
                    return new DescriptorAst.PrimitiveAtom(keyword);
                }
            }
            // The exact async marker: "async" must be immediately followed
            // by '('; any other continuation is a failure at the scalar
            // that is not '('.
            if (matchKeyword("async")
                    && (pos + 5 >= cps.length || !isIdentifierScalar(cps[pos + 5]))) {
                int after = pos + 5;
                if (after >= cps.length) {
                    return new DescriptorSyntaxError(
                        after,
                        DescriptorSyntaxError.Kind.UNEXPECTED_END,
                        "expected '(' after 'async' at scalar offset " + after);
                }
                if (cps[after] != '(') {
                    return new DescriptorSyntaxError(
                        after,
                        DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER,
                        "expected '(' after 'async' but found "
                            + scalarName(cps[after]) + " at scalar offset " + after);
                }
                pos = after;
                return parseFunction(true);
            }
            // Identifier-shaped run: a bare class name when it terminates the
            // descriptor position, otherwise an unexpected scalar right after
            // the identifier (legacy T[]/T|null shapes land here).
            int run = pos;
            while (run < cps.length && isIdentifierScalar(cps[run])) {
                run++;
            }
            if (run > pos) {
                if (run >= cps.length || cps[run] == ']' || cps[run] == ')' || cps[run] == ',') {
                    String name = new String(cps, pos, run - pos);
                    return new DescriptorSyntaxError(
                        pos,
                        DescriptorSyntaxError.Kind.BARE_CLASS_NAME,
                        "bare class name '" + name + "' at scalar offset " + pos
                            + "; class descriptors must start with '@'");
                }
                return new DescriptorSyntaxError(
                    run,
                    DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER,
                    "unexpected character " + scalarName(cps[run])
                        + " at scalar offset " + run);
            }
            return new DescriptorSyntaxError(
                pos,
                DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER,
                "unexpected character " + scalarName(cp) + " at scalar offset " + pos);
        }

        /** {@code [ descriptor ]}. */
        private DescriptorParseResult parseArray() {
            pos++; // consume '['
            DescriptorParseResult inner = parseDescriptor();
            if (inner instanceof DescriptorSyntaxError error) {
                return error;
            }
            if (pos >= cps.length) {
                return unexpectedEnd();
            }
            if (cps[pos] != ']') {
                return new DescriptorSyntaxError(
                    pos,
                    DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER,
                    "expected ']' but found " + scalarName(cps[pos])
                        + " at scalar offset " + pos);
            }
            pos++; // consume ']'
            return new DescriptorAst.ArrayAtom((DescriptorAst) inner);
        }

        /** {@code ? descriptor} with the pinned nested-nullable/null-inner rejections. */
        private DescriptorParseResult parseNullable() {
            pos++; // consume '?'
            int innerStart = pos;
            DescriptorParseResult inner = parseDescriptor();
            if (inner instanceof DescriptorSyntaxError error) {
                return error;
            }
            DescriptorAst innerAst = (DescriptorAst) inner;
            if (innerAst instanceof DescriptorAst.NullableAtom) {
                return new DescriptorSyntaxError(
                    innerStart,
                    DescriptorSyntaxError.Kind.NESTED_NULLABLE,
                    "nested nullable at scalar offset " + innerStart);
            }
            if (innerAst instanceof DescriptorAst.PrimitiveAtom p
                    && p.name().equals("null")) {
                return new DescriptorSyntaxError(
                    innerStart,
                    DescriptorSyntaxError.Kind.NULL_INNER,
                    "nullable of null at scalar offset " + innerStart);
            }
            return new DescriptorAst.NullableAtom(innerAst);
        }

        /**
         * {@code async? "(" (descriptor ("," descriptor)*)? ")" "->" descriptor}.
         * No rest-parameter arm exists in DEAL v1.2.
         */
        private DescriptorParseResult parseFunction(boolean isAsync) {
            pos++; // consume '('
            List<DescriptorAst> params = new ArrayList<>();
            if (pos < cps.length && cps[pos] != ')') {
                while (true) {
                    DescriptorParseResult param = parseDescriptor();
                    if (param instanceof DescriptorSyntaxError error) {
                        return error;
                    }
                    params.add((DescriptorAst) param);
                    if (pos >= cps.length) {
                        return unexpectedEnd();
                    }
                    if (cps[pos] == ',') {
                        pos++;
                        continue;
                    }
                    if (cps[pos] == ')') {
                        break;
                    }
                    return new DescriptorSyntaxError(
                        pos,
                        DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER,
                        "expected ',' or ')' but found " + scalarName(cps[pos])
                            + " at scalar offset " + pos);
                }
            }
            if (pos >= cps.length) {
                return unexpectedEnd();
            }
            pos++; // consume ')'
            // The exact "->" arrow.
            if (pos >= cps.length) {
                return unexpectedEnd();
            }
            if (cps[pos] != '-') {
                return new DescriptorSyntaxError(
                    pos,
                    DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER,
                    "expected '->' but found " + scalarName(cps[pos])
                        + " at scalar offset " + pos);
            }
            if (pos + 1 >= cps.length) {
                return unexpectedEnd();
            }
            if (cps[pos + 1] != '>') {
                return new DescriptorSyntaxError(
                    pos + 1,
                    DescriptorSyntaxError.Kind.UNEXPECTED_CHARACTER,
                    "expected '->' but found " + scalarName(cps[pos + 1])
                        + " at scalar offset " + (pos + 1));
            }
            pos += 2; // consume '->'
            DescriptorParseResult returnType = parseDescriptor();
            if (returnType instanceof DescriptorSyntaxError error) {
                return error;
            }
            return new DescriptorAst.FunctionAtom(isAsync, params, (DescriptorAst) returnType);
        }

        /**
         * {@code "@" component ("/" component)+} with the pinned class-atom
         * shape rules.  Components are maximal allowed runs; the atom ends
         * exactly at its enclosing delimiter (end of input, {@code ]},
         * {@code )}, {@code ,}) and is stored verbatim with the leading
         * {@code @}.  No root/path boundary is ever inferred.
         */
        private DescriptorParseResult parseClass() {
            int atomStart = pos;
            pos++; // consume '@'
            boolean hasSeparator = false;
            while (true) {
                int componentStart = pos;
                while (pos < cps.length) {
                    int cp = cps[pos];
                    if (cp == ']' || cp == ')' || cp == ',') {
                        break; // enclosing delimiter: the atom ends exactly here
                    }
                    if (cp == '/') {
                        break; // component separator
                    }
                    if (cp == '-' && pos + 1 < cps.length && cps[pos + 1] == '>') {
                        return new DescriptorSyntaxError(
                            pos,
                            DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM,
                            "contiguous '->' inside a class-atom component at scalar offset " + pos);
                    }
                    if (forbiddenInComponent(cp)) {
                        break; // maximal allowed run ends before the forbidden scalar
                    }
                    pos++;
                }
                if (pos == componentStart) {
                    return new DescriptorSyntaxError(
                        pos,
                        DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM,
                        "empty class-atom component at scalar offset " + pos);
                }
                String component = new String(cps, componentStart, pos - componentStart);
                if (component.equals(".") || component.equals("..")) {
                    return new DescriptorSyntaxError(
                        componentStart,
                        DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM,
                        "class-atom component '" + component
                            + "' at scalar offset " + componentStart);
                }
                if (pos < cps.length && cps[pos] == '/') {
                    hasSeparator = true;
                    pos++; // consume '/' — the next component must be non-empty
                    continue;
                }
                // This component terminates the atom, so it is the class name
                // and must match the source identifier shape.  Dotted text in
                // the class-name position (@src.models.User, @a/b.C,
                // @host.cfg.ServerConfig) fails exactly here.
                if (!isIdentifierShape(component)) {
                    return new DescriptorSyntaxError(
                        componentStart,
                        DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM,
                        "final class-atom component '" + component
                            + "' at scalar offset " + componentStart
                            + " is not identifier-shaped");
                }
                if (!hasSeparator) {
                    return new DescriptorSyntaxError(
                        pos,
                        DescriptorSyntaxError.Kind.INVALID_CLASS_ATOM,
                        "class atom with fewer than two components at scalar offset " + pos);
                }
                return new DescriptorAst.ClassAtom(
                    new String(cps, atomStart, pos - atomStart));
            }
        }

        /** The pinned component alphabet: forbidden scalars end the maximal run. */
        private static boolean forbiddenInComponent(int cp) {
            // U+0000 and C0/DEL controls.
            if (cp < 0x20 || cp == 0x7F) {
                return true;
            }
            switch (cp) {
                case '@', '[', ']', '?', '(', ')', ',' -> {
                    return true;
                }
                default -> { }
            }
            // Unicode whitespace (including space separators).
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                return true;
            }
            // A lone surrogate is not a decoded Unicode scalar.
            return cp >= 0xD800 && cp <= 0xDFFF;
        }

        /** The final-component class-name shape: {@code [A-Za-z_][A-Za-z0-9_]*}. */
        private static boolean isIdentifierShape(String component) {
            if (component.isEmpty()) {
                return false;
            }
            int first = component.codePointAt(0);
            if (!(first >= 'A' && first <= 'Z')
                    && !(first >= 'a' && first <= 'z')
                    && first != '_') {
                return false;
            }
            for (int i = 1; i < component.length(); i++) {
                char c = component.charAt(i);
                boolean ok = (c >= 'A' && c <= 'Z')
                        || (c >= 'a' && c <= 'z')
                        || (c >= '0' && c <= '9')
                        || c == '_';
                if (!ok) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isIdentifierScalar(int cp) {
            return (cp >= 'A' && cp <= 'Z')
                    || (cp >= 'a' && cp <= 'z')
                    || (cp >= '0' && cp <= '9')
                    || cp == '_';
        }

        /** Exact keyword match against the decoded scalars at the current position. */
        private boolean matchKeyword(String keyword) {
            if (pos + keyword.length() > cps.length) {
                return false;
            }
            for (int i = 0; i < keyword.length(); i++) {
                if (cps[pos + i] != keyword.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        private DescriptorSyntaxError unexpectedEnd() {
            return new DescriptorSyntaxError(
                pos,
                DescriptorSyntaxError.Kind.UNEXPECTED_END,
                "unexpected end of input at scalar offset " + pos);
        }

        private static String scalarName(int cp) {
            if (cp >= 0x20 && cp <= 0x7E) {
                return "'" + (char) cp + "'";
            }
            return String.format("U+%04X", cp);
        }
    }
}
